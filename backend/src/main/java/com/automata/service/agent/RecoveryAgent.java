package com.automata.service.agent;

import com.automata.model.AgentReports.AttemptItem;
import com.automata.model.AgentReports.RecoveryReport;
import com.automata.model.AgentReports.ReviewerReport;
import com.automata.model.FileChange;
import com.automata.model.ValidationResult;
import com.automata.service.CodeValidatorService;
import com.automata.service.GitHubService;
import com.automata.service.ai.FallbackAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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

    public static class RecoveryResult {
        public List<FileChange> finalChanges;
        public RecoveryReport report;
        public ReviewerReport reviewerReport;
        public boolean success;
    }

    public RecoveryResult runRecoveryLoop(List<FileChange> initialChanges, String preferredModel, boolean injectDemoError) {
        log.info("[INFO] Recovery Agent standing by for code validation...");
        
        RecoveryResult result = new RecoveryResult();
        List<AttemptItem> attemptsList = new ArrayList<>();
        List<FileChange> currentChanges = new ArrayList<>(initialChanges);

        int maxAttempts = 3;
        boolean isSuccess = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info("[INFO] Validation Attempt {}/{}", attempt, maxAttempts);

            // Inject a demonstration syntax mismatch (e.g. open brackets mismatch) on Attempt 1 if requested
            if (attempt == 1 && injectDemoError && !currentChanges.isEmpty()) {
                log.info("[DEMO] Injecting syntax error (unmatched bracket) to trigger Recovery Agent self-healing...");
                FileChange broken = currentChanges.get(0);
                currentChanges.set(0, FileChange.builder()
                        .path(broken.getPath())
                        .content(broken.getContent() + "\n// Demo Syntax Mismatch\nfunction broken() {") // Added open brace
                        .build());
            }

            ValidationResult validationResult = codeValidatorService.validateGeneratedCode(currentChanges, System.getProperty("user.dir"));
            
            if (validationResult.isSuccess()) {
                // Run Reviewer Agent check!
                ReviewerReport reviewerReport = reviewerAgent.reviewPatch(currentChanges, preferredModel);
                log.info("[INFO] Reviewer Agent recommendation on Attempt {}: {}", attempt, reviewerReport.getRecommendation());
                
                result.reviewerReport = reviewerReport;

                if ("Approve".equalsIgnoreCase(reviewerReport.getRecommendation())) {
                    log.info("[SUCCESS] Code validation and code review passed on Attempt {}", attempt);
                    attemptsList.add(AttemptItem.builder()
                            .id(attempt)
                            .status("Success")
                            .reason("All validation and syntax checks passed successfully and Reviewer approved")
                            .build());
                    isSuccess = true;
                    break;
                } else {
                    String reviewFeedback = "Reviewer Feedback: Security: " + reviewerReport.getSecurity() + 
                                            ", Performance: " + reviewerReport.getPerformance() + 
                                            ", Maintainability: " + reviewerReport.getMaintainability();
                    log.warn("[WARNING] Reviewer requested changes on Attempt {}: {}", attempt, reviewFeedback);
                    
                    attemptsList.add(AttemptItem.builder()
                            .id(attempt)
                            .status("Failed")
                            .reason("Reviewer requested changes: " + reviewerReport.getRecommendation())
                            .build());

                    if (attempt == maxAttempts) {
                        log.error("[ERROR] Maximum self-healing recovery attempts reached. Code review rejected.");
                        break;
                    }

                    // Call Recovery Agent LLM healing logic with the reviewer feedback
                    currentChanges = attemptHealing(currentChanges, List.of("Reviewer Feedback: " + reviewFeedback), preferredModel);
                }
            } else {
                String errorMsg = String.join(", ", validationResult.getErrors());
                log.warn("[WARNING] Validation failed on Attempt {}: {}", attempt, errorMsg);
                
                attemptsList.add(AttemptItem.builder()
                        .id(attempt)
                        .status("Failed")
                        .reason("Validation failed: " + errorMsg)
                        .build());

                if (attempt == maxAttempts) {
                    log.error("[ERROR] Maximum self-healing recovery attempts reached.");
                    break;
                }

                // Call Recovery Agent LLM healing logic
                currentChanges = attemptHealing(currentChanges, validationResult.getErrors(), preferredModel);
            }
        }

        result.finalChanges = currentChanges;
        result.success = isSuccess;
        result.report = RecoveryReport.builder()
                .example(attemptsList.get(0).getStatus().equals("Failed") ? attemptsList.get(0).getReason() : "None")
                .strategy("Generate Alternative Fix (Syntax & Review Healing)")
                .attempts(attemptsList)
                .build();

        return result;
    }

    private List<FileChange> attemptHealing(List<FileChange> brokenChanges, List<String> errors, String preferredModel) {
        log.info("[INFO] Recovery Agent executing strategy: Re-prompting AI with feedback...");

        String filesContent = brokenChanges.stream()
                .map(fc -> "File: " + fc.getPath() + "\nCode:\n" + fc.getContent())
                .reduce("", (a, b) -> a + "\n" + b);

        String errorsStr = String.join("\n", errors);

        boolean isReviewError = errors.stream().anyMatch(e -> e.contains("Reviewer Feedback:"));
        String promptHeader;
        if (isReviewError) {
            promptHeader = "You are the Recovery Agent for Automata. The previous code fix was rejected during code review.\n" +
                    "Review feedback and requested changes:\n" + errorsStr + "\n\n";
        } else {
            promptHeader = "You are the Recovery Agent for Automata. The previous code fix generated failed syntax validation.\n" +
                    "Errors reported:\n" + errorsStr + "\n\n";
        }

        String prompt = promptHeader +
                "Broken/Rejected Code:\n" + filesContent + "\n\n" +
                "Please inspect the code and the feedback. Generate a corrected version of the code that resolves all reported issues.\n" +
                "Requirements:\n" +
                "- Maintain the original file paths.\n" +
                "- Do not introduce new formatting or syntax errors.\n" +
                "- Output ONLY corrected code (surround each file change with ```lang:path blocks).\n" +
                "- No markdown explanations or other text.\n\n" +
                "Corrected code:";

        try {
            String healedCode = fallbackAiService.generateCode(prompt, preferredModel);
            List<FileChange> healedChanges = githubService.parseAICodeOutput(healedCode);
            if (!healedChanges.isEmpty()) {
                log.info("[INFO] Recovery Agent successfully generated new code patch.");
                return healedChanges;
            }
        } catch (Exception e) {
            log.error("[ERROR] Recovery Agent failed during LLM re-prompt: {}", e.getMessage());
        }

        return brokenChanges;
    }
}
