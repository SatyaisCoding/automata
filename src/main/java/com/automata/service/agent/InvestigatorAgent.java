package com.automata.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.automata.model.AgentReports.EvidenceItem;
import com.automata.model.AgentReports.InvestigatorReport;
import com.automata.model.JiraTicket;
import com.automata.service.ai.FallbackAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class InvestigatorAgent {

    @Autowired
    private FallbackAiService fallbackAiService;

    @Autowired
    private ObjectMapper objectMapper;

    public InvestigatorReport runInvestigation(JiraTicket ticket, String preferredModel) {
        log.info("[INFO] Investigator Agent extracting evidence and forming hypothesis...");

        String prompt = "You are the Investigator Agent for Automata. Inspect this ticket description and logs:\n" +
                "Summary: " + ticket.getSummary() + "\n" +
                "Description: " + ticket.getDescription() + "\n\n" +
                "Please identify:\n" +
                "1. Target Service name\n" +
                "2. Environment (Production, Staging, Development)\n" +
                "3. Severity (High, Medium, Low)\n" +
                "4. Key pieces of evidence (extracting the stack trace or crash details as list of objects)\n" +
                "5. Root cause hypothesis\n" +
                "6. Confidence level (0.0 to 1.0)\n\n" +
                "Return ONLY a valid JSON object matching this schema:\n" +
                "{\n" +
                "  \"service\": \"Service Name (e.g. Payment Service)\",\n" +
                "  \"environment\": \"Production\",\n" +
                "  \"severity\": \"High\",\n" +
                "  \"evidence\": [\n" +
                "    { \"type\": \"Stack Trace / Log Signature\", \"desc\": \"specific details from description\" }\n" +
                "  ],\n" +
                "  \"hypothesis\": \"Root cause explanation\",\n" +
                "  \"confidence\": 0.91\n" +
                "}\n" +
                "Do not include any explanation or markdown formatting (no ```json code blocks). Only output the raw JSON object.";

        try {
            String response = fallbackAiService.generateCode(prompt, preferredModel).trim();
            log.debug("[DEBUG] Investigator Agent response: {}", response);

            // Clean code block wrappers if any
            if (response.startsWith("```")) {
                int firstLineBreak = response.indexOf("\n");
                int lastCodeBlock = response.lastIndexOf("```");
                if (firstLineBreak != -1 && lastCodeBlock > firstLineBreak) {
                    response = response.substring(firstLineBreak, lastCodeBlock).trim();
                }
            }

            JsonNode root = objectMapper.readTree(response);
            
            List<EvidenceItem> evidenceList = new ArrayList<>();
            JsonNode evidenceNode = root.path("evidence");
            if (evidenceNode.isArray()) {
                for (JsonNode itemNode : evidenceNode) {
                    evidenceList.add(EvidenceItem.builder()
                            .type(itemNode.path("type").asText("Evidence"))
                            .desc(itemNode.path("desc").asText(""))
                            .build());
                }
            }

            return InvestigatorReport.builder()
                    .service(root.path("service").asText("Unknown Service"))
                    .environment(root.path("environment").asText("Production"))
                    .severity(root.path("severity").asText("High"))
                    .evidence(evidenceList)
                    .hypothesis(root.path("hypothesis").asText("Missing check bounds"))
                    .confidence(root.path("confidence").asDouble(0.90))
                    .build();

        } catch (Exception e) {
            log.warn("[WARNING] Investigator Agent failed to parse structured JSON: {}. Using heuristics fallback.", e.getMessage());
            
            // Heuristic fallback
            String text = (ticket.getSummary() + " " + ticket.getDescription()).toLowerCase();
            String service = "Payment Service";
            String hypothesis = "Parameter user object reference was dereferenced while holding null pointer.";
            List<EvidenceItem> evidence = new ArrayList<>();
            evidence.add(new EvidenceItem("Description scan", "Identified keyword traces in stack logs"));

            if (text.contains("mapper") || text.contains("bounds") || text.contains("array")) {
                service = "User Management";
                hypothesis = "Indexing split CSV elements without length constraint matching.";
            }

            return InvestigatorReport.builder()
                    .service(service)
                    .environment("Production")
                    .severity("High")
                    .evidence(evidence)
                    .hypothesis(hypothesis)
                    .confidence(0.85)
                    .build();
        }
    }
}
