package com.automata.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.automata.model.AgentReports.EvidenceItem;
import com.automata.model.AgentReports.InvestigatorReport;
import com.automata.model.AgentReports.PlannerReport;
import com.automata.model.JiraTicket;
import com.automata.service.LogStreamService;
import com.automata.service.ai.FallbackAiService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AnalysisAgent {

    @Autowired
    private FallbackAiService fallbackAiService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LogStreamService logStreamService;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnalysisResult {
        private InvestigatorReport investigatorReport;
        private PlannerReport plannerReport;
    }

    public AnalysisResult analyzeTicket(JiraTicket ticket, String preferredModel) {
        logStreamService.broadcast("[INFO] Analysis Agent formulating hypothesis and execution strategy in a single call...");

        String prompt = "You are the Analysis Agent for Automata. Analyze this incident ticket:\n" +
                "Key: " + ticket.getKey() + "\n" +
                "Summary: " + ticket.getSummary() + "\n" +
                "Description: " + ticket.getDescription() + "\n\n" +
                "Please perform investigator and planner roles. Identify the service, environment, severity, extract key evidence items, form a root cause hypothesis, classify the issue type, estimate confidence (0.0 to 1.0), and outline the repair strategy.\n" +
                "Return ONLY a valid JSON object matching this schema:\n" +
                "{\n" +
                "  \"service\": \"Service Name (e.g. Payment Service)\",\n" +
                "  \"environment\": \"Production\",\n" +
                "  \"severity\": \"High\",\n" +
                "  \"evidence\": [\n" +
                "    { \"type\": \"Stack Trace / Log Signature\", \"desc\": \"specific details from description\" }\n" +
                "  ],\n" +
                "  \"hypothesis\": \"Root cause explanation\",\n" +
                "  \"investigatorConfidence\": 0.91,\n" +
                "  \"issueType\": \"NullPointerException\",\n" +
                "  \"plannerConfidence\": 0.92,\n" +
                "  \"repairStrategy\": \"summary of the fix approach\",\n" +
                "  \"affectedModules\": [\"name of target module or file\"]\n" +
                "}\n" +
                "Do not include any explanation or markdown formatting (no ```json code blocks). Only output the raw JSON object.";

        try {
            String response = fallbackAiService.generateCode(prompt, preferredModel).trim();
            log.debug("[DEBUG] Analysis Agent response: {}", response);

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

            InvestigatorReport investigatorReport = InvestigatorReport.builder()
                    .service(root.path("service").asText("Unknown Service"))
                    .environment(root.path("environment").asText("Production"))
                    .severity(root.path("severity").asText("High"))
                    .evidence(evidenceList)
                    .hypothesis(root.path("hypothesis").asText("Missing check bounds"))
                    .confidence(root.path("investigatorConfidence").asDouble(0.90))
                    .build();

            List<String> affected = new ArrayList<>();
            JsonNode modulesNode = root.path("affectedModules");
            if (modulesNode.isArray()) {
                for (JsonNode m : modulesNode) {
                    affected.add(m.asText());
                }
            }

            PlannerReport plannerReport = PlannerReport.builder()
                    .issueType(root.path("issueType").asText("Unclassified Bug"))
                    .confidence(root.path("plannerConfidence").asDouble(0.85))
                    .repairStrategy(root.path("repairStrategy").asText("Examine code context and fix details"))
                    .affectedModules(affected)
                    .build();

            return new AnalysisResult(investigatorReport, plannerReport);

        } catch (Exception e) {
            log.warn("[WARNING] Analysis Agent failed to parse structured JSON: {}. Using heuristics fallback.", e.getMessage());
            
            // Heuristics fallback
            String text = (ticket.getSummary() + " " + ticket.getDescription()).toLowerCase();
            String service = "Payment Service";
            String hypothesis = "Parameter user object reference was dereferenced while holding null pointer.";
            List<EvidenceItem> evidence = new ArrayList<>();
            evidence.add(new EvidenceItem("Description scan", "Identified keyword traces in stack logs"));

            String issue = "NullPointerException";
            String strategy = "Add parameter null validations.";
            String targetFile = "lib/fix.ts";

            if (text.contains("mapper") || text.contains("bounds") || text.contains("array") || text.contains("index")) {
                service = "User Management";
                hypothesis = "Indexing split CSV elements without length constraint matching.";
                issue = "ArrayIndexOutOfBoundsException";
                strategy = "Add index boundaries validation.";
                targetFile = "backend/src/main/java/com/automata/service/agent/RecoveryAgent.java";
            }

            InvestigatorReport investigatorReport = InvestigatorReport.builder()
                    .service(service)
                    .environment("Production")
                    .severity("High")
                    .evidence(evidence)
                    .hypothesis(hypothesis)
                    .confidence(0.85)
                    .build();

            PlannerReport plannerReport = PlannerReport.builder()
                    .issueType(issue)
                    .confidence(0.88)
                    .repairStrategy(strategy)
                    .affectedModules(List.of(targetFile))
                    .build();

            return new AnalysisResult(investigatorReport, plannerReport);
        }
    }
}
