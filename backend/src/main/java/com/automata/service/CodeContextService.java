package com.automata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.automata.model.JiraTicket;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CodeContextService {

    @Value("${automata.github-token:}")
    private String githubToken;

    @Value("${automata.github-owner:}")
    private String githubOwner;

    @Value("${automata.github-repo:}")
    private String githubRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", "is", "are", 
        "was", "were", "be", "been", "have", "has", "had", "do", "does", "did", "will", "would", "should", 
        "could", "may", "might", "must", "can", "this", "that", "these", "those", "i", "you", "he", "she", 
        "it", "we", "they", "what", "which", "who", "when", "where", "why", "how", "all", "each", "every", 
        "both", "few", "more", "most", "other", "some", "such", "no", "nor", "not", "only", "own", "same", 
        "so", "than", "too", "very", "just", "now"
    );

    @Data
    @AllArgsConstructor
    public static class CodeContext {
        private String filename;
        private String content;
    }

    private List<String> extractKeywords(JiraTicket ticket) {
        String text = (ticket.getSummary() + " " + ticket.getDescription()).toLowerCase();
        String[] words = text.split("\\s+");

        Set<String> keywords = new LinkedHashSet<>();
        for (String word : words) {
            String clean = word.replaceAll("[^a-zA-Z]", "");
            if (clean.length() > 3 && !STOP_WORDS.contains(clean)) {
                keywords.add(clean);
            }
        }

        return keywords.stream().limit(20).collect(Collectors.toList());
    }

    private boolean shouldIgnoreFile(String path) {
        List<String> ignorePatterns = List.of(
            "node_modules", "dist", "build", ".next", ".env", ".git", "coverage", ".DS_Store"
        );
        return ignorePatterns.stream().anyMatch(path::contains);
    }

    private boolean hasRelevantExtension(String path) {
        return path.endsWith(".ts") || path.endsWith(".tsx") || path.endsWith(".js") || path.endsWith(".jsx")
                || path.endsWith(".java") || path.endsWith(".py") || path.endsWith(".go")
                || path.endsWith(".properties") || path.endsWith(".xml") || path.endsWith(".yml") || path.endsWith(".yaml");
    }

    private int scoreFile(String path, List<String> keywords) {
        String lowerPath = path.toLowerCase();
        int score = 0;

        for (String keyword : keywords) {
            if (lowerPath.contains(keyword)) {
                score += 10;
            }

            String[] parts = lowerPath.split("/");
            String filename = parts.length > 0 ? parts[parts.length - 1] : "";
            if (filename.contains(keyword)) {
                score += 20;
            }
        }

        return score;
    }

    public List<CodeContext> getCodeContext(JiraTicket ticket) {
        try {
            List<String> keywords = extractKeywords(ticket);
            log.info("[INFO] Extracted keywords: {}", keywords);

            List<String> allFiles = fetchRepositoryTree();
            log.info("[INFO] Found {} files in repository", allFiles.size());

            // Score and filter files
            List<ScoredFile> relevantFiles = allFiles.stream()
                    .filter(path -> hasRelevantExtension(path) && !shouldIgnoreFile(path))
                    .map(path -> new ScoredFile(path, scoreFile(path, keywords)))
                    .sorted((a, b) -> Integer.compare(b.score, a.score))
                    .limit(3)
                    .collect(Collectors.toList());

            log.info("[INFO] Selected files: {}", relevantFiles.stream()
                    .map(sf -> sf.path + " (score: " + sf.score + ")")
                    .collect(Collectors.toList()));

            List<java.util.concurrent.CompletableFuture<CodeContext>> contentFutures = relevantFiles.stream()
                    .map(sf -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            String content = fetchFileContent(sf.path);
                            return new CodeContext(sf.path, content);
                        } catch (Exception e) {
                            log.error("[ERROR] Failed to fetch content for {}: {}", sf.path, e.getMessage());
                            return null;
                        }
                    }))
                    .collect(Collectors.toList());

            List<CodeContext> contexts = contentFutures.stream()
                    .map(java.util.concurrent.CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            return contexts;
        } catch (Exception e) {
            log.error("[ERROR] Error fetching code context: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> fetchRepositoryTree() throws Exception {
        if (githubToken.isEmpty() || githubOwner.isEmpty() || githubRepo.isEmpty()) {
            throw new IllegalStateException("GitHub credentials not configured (GITHUB_TOKEN, GITHUB_OWNER, GITHUB_REPO)");
        }

        // Get default branch
        String repoUrl = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo;
        HttpRequest repoRequest = HttpRequest.newBuilder()
                .uri(URI.create(repoUrl))
                .header("Authorization", "token " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> repoResponse = httpClient.send(repoRequest, HttpResponse.BodyHandlers.ofString());
        if (repoResponse.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch repository details: " + repoResponse.body());
        }

        JsonNode repoNode = objectMapper.readTree(repoResponse.body());
        String defaultBranch = repoNode.path("default_branch").asText("main");

        // Fetch tree recursively
        String treeUrl = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/git/trees/" + defaultBranch + "?recursive=1";
        HttpRequest treeRequest = HttpRequest.newBuilder()
                .uri(URI.create(treeUrl))
                .header("Authorization", "token " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> treeResponse = httpClient.send(treeRequest, HttpResponse.BodyHandlers.ofString());
        if (treeResponse.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch repository tree: " + treeResponse.body());
        }

        JsonNode treeNode = objectMapper.readTree(treeResponse.body());
        JsonNode treeList = treeNode.path("tree");
        List<String> files = new ArrayList<>();
        for (JsonNode node : treeList) {
            if ("blob".equals(node.path("type").asText())) {
                files.add(node.path("path").asText());
            }
        }

        return files;
    }

    private String fetchFileContent(String path) throws Exception {
        String url = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/contents/" + path.replace("/", "%2F");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "token " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch file content for " + path + ": " + response.body());
        }

        JsonNode node = objectMapper.readTree(response.body());
        String encoding = node.path("encoding").asText();
        String content = node.path("content").asText();

        if ("base64".equals(encoding) && content != null) {
            // Remove whitespace from base64 encoding before decoding
            String cleanedContent = content.replaceAll("\\s", "");
            byte[] decodedBytes = Base64.getDecoder().decode(cleanedContent);
            String text = new String(decodedBytes, StandardCharsets.UTF_8);
            if (text.length() > 8000) {
                return text.substring(0, 8000) + "\n// ... (truncated)";
            }
            return text;
        }

        throw new RuntimeException("Unsupported encoding for file: " + path);
    }

    private static class ScoredFile {
        String path;
        int score;

        ScoredFile(String path, int score) {
            this.path = path;
            this.score = score;
        }
    }
}
