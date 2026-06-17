package com.automata.service.agent;

import com.automata.model.AgentReports.AttemptItem;
import com.automata.model.AgentReports.RecoveryReport;
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

    public static class RecoveryResult {
        public List<FileChange> finalChanges;
        public RecoveryReport report;
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
                log.info("[SUCCESS] Code validation passed on Attempt {}", attempt);
                attemptsList.add(AttemptItem.builder()
                        .id(attempt)
                        .status("Success")
                        .reason("All validation and syntax checks passed successfully")
                        .build());
                isSuccess = true;
                break;
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
                .strategy("Generate Alternative Fix (Syntax Healing)")
                .attempts(attemptsList)
                .build();

        return result;
    }

    private List<FileChange> attemptHealing(List<FileChange> brokenChanges, List<String> errors, String preferredModel) {
        log.info("[INFO] Recovery Agent executing strategy: Re-prompting AI with syntax error feedback...");

        String filesContent = brokenChanges.stream()
                .map(fc -> "File: " + fc.getPath() + "\nCode:\n" + fc.getContent())
                .reduce("", (a, b) -> a + "\n" + b);

        String errorsStr = String.join("\n", errors);

        String prompt = "You are the Recovery Agent for Automata. The previous code fix generated failed syntax validation.\n" +
                "Errors reported:\n" + errorsStr + "\n\n" +
                "Broken Code:\n" + filesContent + "\n\n" +
                "Please inspect the broken code and the error reports. Generate a corrected version of the code that resolves all compilation and syntax errors.\n" +
                "Requirements:\n" +
                "- Maintain the original file paths.\n" +
                "- Do not introduce new formatting errors.\n" +
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
