package com.automata.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.automata.model.AgentReports.PlannerReport;
import com.automata.model.JiraTicket;
import com.automata.service.ai.FallbackAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class PlannerAgent {

    @Autowired
    private FallbackAiService fallbackAiService;

    @Autowired
    private ObjectMapper objectMapper;

    public PlannerReport generatePlan(JiraTicket ticket, String preferredModel) {
        log.info("[INFO] Planner Agent formulating execution strategy...");

        String prompt = "You are the Planner Agent for Automata. Analyze this incident ticket:\n" +
                "Key: " + ticket.getKey() + "\n" +
                "Summary: " + ticket.getSummary() + "\n" +
                "Description: " + ticket.getDescription() + "\n\n" +
                "Please categorize the issue, estimate confidence (0.0 to 1.0), and outline the repair strategy.\n" +
                "Return ONLY a valid JSON object matching this schema:\n" +
                "{\n" +
                "  \"issueType\": \"classified issue type (e.g. NullPointerException, ArrayIndexOutOfBoundsException)\",\n" +
                "  \"confidence\": 0.92,\n" +
                "  \"repairStrategy\": \"summary of the fix approach\",\n" +
                "  \"affectedModules\": [\"name of target module or file\"]\n" +
                "}\n" +
                "Do not include any explanation or markdown formatting (no ```json code blocks). Only output the raw JSON object.";

        try {
            String response = fallbackAiService.generateCode(prompt, preferredModel).trim();
            log.debug("[DEBUG] Planner Agent response: {}", response);

            // Clean code block wrappers if any
            if (response.startsWith("```")) {
                int firstLineBreak = response.indexOf("\n");
                int lastCodeBlock = response.lastIndexOf("```");
                if (firstLineBreak != -1 && lastCodeBlock > firstLineBreak) {
                    response = response.substring(firstLineBreak, lastCodeBlock).trim();
                }
            }

            JsonNode root = objectMapper.readTree(response);
            return PlannerReport.builder()
                    .issueType(root.path("issueType").asText("Unclassified Bug"))
                    .confidence(root.path("confidence").asDouble(0.85))
                    .repairStrategy(root.path("repairStrategy").asText("Examine code context and fix details"))
                    .affectedModules(objectMapper.convertValue(root.path("affectedModules"), List.class))
                    .build();

        } catch (Exception e) {
            log.warn("[WARNING] Planner Agent failed to parse structured JSON: {}. Using heuristics fallback.", e.getMessage());
            
            // Simple heuristic mapping
            String text = (ticket.getSummary() + " " + ticket.getDescription()).toLowerCase();
            String issue = "NullPointerException";
            String strategy = "Add parameter null validations.";
            String targetFile = "lib/fix.ts";

            if (text.contains("bounds") || text.contains("array") || text.contains("index")) {
                issue = "ArrayIndexOutOfBoundsException";
                strategy = "Add index boundaries validation.";
            }

            return PlannerReport.builder()
                    .issueType(issue)
                    .confidence(0.88)
                    .repairStrategy(strategy)
                    .affectedModules(List.of(targetFile))
                    .build();
        }
    }
}
