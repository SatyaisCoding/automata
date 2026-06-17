package com.automata.service;

import com.automata.model.ExtractedErrorInfo;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ErrorExtractor {

    public ExtractedErrorInfo extractErrorInfo(String description) {
        if (description == null || description.trim().isEmpty()) {
            return new ExtractedErrorInfo();
        }

        ExtractedErrorInfo info = new ExtractedErrorInfo();

        // 1. Extract Stack Traces
        Pattern[] stackTracePatterns = {
            Pattern.compile("(?:Error|Exception|TypeError|ReferenceError|SyntaxError)[:\\s]+([^\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("at\\s+([^\\s]+)\\s+\\(([^:]+):(\\d+):(\\d+)\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:Stack trace|StackTrace):\\s*([\\s\\S]+?)(?:\\n\\n|\\n[A-Z]|$)", Pattern.CASE_INSENSITIVE)
        };

        StringBuilder stackTraceBuilder = new StringBuilder();
        for (Pattern pattern : stackTracePatterns) {
            Matcher matcher = pattern.matcher(description);
            if (matcher.find()) {
                stackTraceBuilder.append(matcher.group(0)).append("\n");
            }
        }
        if (stackTraceBuilder.length() > 0) {
            info.setStackTrace(stackTraceBuilder.toString().trim());
        }

        // 2. Extract Error Messages
        Pattern[] errorMessagePatterns = {
            Pattern.compile("(?:Error|Exception|Failed|Fails?):\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:Error message|Error Message):\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"([^\"]*Error[^\"]*)\"|'([^']*Error[^']*)'", Pattern.CASE_INSENSITIVE)
        };

        for (Pattern pattern : errorMessagePatterns) {
            Matcher matcher = pattern.matcher(description);
            if (matcher.find()) {
                String match = matcher.group(1) != null ? matcher.group(1) : 
                              (matcher.group(2) != null ? matcher.group(2) : matcher.group(0));
                info.setErrorMessage(match);
                break;
            }
        }

        // 3. Extract Test Failures
        Pattern[] testFailurePatterns = {
            Pattern.compile("(?:Test|Spec)\\s+(?:failed|failure|error)[:\\s]+([^\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:FAIL|FAILED|ERROR)\\s+([^\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:Expected|Expected:)\\s+([^\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:Actual|Actual:)\\s+([^\\n]+)", Pattern.CASE_INSENSITIVE)
        };

        for (Pattern pattern : testFailurePatterns) {
            Matcher matcher = pattern.matcher(description);
            if (matcher.find()) {
                String match = matcher.group(1) != null ? matcher.group(1) : matcher.group(0);
                info.setTestFailure(match);
                break;
            }
        }

        // 4. Extract File Path and Line Number
        Pattern fileLinePattern = Pattern.compile("([\\/\\w\\-\\.]+\\.(?:ts|tsx|js|jsx)):(\\d+)");
        Matcher fileLineMatcher = fileLinePattern.matcher(description);
        if (fileLineMatcher.find()) {
            info.setFilePath(fileLineMatcher.group(1));
            try {
                info.setLineNumber(Integer.parseInt(fileLineMatcher.group(2)));
            } catch (NumberFormatException ignored) {}
        }

        // 5. Extract Error Type
        Pattern errorTypePattern = Pattern.compile("(TypeError|ReferenceError|SyntaxError|Error|Exception|ValidationError|RuntimeError)", Pattern.CASE_INSENSITIVE);
        Matcher errorTypeMatcher = errorTypePattern.matcher(description);
        if (errorTypeMatcher.find()) {
            info.setErrorType(errorTypeMatcher.group(1));
        }

        return info;
    }

    public String formatErrorInfoForPrompt(ExtractedErrorInfo errorInfo) {
        if (errorInfo == null || (errorInfo.getErrorMessage() == null && errorInfo.getStackTrace() == null && errorInfo.getTestFailure() == null)) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("\n---\nError Information:\n\n");

        if (errorInfo.getErrorType() != null) {
            builder.append("Error Type: ").append(errorInfo.getErrorType()).append("\n");
        }
        if (errorInfo.getErrorMessage() != null) {
            builder.append("Error Message: ").append(errorInfo.getErrorMessage()).append("\n");
        }
        if (errorInfo.getFilePath() != null && errorInfo.getLineNumber() != null) {
            builder.append("Location: ").append(errorInfo.getFilePath()).append(":").append(errorInfo.getLineNumber()).append("\n");
        }
        if (errorInfo.getStackTrace() != null) {
            builder.append("\nStack Trace:\n").append(errorInfo.getStackTrace()).append("\n");
        }
        if (errorInfo.getTestFailure() != null) {
            builder.append("\nTest Failure:\n").append(errorInfo.getTestFailure()).append("\n");
        }

        builder.append("---\n");
        return builder.toString();
    }
}
