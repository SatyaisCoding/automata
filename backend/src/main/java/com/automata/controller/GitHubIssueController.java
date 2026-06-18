package com.automata.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.automata.model.JiraTicket;
import com.automata.service.JiraWebhookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/webhook/github")
public class GitHubIssueController {

    @Autowired
    private JiraWebhookService jiraWebhookService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${automata.webhook.github-secret:}")
    private String githubSecret;

    @Value("${automata.github-owner:}")
    private String githubOwner;

    @Value("${automata.github-repo:}")
    private String githubRepo;

    @PostMapping
    public ResponseEntity<Map<String, Object>> handleGitHubWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestParam(value = "model", required = false) String modelParam) {

        try {
            // Verify GitHub webhook signature if a secret is configured
            if (githubSecret != null && !githubSecret.trim().isEmpty()) {
                if (signatureHeader == null || !verifySignature(rawPayload, signatureHeader, githubSecret)) {
                    log.warn("[WARNING] Invalid GitHub webhook signature received.");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid webhook signature"));
                }
            }

            JsonNode payload = objectMapper.readTree(rawPayload);

            // Validate that the event comes from the configured repository
            JsonNode repository = payload.path("repository");
            if (!repository.isMissingNode()) {
                String fullName = repository.path("full_name").asText("");
                String expectedFullName = githubOwner + "/" + githubRepo;
                if (githubOwner != null && !githubOwner.trim().isEmpty() && githubRepo != null && !githubRepo.trim().isEmpty()) {
                    if (!expectedFullName.equalsIgnoreCase(fullName)) {
                        log.warn("[WARNING] Ignoring webhook from unauthorized repository: '{}'. Expected: '{}'", fullName, expectedFullName);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "Unauthorized repository: " + fullName));
                    }
                }
            }

            // GitHub issue events contain an "action" field
            String action = payload.path("action").asText("");
            if (!"opened".equals(action)) {
                log.info("[INFO] Ignoring GitHub webhook event action: '{}'. Only processing 'opened' action.", action);
                return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Event action is not 'opened'"));
            }

            JsonNode issue = payload.path("issue");
            if (issue.isMissingNode()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid webhook payload structure: missing 'issue'"));
            }

            // Map GitHub payload to JiraTicket model
            // Title: issue.title
            // Description: issue.body
            // Key: GH-{number}
            // ID: issue.id
            JiraTicket ticket = JiraTicket.builder()
                    .id(issue.path("id").asText(""))
                    .key("GH-" + issue.path("number").asInt())
                    .summary(issue.path("title").asText(""))
                    .description(issue.path("body").asText(""))
                    .priority("Medium") // Default priority for GitHub issues
                    .build();

            log.info("[START] Triggered via GitHub webhook. Processing ticket: {}", ticket.getKey());

            // Determine preferred model
            String preferredModel = modelParam;

            Map<String, Object> result = jiraWebhookService.processWebhook(ticket, preferredModel);

            if ("failed".equals(result.get("status"))) {
                return ResponseEntity.internalServerError().body(result);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[ERROR] Failed to process GitHub webhook: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    private boolean verifySignature(String payload, String signatureHeader, String secret) {
        if (!signatureHeader.startsWith("sha256=")) {
            return false;
        }
        String expectedSignature = signatureHeader.substring(7);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : rawHmac) {
                hexString.append(String.format("%02x", b));
            }
            String computedSignature = hexString.toString();
            return MessageDigest.isEqual(computedSignature.getBytes(StandardCharsets.UTF_8), expectedSignature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[ERROR] Error verifying GitHub signature: {}", e.getMessage());
            return false;
        }
    }
}
