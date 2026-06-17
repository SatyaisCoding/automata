package com.automata.service;

import com.automata.model.*;
import com.automata.service.ai.FallbackAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class JiraWebhookService {

    @Autowired
    private ErrorExtractor errorExtractor;

    @Autowired
    private CodeContextService codeContextService;

    @Autowired
    private FallbackAiService fallbackAiService;

    @Autowired
    private SafetyGuardService safetyGuardService;

    @Autowired
    private CodeValidatorService codeValidatorService;

    @Autowired
    private GitHubService githubService;

    @Autowired
    private AuditLogService auditLogService;

    public Map<String, Object> processWebhook(JiraTicket ticket, String preferredModel) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 1. Audit Log: Jira ticket received
            log.info("[INFO] === Jira Webhook Received ===");
            log.info("[INFO] Issue Key: {}", ticket.getKey());
            log.info("[INFO] Summary: {}", ticket.getSummary());
            log.info("[INFO] Description: {}", ticket.getDescription());
            log.info("[INFO] Priority: {}", ticket.getPriority() != null ? ticket.getPriority() : "Not set");
            log.info("[INFO] ============================");

            auditLogService.logAuditEvent(ticket.getKey(), "jira_ticket_received", "success", Map.of(
                    "summary", ticket.getSummary(),
                    "priority", ticket.getPriority() != null ? ticket.getPriority() : "not_specified"
            ));

            // 2. Extract Error Info
            ExtractedErrorInfo errorInfo = errorExtractor.extractErrorInfo(ticket.getDescription());
            boolean hasErrorInfo = errorInfo.getErrorMessage() != null || errorInfo.getStackTrace() != null;
            log.info("[INFO] Extracted error info: hasErrorMessage={}, hasStackTrace={}", 
                    errorInfo.getErrorMessage() != null, errorInfo.getStackTrace() != null);

            // 3. Fetch Code Context
            log.info("[INFO] Fetching code context from repository...");
            List<CodeContextService.CodeContext> codeContexts = codeContextService.getCodeContext(ticket);
            log.info("[INFO] Retrieved {} relevant file(s) from repository", codeContexts.size());
            auditLogService.logAuditEvent(ticket.getKey(), "code_context_fetched", "success", Map.of(
                    "fileCount", codeContexts.size()
            ));

            // 4. Build Prompt & Invoke AI
            String prompt = buildPrompt(ticket, codeContexts, errorInfo);
            auditLogService.logAuditEvent(ticket.getKey(), "prompt_sent_to_ai", "success", Map.of(
                    "promptHash", auditLogService.hashData(prompt),
                    "promptLength", prompt.length()
            ));

            String generatedCode = fallbackAiService.generateCode(prompt, preferredModel);
            auditLogService.logAuditEvent(ticket.getKey(), "ai_output_received", "success", Map.of(
                    "outputHash", auditLogService.hashData(generatedCode),
                    "outputLength", generatedCode.length()
            ));

            log.info("[INFO] === AI Generated Code ===");
            log.info(generatedCode);
            log.info("[INFO] =========================");

            // 5. Parse Changes and Apply Safety Guards
            List<FileChange> fileChanges = githubService.parseAICodeOutput(generatedCode);
            log.info("[INFO] Parsed {} file change(s) from AI output", fileChanges.size());

            try {
                safetyGuardService.guardFileChanges(fileChanges);
                log.info("[INFO] Safety guards passed");
            } catch (Exception e) {
                log.error("[ERROR] Safety guard validation failed: {}", e.getMessage());
                auditLogService.logAuditEvent(ticket.getKey(), "safety_guard_blocked", "failed", Map.of(
                        "reason", e.getMessage()
                ));
                result.put("status", "failed");
                result.put("error", "Safety guard validation failed: " + e.getMessage());
                return result;
            }

            // 6. Pre-PR Code Validation
            log.info("[INFO] === Validating Generated Code ===");
            try {
                ValidationResult validationResult = codeValidatorService.validateGeneratedCode(fileChanges, System.getProperty("user.dir"));
                if (!validationResult.isSuccess()) {
                    log.error("[ERROR] Code validation failed: {}", validationResult.getErrors());
                    auditLogService.logAuditEvent(ticket.getKey(), "commit_created", "failed", Map.of(
                            "reason", "Validation failed: " + String.join(", ", validationResult.getErrors())
                    ));
                    result.put("status", "failed");
                    result.put("error", "Code validation failed");
                    result.put("validationErrors", validationResult.getErrors());
                    return result;
                }
                if (!validationResult.getWarnings().isEmpty()) {
                    log.warn("[WARNING] Validation warnings: {}", validationResult.getWarnings());
                }
                log.info("[INFO] Code validation passed");
            } catch (Exception e) {
                log.warn("[WARNING] Validation system encountered an error: {}", e.getMessage());
            }

            // 7. Push Commit & Create Pull Request
            log.info("[INFO] === Creating Pull Request ===");
            String branchName = "automata/" + ticket.getKey() + "-ai-fix";
            
            String branchSha = githubService.createBranch(branchName);
            log.info("[INFO] Branch created with SHA: {}", branchSha);
            auditLogService.logAuditEvent(ticket.getKey(), "github_branch_created", "success", Map.of(
                    "branchName", branchName,
                    "sha", branchSha
            ));

            String commitSha = githubService.commitChanges(branchName, fileChanges, "Automata AI fix for " + ticket.getKey());
            log.info("[INFO] Changes committed successfully, commit SHA: {}", commitSha);
            auditLogService.logAuditEvent(ticket.getKey(), "commit_created", "success", Map.of(
                    "commitSha", commitSha,
                    "fileCount", fileChanges.size()
            ));

            Map<String, Object> prResult = githubService.createPullRequest(branchName, ticket, fileChanges, null);
            String prUrl = (String) prResult.get("prUrl");
            int prNumber = (Integer) prResult.get("prNumber");
            log.info("[INFO] Pull Request created (draft): {}", prUrl);
            auditLogService.logAuditEvent(ticket.getKey(), "pull_request_created", "success", Map.of(
                    "prUrl", prUrl,
                    "prNumber", prNumber
            ));

            result.put("status", "ai_generated");
            result.put("pr_url", prUrl);
            result.put("pr_number", prNumber);
            result.put("error_info_extracted", hasErrorInfo);

            // 8. CI Status Tracking (Non-blocking but runs up to 60 seconds inline)
            log.info("[INFO] === Waiting for CI Checks ===");
            try {
                CICheckStatus ciResult = githubService.waitForCIChecks(prNumber, 60000, 10000); // 1 minute max, check every 10s
                
                if ("success".equals(ciResult.getStatus())) {
                    log.info("[INFO] CI checks passed");
                    githubService.markPRReadyForReview(prNumber);
                    githubService.addPRComment(prNumber, "[SUCCESS] **Automata CI Check**: All checks passed. PR is ready for review.");
                    result.put("ci_status", "success");
                } else if ("failure".equals(ciResult.getStatus())) {
                    log.warn("[WARNING] CI checks failed");
                    StringBuilder commentBuilder = new StringBuilder();
                    commentBuilder.append("[FAILURE] **Automata CI Check**: Some checks failed.\n\nChecks:\n");
                    if (ciResult.getChecks() != null) {
                        for (CICheckStatus.CheckRun check : ciResult.getChecks()) {
                            commentBuilder.append("- ").append(check.getName()).append(": ").append(check.getConclusion() != null ? check.getConclusion() : check.getStatus()).append("\n");
                        }
                    } else {
                        commentBuilder.append("No check details available\n");
                    }
                    commentBuilder.append("\nPlease review the failures before merging.");
                    githubService.addPRComment(prNumber, commentBuilder.toString());
                    result.put("ci_status", "failure");
                } else {
                    log.info("[INFO] CI checks still pending");
                    githubService.addPRComment(prNumber, "[PENDING] **Automata CI Check**: Checks are still running. This PR will remain in draft until checks complete.\n\nThe PR will be automatically marked as ready for review once all checks pass.");
                    result.put("ci_status", "pending");
                }
            } catch (Exception ciError) {
                log.error("[ERROR] Error checking CI status: {}", ciError.getMessage());
                result.put("ci_status", "error");
            }

        } catch (Exception e) {
            log.error("[ERROR] Unexpected error processing webhook: {}", e.getMessage(), e);
            auditLogService.logAuditEvent(ticket.getKey(), "pull_request_created", "failed", Map.of(
                    "error", e.getMessage()
            ));
            result.put("status", "failed");
            result.put("error", e.getMessage());
        }

        return result;
    }

    private String buildPrompt(JiraTicket ticket, List<CodeContextService.CodeContext> codeContexts, ExtractedErrorInfo errorInfo) {
        String priorityText = ticket.getPriority() != null ? "Priority: " + ticket.getPriority() : "Priority: Not specified";

        StringBuilder contextSection = new StringBuilder();
        if (!codeContexts.isEmpty()) {
            contextSection.append("\n---\nRelevant Code Context:\n\n");
            for (CodeContextService.CodeContext context : codeContexts) {
                contextSection.append("File: ").append(context.getFilename()).append("\n").append(context.getContent()).append("\n\n");
            }
            contextSection.append("---\n");
        }

        String errorSection = errorExtractor.formatErrorInfoForPrompt(errorInfo);

        return "You are a senior full-stack engineer. A bug has been reported in Jira.\n\n" +
                "Issue Key: " + ticket.getKey() + "\n" +
                "Summary: " + ticket.getSummary() + "\n" +
                priorityText + "\n\n" +
                "Description:\n" +
                ticket.getDescription() + "\n" +
                errorSection + contextSection.toString() + "\n" +
                "Please provide a FIX in code for this bug. \n\n" +
                "Requirements:\n" +
                "- Do not change public APIs\n" +
                "- Output only code (no explanations)\n" +
                "- Provide a complete, production-ready solution\n" +
                "- Use the provided code context to understand the codebase structure\n" +
                "- Address the specific error information provided above\n\n" +
                "Code fix:";
    }
}
