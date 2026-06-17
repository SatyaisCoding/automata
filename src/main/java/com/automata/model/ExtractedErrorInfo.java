package com.automata.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedErrorInfo {
    private String errorMessage;
    private String stackTrace;
    private String testFailure;
    private String errorType;
    private Integer lineNumber;
    private String filePath;
}
