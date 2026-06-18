package com.automata.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.automata.model.JiraTicket;
import com.automata.service.JiraWebhookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/webhook/jira")
public class JiraWebhookController {

    @Autowired
    private JiraWebhookService jiraWebhookService;

    @Value("${automata.webhook.jira-secret:}")
    private String jiraSecret;

    @PostMapping
    public ResponseEntity<Map<String, Object>> handleJiraWebhook(
            @RequestBody JsonNode payload,
            @RequestParam(value = "secret", required = false) String secretParam,
            @RequestParam(value = "model", required = false) String modelParam) {

        try {
            // Verify Jira webhook secret if configured
            if (jiraSecret != null && !jiraSecret.trim().isEmpty()) {
                if (secretParam == null || !jiraSecret.equals(secretParam)) {
                    log.warn("[WARNING] Invalid Jira webhook secret received.");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid webhook secret"));
                }
            }

            JsonNode issue = payload.path("issue");
            if (issue.isMissingNode()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid webhook payload structure: missing 'issue'"));
            }

            JsonNode fields = issue.path("fields");
            if (fields.isMissingNode() || fields.path("summary").isMissingNode() || fields.path("description").isMissingNode()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing required fields: 'summary' or 'description'"));
            }

            JiraTicket ticket = JiraTicket.builder()
                    .id(issue.path("id").asText(""))
                    .key(issue.path("key").asText())
                    .summary(fields.path("summary").asText())
                    .description(fields.path("description").asText())
                    .priority(fields.path("priority").path("name").asText("Not specified"))
                    .build();

            // Determine preferred model (prioritizing URL param, then body payload)
            String preferredModel = modelParam;
            if (preferredModel == null && payload.has("model")) {
                preferredModel = payload.path("model").asText();
            }

            Map<String, Object> result = jiraWebhookService.processWebhook(ticket, preferredModel);
            
            if ("failed".equals(result.get("status"))) {
                return ResponseEntity.internalServerError().body(result);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[ERROR] Failed to process webhook: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }
}
