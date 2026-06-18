package com.automata.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Structured result from the CompilerSandboxService.
 * Captures exit code, raw output, parsed error lines, and timing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompilationResult {

    /** true if the compiler/test command exited with code 0 */
    private boolean passed;

    /** Raw OS exit code from the compiler process */
    private int exitCode;

    /** Combined stdout + stderr from the compiler run (capped at 4000 chars) */
    private String rawOutput;

    /**
     * Parsed, human-readable compiler error lines extracted from rawOutput.
     * e.g. ["Calculator.java:15: error: ';' expected", ...]
     */
    private List<String> compilerErrors;

    /** "maven" | "node" | "unknown" */
    private String projectType;

    /** Wall-clock time of the compiler run in milliseconds */
    private long durationMs;

    /** The sandbox temp directory used for this run (for debugging) */
    private String sandboxPath;
}
