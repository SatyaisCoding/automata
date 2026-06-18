package com.automata.service;

import com.automata.model.CompilationResult;
import com.automata.model.FileChange;
import com.automata.model.ValidationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CodeValidatorService — Two-tier validation gate.
 *
 * <p><b>Gate 1 (fast):</b> Brace/bracket/parenthesis balance check (~0ms).
 * <p><b>Gate 2 (real):</b> Actual compiler run via {@link CompilerSandboxService} (~5-60s).
 *
 * <p>The RecoveryAgent always runs Gate 1 first; if Gate 1 passes it then
 * calls {@link #runCompilerValidation} for Gate 2.
 */
@Service
public class CodeValidatorService {

    @Autowired
    private CompilerSandboxService compilerSandboxService;

    // ─────────────────────────────────────────────────────────────────────────
    // Gate 1: Fast brace/bracket syntax check
    // ─────────────────────────────────────────────────────────────────────────

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

        // Gate 1: brace/syntax balance check
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
                errors.add(fileChange.getPath() + ": Unmatched braces { }");
            }
            if (openParens != closeParens) {
                errors.add(fileChange.getPath() + ": Unmatched parentheses ( )");
            }
            if (openBrackets != closeBrackets) {
                errors.add(fileChange.getPath() + ": Unmatched brackets [ ]");
            }
        }

        // Tool availability warnings (kept for informational purposes)
        if (projectRoot != null) {
            if (codeFiles.stream().anyMatch(fc -> fc.getPath().matches(".*\\.(ts|tsx)$"))) {
                if (!isToolAvailable("tsc")) {
                    warnings.add("TypeScript compiler not on PATH - type check skipped");
                }
            }
        }

        boolean success = errors.isEmpty();
        return ValidationResult.builder()
                .success(success)
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gate 2: Real compiler validation via sandbox
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs Gate 2: delegates to {@link CompilerSandboxService} to actually compile
     * the patched files and capture real compiler errors.
     *
     * @param fileChanges the patches to validate
     * @param projectRoot the project root where pom.xml / package.json lives
     * @return ValidationResult whose errors list contains real compiler error lines
     */
    public ValidationResult runCompilerValidation(List<FileChange> fileChanges, String projectRoot) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        CompilationResult compResult = compilerSandboxService.runCompilerCheck(fileChanges, projectRoot);

        if (compResult.isPassed()) {
            warnings.add("Compiler passed in " + compResult.getDurationMs() + "ms (" + compResult.getProjectType() + ")");
            return ValidationResult.builder()
                    .success(true)
                    .errors(errors)
                    .warnings(warnings)
                    .build();
        }

        // Compiler failed: surface the real errors
        String header = "[Compiler:" + compResult.getProjectType() + " exit=" + compResult.getExitCode() + "] ";
        if (compResult.getCompilerErrors() != null && !compResult.getCompilerErrors().isEmpty()) {
            for (String err : compResult.getCompilerErrors()) {
                errors.add(header + err);
            }
        } else {
            // No parsed errors — use a snippet of raw output
            String rawSnippet = compResult.getRawOutput() != null
                    ? compResult.getRawOutput().substring(0, Math.min(500, compResult.getRawOutput().length()))
                    : "No output captured";
            errors.add(header + rawSnippet);
        }

        return ValidationResult.builder()
                .success(false)
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    /**
     * Convenience method: returns the raw {@link CompilationResult} directly,
     * useful when the caller needs timing/output for logging.
     */
    public CompilationResult getCompilationResult(List<FileChange> fileChanges, String projectRoot) {
        return compilerSandboxService.runCompilerCheck(fileChanges, projectRoot);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private int countOccurrences(String content, char ch) {
        int count = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == ch) {
                count++;
            }
        }
        return count;
    }

    private boolean isToolAvailable(String command) {
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
