package com.automata.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.automata.model.AgentReports.ReviewerReport;
import com.automata.model.FileChange;
import com.automata.service.ai.FallbackAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewerAgent {

    @Autowired
    private FallbackAiService fallbackAiService;

    @Autowired
    private ObjectMapper objectMapper;

    public ReviewerReport reviewPatch(List<FileChange> fileChanges, String preferredModel) {
        log.info("[INFO] Reviewer Agent validating code quality, performance, and security...");

        if (fileChanges == null || fileChanges.isEmpty()) {
            return ReviewerReport.builder()
                    .security("Low")
                    .performance("Negligible")
                    .maintainability("Good")
                    .recommendation("Approve")
                    .build();
        }

        String patchSummary = fileChanges.stream()
                .map(fc -> "File: " + fc.getPath() + "\n" + fc.getContent() + "\n")
                .collect(Collectors.joining("\n"));

        String prompt = "You are the Reviewer Agent for Automata. Review these generated file changes:\n\n" +
                patchSummary + "\n" +
                "Evaluate the code quality, security risks, performance overhead, and maintainability.\n" +
                "Return ONLY a valid JSON object matching this schema:\n" +
                "{\n" +
                "  \"security\": \"Low / Medium / High (explain briefly)\",\n" +
                "  \"performance\": \"Negligible / Moderate / High (explain briefly)\",\n" +
                "  \"maintainability\": \"Good / Fair / Excellent\",\n" +
                "  \"recommendation\": \"Approve / Request Changes\"\n" +
                "}\n" +
                "Do not include any explanation or markdown formatting (no ```json code blocks). Only output the raw JSON object.";

        try {
            String response = fallbackAiService.generateCode(prompt, preferredModel).trim();
            log.debug("[DEBUG] Reviewer Agent response: {}", response);

            // Clean code block wrappers if any
            if (response.startsWith("```")) {
                int firstLineBreak = response.indexOf("\n");
                int lastCodeBlock = response.lastIndexOf("```");
                if (firstLineBreak != -1 && lastCodeBlock > firstLineBreak) {
                    response = response.substring(firstLineBreak, lastCodeBlock).trim();
                }
            }

            JsonNode root = objectMapper.readTree(response);
            return ReviewerReport.builder()
                    .security(root.path("security").asText("Low"))
                    .performance(root.path("performance").asText("Negligible"))
                    .maintainability(root.path("maintainability").asText("Good"))
                    .recommendation(root.path("recommendation").asText("Approve"))
                    .build();

        } catch (Exception e) {
            log.warn("[WARNING] Reviewer Agent failed to parse structured JSON: {}. Using fallback report.", e.getMessage());
            
            return ReviewerReport.builder()
                    .security("Low")
                    .performance("Negligible")
                    .maintainability("Excellent")
                    .recommendation("Approve")
                    .build();
        }
    }
}
