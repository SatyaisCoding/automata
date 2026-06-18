package com.automata.service;

import com.automata.model.FileChange;
import com.automata.model.GuardResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SafetyGuardService {

    private static final List<String> BLOCKED_PATTERNS = List.of(
            ".github/",
            "infra/",
            "auth/",
            "secrets",
            ".env",
            "package-lock.json",
            "yarn.lock",
            "pnpm-lock.yaml"
    );

    private static final int MAX_FILE_CHANGES = 3;

    private boolean isPathBlocked(String path) {
        if (path == null) return false;
        String lowerPath = path.toLowerCase();
        return BLOCKED_PATTERNS.stream().anyMatch(pattern -> lowerPath.contains(pattern.toLowerCase()));
    }

    private boolean isInvalidPath(String path) {
        if (path == null) return true;
        if (path.contains("..") || path.startsWith("/")) {
            return true;
        }
        return !path.matches(".*\\.(ts|tsx|js|jsx|json|md|java|py|go|css|html|properties|xml|yml|yaml)$");
    }

    public GuardResult validateFileChanges(List<FileChange> fileChanges) {
        if (fileChanges == null) {
            return GuardResult.builder().allowed(true).build();
        }

        // Check file limit count
        if (fileChanges.size() > MAX_FILE_CHANGES) {
            List<String> paths = fileChanges.stream().map(FileChange::getPath).collect(Collectors.toList());
            return GuardResult.builder()
                    .allowed(false)
                    .reason("Exceeds maximum file change limit of " + MAX_FILE_CHANGES)
                    .blockedFiles(paths)
                    .build();
        }

        // Check blocked paths
        List<String> blockedFiles = fileChanges.stream()
                .map(FileChange::getPath)
                .filter(this::isPathBlocked)
                .collect(Collectors.toList());

        if (!blockedFiles.isEmpty()) {
            return GuardResult.builder()
                    .allowed(false)
                    .reason("Contains files in blocked paths")
                    .blockedFiles(blockedFiles)
                    .build();
        }

        // Check invalid paths
        List<String> invalidPaths = fileChanges.stream()
                .map(FileChange::getPath)
                .filter(this::isInvalidPath)
                .collect(Collectors.toList());

        if (!invalidPaths.isEmpty()) {
            return GuardResult.builder()
                    .allowed(false)
                    .reason("Contains invalid file paths")
                    .blockedFiles(invalidPaths)
                    .build();
        }

        return GuardResult.builder().allowed(true).build();
    }

    public void guardFileChanges(List<FileChange> fileChanges) {
        GuardResult result = validateFileChanges(fileChanges);
        if (!result.isAllowed()) {
            String blockedList = result.getBlockedFiles() != null ? String.join(", ", result.getBlockedFiles()) : "";
            throw new RuntimeException("Safety guard blocked: " + result.getReason() + ". Blocked files: " + blockedList);
        }
    }
}
