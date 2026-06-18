package com.automata.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
public class AgentReports {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlannerReport {
        private String issueType;
        private double confidence;
        private String repairStrategy;
        private List<String> affectedModules;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceItem {
        private String type;
        private String desc;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestigatorReport {
        private String service;
        private String environment;
        private String severity;
        private List<EvidenceItem> evidence;
        private String hypothesis;
        private double confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewerReport {
        private String security;
        private String performance;
        private String maintainability;
        private String recommendation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttemptItem {
        private int id;
        private String status;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecoveryReport {
        private String example;
        private String strategy;
        private List<AttemptItem> attempts;
        /** "brace_check" | "compiler" | "compiler+tests" */
        private String validationMode;
        /** Raw compiler output (truncated to 2000 chars) for display in the dashboard */
        private String compilerOutput;
        /** Total healing attempts consumed */
        private int totalAttemptsUsed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RcaReport {
        private String issue;
        private String rootCause;
        private String impact;
        private String filesModified;
        private String fixApplied;
        private String risk;
        private String rollback;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryReport {
        private String id;
        private String issue;
        private String rootCause;
        private String resolution;
        private String confidence;
    }
}
