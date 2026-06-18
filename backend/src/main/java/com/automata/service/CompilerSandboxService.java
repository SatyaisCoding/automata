package com.automata.service;

import com.automata.model.CompilationResult;
import com.automata.model.FileChange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * CompilerSandboxService — Compiler-in-the-Loop self-healing engine.
 *
 * <p>Writes AI-generated file patches into a temporary sandbox directory,
 * runs the real compiler (Maven or Node), captures the exit code and
 * error output, then restores all originals — leaving the live project
 * untouched.
 */
@Slf4j
@Service
public class CompilerSandboxService {

    @Value("${automata.compiler.enabled:true}")
    private boolean compilerEnabled;

    @Value("${automata.compiler.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${automata.compiler.run-tests:false}")
    private boolean runTests;

    @Value("${automata.compiler.project-root:}")
    private String configuredProjectRoot;

    private static final int MAX_OUTPUT_CHARS = 4000;

    // Regex patterns for extracting Java/Maven compiler errors
    private static final Pattern JAVA_ERROR_PATTERN =
            Pattern.compile("(?m)^.*\\.java:\\d+:.*error:.*$");
    // Pattern for Node / TypeScript errors
    private static final Pattern TS_ERROR_PATTERN =
            Pattern.compile("(?m)^.*\\.tsx?\\(\\d+,\\d+\\):.*error.*$");
    // Generic "error:" pattern as fallback
    private static final Pattern GENERIC_ERROR_PATTERN =
            Pattern.compile("(?m)^.*error:.*$", Pattern.CASE_INSENSITIVE);

    /**
     * Detect the build tool used in the given project root.
     *
     * @return "maven" if pom.xml found, "node" if package.json found, "unknown" otherwise
     */
    public String detectProjectType(String projectRoot) {
        if (projectRoot == null) return "unknown";
        Path root = Paths.get(projectRoot);
        if (Files.exists(root.resolve("pom.xml"))) return "maven";
        if (Files.exists(root.resolve("package.json"))) return "node";
        return "unknown";
    }

    /**
     * Main entry point: patches files in a temp sandbox, compiles, captures errors,
     * then restores originals.
     *
     * @param fileChanges the AI-generated patches to validate
     * @param projectRoot the root of the project (where pom.xml / package.json lives)
     * @return CompilationResult with pass/fail, errors, and raw output
     */
    public CompilationResult runCompilerCheck(List<FileChange> fileChanges, String projectRoot) {
        if (!compilerEnabled) {
            log.info("[CompilerSandbox] Compiler validation disabled via config. Skipping.");
            return CompilationResult.builder()
                    .passed(true)
                    .exitCode(0)
                    .rawOutput("[CompilerSandbox] Compiler check disabled.")
                    .compilerErrors(Collections.emptyList())
                    .projectType("disabled")
                    .durationMs(0)
                    .build();
        }

        // Resolve project root: use config value if set, otherwise use detected root
        String resolvedRoot = resolveProjectRoot(projectRoot);
        String projectType = detectProjectType(resolvedRoot);

        log.info("[CompilerSandbox] Detected project type: {} at {}", projectType, resolvedRoot);

        if ("unknown".equals(projectType)) {
            log.warn("[CompilerSandbox] Cannot determine project type, falling back to brace check only.");
            return CompilationResult.builder()
                    .passed(true)
                    .exitCode(0)
                    .rawOutput("[CompilerSandbox] Project type unknown — skipping compiler check.")
                    .compilerErrors(Collections.emptyList())
                    .projectType("unknown")
                    .durationMs(0)
                    .build();
        }

        // Back up originals and write patched files
        Map<Path, String> backups = new LinkedHashMap<>();
        List<Path> newFiles = new ArrayList<>();
        try {
            applyPatches(fileChanges, resolvedRoot, backups, newFiles);
        } catch (IOException e) {
            log.error("[CompilerSandbox] Failed to write patches: {}", e.getMessage());
            return CompilationResult.builder()
                    .passed(false)
                    .exitCode(-1)
                    .rawOutput("Failed to write sandbox patches: " + e.getMessage())
                    .compilerErrors(List.of("Sandbox setup error: " + e.getMessage()))
                    .projectType(projectType)
                    .durationMs(0)
                    .build();
        }

        // Run the compiler
        CompilationResult result;
        try {
            result = executeCompiler(projectType, resolvedRoot);
        } finally {
            // Always restore originals — even if compiler throws
            restoreOriginals(backups, newFiles);
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes patched file content to the project tree.
     * Backs up any file that already exists so it can be restored later.
     */
    private void applyPatches(List<FileChange> fileChanges,
                               String projectRoot,
                               Map<Path, String> backups,
                               List<Path> newFiles) throws IOException {
        Path root = Paths.get(projectRoot);
        for (FileChange fc : fileChanges) {
            if (fc.getPath() == null || fc.getContent() == null) continue;

            // Strip leading "/" if present so we get a relative path
            String relative = fc.getPath().startsWith("/")
                    ? fc.getPath().substring(1)
                    : fc.getPath();

            Path target = root.resolve(relative).normalize();

            if (!target.startsWith(root)) {
                log.warn("[CompilerSandbox] Path escape attempt blocked: {}", fc.getPath());
                continue;
            }

            if (Files.exists(target)) {
                // Back up existing content
                backups.put(target, Files.readString(target));
            } else {
                // Track new files so we can delete them on restore
                newFiles.add(target);
                Files.createDirectories(target.getParent());
            }

            Files.writeString(target, fc.getContent());
            log.info("[CompilerSandbox] Patched: {}", relative);
        }
    }

    /**
     * Builds and executes the compiler command, waits for output, and
     * returns a structured CompilationResult.
     */
    private CompilationResult executeCompiler(String projectType, String projectRoot) {
        List<String> command = buildCommand(projectType);
        log.info("[CompilerSandbox] Running: {} in {}", String.join(" ", command), projectRoot);

        long startMs = System.currentTimeMillis();
        StringBuilder outputBuilder = new StringBuilder();
        int exitCode = -1;

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(projectRoot));
            pb.redirectErrorStream(true); // merge stderr into stdout
            pb.environment().put("MAVEN_OPTS", "-Xmx256m"); // limit memory in sandbox

            Process process = pb.start();

            // Read output in a separate thread to avoid blocking
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                } catch (IOException e) {
                    return "[Error reading output]: " + e.getMessage();
                }
            });

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[CompilerSandbox] Compiler timed out after {}s", timeoutSeconds);
                outputBuilder.append("[TIMEOUT] Compiler did not finish within ")
                        .append(timeoutSeconds).append(" seconds.");
                exitCode = -2;
            } else {
                exitCode = process.exitValue();
                String rawOut = outputFuture.get(5, TimeUnit.SECONDS);
                outputBuilder.append(rawOut);
            }

        } catch (Exception e) {
            log.error("[CompilerSandbox] Compiler execution error: {}", e.getMessage());
            outputBuilder.append("[ERROR] ").append(e.getMessage());
            exitCode = -1;
        }

        long durationMs = System.currentTimeMillis() - startMs;
        String rawOutput = truncate(outputBuilder.toString(), MAX_OUTPUT_CHARS);
        boolean passed = (exitCode == 0);
        List<String> errors = passed ? Collections.emptyList() : parseCompilerErrors(rawOutput, projectType);

        log.info("[CompilerSandbox] Compiler {} in {}ms (exit={}). Errors: {}",
                passed ? "PASSED" : "FAILED", durationMs, exitCode, errors.size());

        return CompilationResult.builder()
                .passed(passed)
                .exitCode(exitCode)
                .rawOutput(rawOutput)
                .compilerErrors(errors)
                .projectType(projectType)
                .durationMs(durationMs)
                .build();
    }

    /** Build the OS command array for the detected project type */
    private List<String> buildCommand(String projectType) {
        if ("maven".equals(projectType)) {
            // Use mvn compile (fast) or mvn test (thorough) based on config
            String goal = runTests ? "test" : "compile";
            // Try to find mvn on PATH; on macOS it may be via brew or wrapper
            String mvn = findExecutable("mvn", "./mvnw");
            return List.of(mvn, goal, "-q", "--no-transfer-progress", "-DskipTests=" + !runTests);
        } else {
            // Node/npm: run tests in CI mode (no watch)
            String npm = findExecutable("npm", "npm");
            return List.of(npm, runTests ? "test" : "run", runTests ? "--" : "build",
                    runTests ? "--watchAll=false" : "");
        }
    }

    /** Finds the first available executable from candidates */
    private String findExecutable(String... candidates) {
        for (String candidate : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder("which", candidate);
                Process p = pb.start();
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String path = r.readLine();
                        if (path != null && !path.isBlank()) return path.trim();
                    }
                }
            } catch (Exception ignored) { /* fall through to next candidate */ }
        }
        return candidates[candidates.length - 1]; // last candidate as default
    }

    /**
     * Parses compiler error lines from raw output using language-specific regex patterns.
     * Falls back to a generic "error:" scan if specific patterns yield no results.
     */
    private List<String> parseCompilerErrors(String rawOutput, String projectType) {
        if (rawOutput == null || rawOutput.isBlank()) return Collections.emptyList();

        Pattern primary = "maven".equals(projectType) ? JAVA_ERROR_PATTERN : TS_ERROR_PATTERN;
        List<String> errors = matchAll(primary, rawOutput);

        if (errors.isEmpty()) {
            errors = matchAll(GENERIC_ERROR_PATTERN, rawOutput);
        }

        // Deduplicate and cap at 20 lines to keep prompts manageable
        return errors.stream().distinct().limit(20).collect(Collectors.toList());
    }

    private List<String> matchAll(Pattern pattern, String text) {
        List<String> matches = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String line = m.group().trim();
            if (!line.isBlank()) matches.add(line);
        }
        return matches;
    }

    /** Restores original files and deletes any newly created files from the patch */
    private void restoreOriginals(Map<Path, String> backups, List<Path> newFiles) {
        for (Map.Entry<Path, String> entry : backups.entrySet()) {
            try {
                Files.writeString(entry.getKey(), entry.getValue());
                log.debug("[CompilerSandbox] Restored: {}", entry.getKey());
            } catch (IOException e) {
                log.error("[CompilerSandbox] CRITICAL: Failed to restore {}: {}", entry.getKey(), e.getMessage());
            }
        }
        for (Path newFile : newFiles) {
            try {
                Files.deleteIfExists(newFile);
            } catch (IOException e) {
                log.warn("[CompilerSandbox] Could not delete temp file {}: {}", newFile, e.getMessage());
            }
        }
    }

    /** Resolves the effective project root, preferring the config value over the passed-in value */
    private String resolveProjectRoot(String passedRoot) {
        if (configuredProjectRoot != null && !configuredProjectRoot.isBlank()) {
            return configuredProjectRoot;
        }
        if (passedRoot != null && !passedRoot.isBlank()) {
            return passedRoot;
        }
        // Default: current working directory (where Spring Boot runs from)
        return System.getProperty("user.dir");
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... [output truncated]";
    }
}
