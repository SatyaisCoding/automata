package com.automata.service;

import com.automata.model.FileChange;
import com.automata.model.ValidationResult;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CodeValidatorService {

    public ValidationResult validateGeneratedCode(List<FileChange> fileChanges, String projectRoot) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (fileChanges == null || fileChanges.isEmpty()) {
            warnings.add("No code files to validate");
            return ValidationResult.builder().success(true).errors(errors).warnings(warnings).build();
        }

        List<FileChange> codeFiles = fileChanges.stream()
                .filter(fc -> fc.getPath().matches(".*\\.(ts|tsx|js|jsx|java|py|go)$"))
                .collect(Collectors.toList());

        if (codeFiles.isEmpty()) {
            warnings.add("No code files to validate");
            return ValidationResult.builder().success(true).errors(errors).warnings(warnings).build();
        }

        // Basic brace/syntax check
        for (FileChange fileChange : codeFiles) {
            String content = fileChange.getContent();
            if (content == null) continue;

            if (fileChange.getPath().contains("tsconfig") || fileChange.getPath().contains("package")) {
                continue;
            }

            int openBraces = countOccurrences(content, '{');
            int closeBraces = countOccurrences(content, '}');
            int openParens = countOccurrences(content, '(');
            int closeParens = countOccurrences(content, ')');
            int openBrackets = countOccurrences(content, '[');
            int closeBrackets = countOccurrences(content, ']');

            if (openBraces != closeBraces) {
                errors.add(fileChange.getPath() + ": Unmatched braces");
            }
            if (openParens != closeParens) {
                errors.add(fileChange.getPath() + ": Unmatched parentheses");
            }
            if (openBrackets != closeBrackets) {
                errors.add(fileChange.getPath() + ": Unmatched brackets");
            }
        }

        // Check if compiler/lint tools are available
        if (projectRoot != null) {
            if (codeFiles.stream().anyMatch(fc -> fc.getPath().matches(".*\\.(ts|tsx)$"))) {
                if (!isToolAvailable("tsc", projectRoot)) {
                    warnings.add("TypeScript compiler not available - type check skipped");
                } else {
                    warnings.add("Full type checking requires project context - skipped");
                }
            }

            if (!isToolAvailable("eslint", projectRoot)) {
                warnings.add("ESLint not available - lint check skipped");
            } else {
                warnings.add("Full linting requires project context - skipped");
            }
        }

        boolean success = errors.isEmpty();
        return ValidationResult.builder()
                .success(success)
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    private int countOccurrences(String content, char ch) {
        int count = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == ch) {
                count++;
            }
        }
        return count;
    }

    private boolean isToolAvailable(String command, String workingDir) {
        try {
            ProcessBuilder builder = new ProcessBuilder("which", command);
            Process process = builder.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    return line != null && !line.trim().isEmpty();
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
