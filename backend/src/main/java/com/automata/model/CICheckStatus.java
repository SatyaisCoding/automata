package com.automata.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CICheckStatus {
    private String status; // pending, success, failure, error
    private String conclusion;
    private List<CheckRun> checks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckRun {
        private String name;
        private String status;
        private String conclusion;
    }
}
