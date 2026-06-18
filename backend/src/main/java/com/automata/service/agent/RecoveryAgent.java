package com.automata.service.agent;

import com.automata.model.AgentReports.AttemptItem;
import com.automata.model.AgentReports.RecoveryReport;
import com.automata.model.AgentReports.ReviewerReport;
import com.automata.model.CompilationResult;
import com.automata.model.FileChange;
import com.automata.model.ValidationResult;
import com.automata.service.CodeValidatorService;
import com.automata.service.GitHubService;
import com.automata.service.LogStreamService;
import com.automata.service.ai.FallbackAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RecoveryAgent — True Self-Healing Loop with "Compiler-in-the-Loop".
 *
 * <p>Three-tier validation on each attempt:
 * <ol>
 *   <li><b>Gate 1 — Brace/syntax check</b> (fast, ~0ms): rejects immediately on unmatched braces.</li>
 *   <li><b>Gate 2 — Compiler sandbox</b> (~5-60s): runs actual {@code mvn compile} or {@code npm build}
 *       and feeds real compiler errors back into the LLM for the next healing attempt.</li>
 *   <li><b>Gate 3 — Reviewer Agent</b>: quality, security, and maintainability review.</li>
 * </ol>
 *
 * <p>Up to {@code maxAttempts} (default 3) healing cycles are run.
 * Each failed attempt feeds its specific error type (syntax / compiler / reviewer)
 * back to the LLM so the next patch is smarter.
 */
@Slf4j
@Service
public class RecoveryAgent {

    @Autowired
    private FallbackAiService fallbackAiService;

    @Autowired
    private CodeValidatorService codeValidatorService;

    @Autowired
    private GitHubService githubService;

    @Autowired
    private ReviewerAgent reviewerAgent;

    @Autowired
    private LogStreamService logStreamService;

    public static class RecoveryResult {
        public List<FileChange> finalChanges;
        public RecoveryReport report;
        public ReviewerReport reviewerReport;
        public boolean success;
    }

    public RecoveryResult runRecoveryLoop(List<FileChange> initialChanges,
                                          String preferredModel,
                                          boolean injectDemoError) {
        logStreamService.broadcast("[INFO] Recovery Agent initializing self-healing pipeline...");

        RecoveryResult result = new RecoveryResult();
        List<AttemptItem> attemptsList = new ArrayList<>();
        List<FileChange> currentChanges = new ArrayList<>(initialChanges);

        int maxAttempts = 3;
        boolean isSuccess = false;
        String lastCompilerOutput = null;   // persisted for RecoveryReport
        String validationMode = "brace_check"; // starts conservative; upgraded when compiler runs

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            logStreamService.broadcast("[INFO] ─────────────────────────────────────────────");
            logStreamService.broadcast("[INFO] Recovery Agent — Attempt " + attempt + "/" + maxAttempts);

            // ──────────────────────────────────────────────────────────────────
            // DEMO MODE: inject a syntax mismatch on attempt 1 for ticket PROD-1234
            // ──────────────────────────────────────────────────────────────────
            if (attempt == 1 && injectDemoError && !currentChanges.isEmpty()) {
                logStreamService.broadcast("[DEMO] Injecting syntax error (open brace) to demonstrate self-healing...");
                FileChange broken = currentChanges.get(0);
                currentChanges.set(0, FileChange.builder()
                        .path(broken.getPath())
                        .content(broken.getContent() + "\n// === DEMO INJECTED ERROR ===\nvoid brokenMethod() {")
                        .build());
            }

            // ──────────────────────────────────────────────────────────────────
            // GATE 1: Fast brace/bracket syntax check
            // ──────────────────────────────────────────────────────────────────
            logStreamService.broadcast("[INFO] Gate 1 — Syntax check (brace/bracket balance)...");
            ValidationResult syntaxResult = codeValidatorService.validateGeneratedCode(
                    currentChanges, System.getProperty("user.dir"));

            if (!syntaxResult.isSuccess()) {
                String errorMsg = String.join("; ", syntaxResult.getErrors());
                logStreamService.broadcast("[ERROR] Gate 1 FAILED on attempt " + attempt + ": " + errorMsg);

                attemptsList.add(AttemptItem.builder()
                        .id(attempt)
                        .status("SyntaxFailed")
                        .reason("Syntax check: " + errorMsg)
                        .build());

                if (attempt == maxAttempts) {
                    logStreamService.broadcast("[ERROR] Maximum self-healing attempts reached (syntax gate).");
                    break;
                }

                logStreamService.broadcast("[INFO] Recovery Agent re-prompting LLM with syntax errors...");
                currentChanges = attemptHealing(currentChanges, syntaxResult.getErrors(), preferredModel, "syntax");
                continue;
            }
            logStreamService.broadcast("[INFO] Gate 1 PASSED ✓");

            // ──────────────────────────────────────────────────────────────────
            // GATE 2: Real compiler validation (Compiler-in-the-Loop)
            // ──────────────────────────────────────────────────────────────────
            logStreamService.broadcast("[INFO] Gate 2 — Compiler sandbox (running real compiler)...");
            validationMode = "compiler";

            CompilationResult compResult = codeValidatorService.getCompilationResult(
                    currentChanges, System.getProperty("user.dir"));

            lastCompilerOutput = compResult.getRawOutput();
            long ms = compResult.getDurationMs();
            String projType = compResult.getProjectType();

            if (!compResult.isPassed()) {
                logStreamService.broadcast("[ERROR] Gate 2 FAILED on attempt " + attempt
                        + " (" + projType + ", exit=" + compResult.getExitCode() + ", " + ms + "ms)");

                // Log each parsed compiler error line to the dashboard
                if (compResult.getCompilerErrors() != null && !compResult.getCompilerErrors().isEmpty()) {
                    logStreamService.broadcast("[ERROR] Compiler errors detected:");
                    compResult.getCompilerErrors().forEach(err ->
                            logStreamService.broadcast("        ↳ " + err));
                } else {
                    logStreamService.broadcast("[ERROR] Raw compiler output (snippet):");
                    logStreamService.broadcast("        " + truncate(lastCompilerOutput, 300));
                }

                attemptsList.add(AttemptItem.builder()
                        .id(attempt)
                        .status("CompilerFailed")
                        .reason("Compiler (" + projType + ") exit=" + compResult.getExitCode()
                                + ": " + String.join("; ", compResult.getCompilerErrors()))
                        .build());

                if (attempt == maxAttempts) {
                    logStreamService.broadcast("[ERROR] Maximum self-healing attempts reached (compiler gate).");
                    break;
                }

                // Feed REAL compiler errors into LLM
                logStreamService.broadcast("[INFO] Recovery Agent feeding compiler errors to LLM for self-healing...");
                List<String> compilerErrors = compResult.getCompilerErrors().isEmpty()
                        ? List.of(truncate(lastCompilerOutput, 800))
                        : compResult.getCompilerErrors();
                currentChanges = attemptHealing(currentChanges, compilerErrors, preferredModel, "compiler");
                continue;
            }

            logStreamService.broadcast("[INFO] Gate 2 PASSED ✓ (" + projType + " compiled in " + ms + "ms)");

            // ──────────────────────────────────────────────────────────────────
            // GATE 3: Reviewer Agent quality/security review
            // ──────────────────────────────────────────────────────────────────
            logStreamService.broadcast("[INFO] Gate 3 — Reviewer Agent quality & security review...");
            ReviewerReport reviewerReport = reviewerAgent.reviewPatch(currentChanges, preferredModel);
            logStreamService.broadcast("[INFO] Reviewer decision on attempt " + attempt
                    + ": " + reviewerReport.getRecommendation());

            result.reviewerReport = reviewerReport;

            if ("Approve".equalsIgnoreCase(reviewerReport.getRecommendation())) {
                logStreamService.broadcast("[SUCCESS] ✓ All 3 gates passed on attempt " + attempt
                        + " — code is ready for PR creation.");
                attemptsList.add(AttemptItem.builder()
                        .id(attempt)
                        .status("Success")
                        .reason("All validation gates passed: syntax ✓, compiler ✓, reviewer approved ✓")
                        .build());
                isSuccess = true;
                break;
            }

            // Reviewer rejected — heal with reviewer feedback
            String reviewFeedback = "Security: " + reviewerReport.getSecurity()
                    + ", Performance: " + reviewerReport.getPerformance()
                    + ", Maintainability: " + reviewerReport.getMaintainability();
            logStreamService.broadcast("[WARNING] Gate 3 FAILED on attempt " + attempt
                    + " — Reviewer requested changes: " + reviewFeedback);

            attemptsList.add(AttemptItem.builder()
                    .id(attempt)
                    .status("ReviewFailed")
                    .reason("Reviewer requested changes: " + reviewerReport.getRecommendation()
                            + " (" + reviewFeedback + ")")
                    .build());

            if (attempt == maxAttempts) {
                logStreamService.broadcast("[ERROR] Maximum self-healing attempts reached (reviewer gate).");
                break;
            }

            logStreamService.broadcast("[INFO] Recovery Agent re-prompting LLM with reviewer feedback...");
            currentChanges = attemptHealing(
                    currentChanges,
                    List.of("Reviewer Feedback: " + reviewFeedback),
                    preferredModel,
                    "reviewer");
        }

        logStreamService.broadcast("[INFO] ─────────────────────────────────────────────");

        result.finalChanges = currentChanges;
        result.success = isSuccess;

        String firstFailReason = !attemptsList.isEmpty() && !"Success".equals(attemptsList.get(0).getStatus())
                ? attemptsList.get(0).getReason()
                : "None";

        result.report = RecoveryReport.builder()
                .example(firstFailReason)
                .strategy("Compiler-in-the-Loop: Syntax → Compiler → Reviewer (3-tier self-healing)")
                .attempts(attemptsList)
                .validationMode(validationMode)
                .compilerOutput(truncate(lastCompilerOutput, 2000))
                .totalAttemptsUsed(attemptsList.size())
                .build();

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM re-prompt with contextual error type (syntax / compiler / reviewer)
    // ─────────────────────────────────────────────────────────────────────────

    private List<FileChange> attemptHealing(List<FileChange> brokenChanges,
                                             List<String> errors,
                                             String preferredModel,
                                             String errorType) {
        logStreamService.broadcast("[INFO] Recovery Agent executing healing strategy for error type: " + errorType);

        String filesContent = brokenChanges.stream()
                .map(fc -> "File: " + fc.getPath() + "\nCode:\n" + fc.getContent())
                .reduce("", (a, b) -> a + "\n" + b);

        String errorsStr = String.join("\n", errors);
        String prompt = buildHealingPrompt(errorType, errorsStr, filesContent);

        try {
            String healedCode = fallbackAiService.generateCode(prompt, preferredModel);
            List<FileChange> healedChanges = githubService.parseAICodeOutput(healedCode);
            if (!healedChanges.isEmpty()) {
                logStreamService.broadcast("[INFO] Recovery Agent generated new patch (" + healedChanges.size() + " file(s)).");
                return healedChanges;
            }
            logStreamService.broadcast("[WARNING] Recovery Agent got empty patch from LLM — keeping previous code.");
        } catch (Exception e) {
            logStreamService.broadcast("[ERROR] Recovery Agent LLM re-prompt failed: " + e.getMessage());
        }

        return brokenChanges;
    }

    /**
     * Builds a targeted healing prompt based on the type of failure.
     * Giving the LLM specific, contextual instructions produces much better patches.
     */
    private String buildHealingPrompt(String errorType, String errorsStr, String filesContent) {
        String header;
        switch (errorType) {
            case "compiler":
                header = "You are the Recovery Agent for Automata — a Compiler-in-the-Loop self-healing system.\n"
                        + "The following Java/TypeScript file(s) FAILED to compile.\n\n"
                        + "== COMPILER ERRORS ==\n" + errorsStr + "\n\n"
                        + "These are REAL errors from the Java compiler (javac/mvn) or TypeScript compiler (tsc).\n"
                        + "Fix every compiler error listed above. Pay close attention to:\n"
                        + "  - Missing semicolons, unclosed braces, or brackets\n"
                        + "  - Missing return statements or wrong return types\n"
                        + "  - Undefined variables or incorrect method signatures\n"
                        + "  - Import statements that are missing or incorrect\n\n";
                break;
            case "reviewer":
                header = "You are the Recovery Agent for Automata.\n"
                        + "The previous code fix was rejected during automated code review.\n\n"
                        + "== REVIEW FEEDBACK ==\n" + errorsStr + "\n\n"
                        + "Address ALL reviewer concerns. Focus on:\n"
                        + "  - Security: no SQL injection, input validation, no hardcoded secrets\n"
                        + "  - Performance: avoid N+1 queries, unnecessary loops, or blocking calls\n"
                        + "  - Maintainability: clear naming, add Javadoc, follow existing patterns\n\n";
                break;
            default: // "syntax"
                header = "You are the Recovery Agent for Automata.\n"
                        + "The following code has SYNTAX ERRORS (unmatched braces/brackets).\n\n"
                        + "== SYNTAX ERRORS ==\n" + errorsStr + "\n\n"
                        + "Fix all unmatched braces {}, parentheses (), and brackets [].\n\n";
                break;
        }

        return header
                + "== BROKEN CODE ==\n" + filesContent + "\n\n"
                + "Requirements:\n"
                + "- Maintain the original file paths exactly.\n"
                + "- Do NOT change public method signatures or class names.\n"
                + "- Output ONLY corrected code (wrap each file in ```lang:path blocks).\n"
                + "- No markdown prose, no explanations — code only.\n\n"
                + "Corrected code:";
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "... [truncated]";
    }
}
