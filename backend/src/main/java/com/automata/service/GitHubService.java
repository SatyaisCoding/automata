package com.automata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.automata.model.CICheckStatus;
import com.automata.model.FileChange;
import com.automata.model.JiraTicket;
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
public class GitHubService {

    @Value("${automata.github-token:}")
    private String githubToken;

    @Value("${automata.github-owner:}")
    private String githubOwner;

    @Value("${automata.github-repo:}")
    private String githubRepo;

    @Value("${automata.github-default-branch:main}")
    private String defaultBranch;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Map<String, String> getGitHubHeaders() {
        if (githubToken.isEmpty()) {
            throw new IllegalStateException("GITHUB_TOKEN environment variable is not set");
        }

        String authHeader = githubToken.startsWith("github_pat_")
                ? "Bearer " + githubToken
                : "token " + githubToken;

        return Map.of(
                "Authorization", authHeader,
                "Accept", "application/vnd.github.v3+json",
                "Content-Type", "application/json"
        );
    }

    private HttpRequest.Builder newRequestBuilder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url));
        getGitHubHeaders().forEach(builder::header);
        return builder;
    }

    private String getDefaultBranchSha() throws Exception {
        String url = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/git/ref/heads/" + defaultBranch;
        HttpRequest request = newRequestBuilder(url).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to get default branch SHA: " + response.body());
        }

        JsonNode node = objectMapper.readTree(response.body());
        return node.path("object").path("sha").asText();
    }

    public String createBranch(String branchName) throws Exception {
        String defaultBranchSha = getDefaultBranchSha();
        String url = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/git/refs";

        Map<String, String> body = Map.of(
                "ref", "refs/heads/" + branchName,
                "sha", defaultBranchSha
        );
        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = newRequestBuilder(url)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201) {
            String error = response.body();
            // Branch might already exist, try to fetch its SHA
            if (response.statusCode() == 422) {
                String existingUrl = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/git/ref/heads/" + branchName;
                HttpRequest getRefRequest = newRequestBuilder(existingUrl).GET().build();
                HttpResponse<String> getRefResponse = httpClient.send(getRefRequest, HttpResponse.BodyHandlers.ofString());
                if (getRefResponse.statusCode() == 200) {
                    JsonNode node = objectMapper.readTree(getRefResponse.body());
                    return node.path("object").path("sha").asText();
                }
            }
            throw new RuntimeException("Failed to create branch: " + error);
        }

        JsonNode node = objectMapper.readTree(response.body());
        return node.path("object").path("sha").asText();
    }

    private String getFileSha(String branch, String path) {
        try {
            String url = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/contents/" + path.replace("/", "%2F") + "?ref=" + branch;
            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                return node.path("sha").asText();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String createOrUpdateFile(String branch, FileChange fileChange) throws Exception {
        String sha = getFileSha(branch, fileChange.getPath());
        String encodedContent = Base64.getEncoder().encodeToString(fileChange.getContent().getBytes(StandardCharsets.UTF_8));
        String url = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/contents/" + fileChange.getPath().replace("/", "%2F");

        Map<String, String> body = new HashMap<>();
        body.put("message", "Update " + fileChange.getPath());
        body.put("content", encodedContent);
        body.put("branch", branch);
        if (sha != null) {
            body.put("sha", sha);
        }

        String requestBody = objectMapper.writeValueAsString(body);
        HttpRequest request = newRequestBuilder(url)
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RuntimeException("Failed to commit file change: " + response.body());
        }

        JsonNode node = objectMapper.readTree(response.body());
        return node.path("commit").path("sha").asText();
    }

    public String commitChanges(String branch, List<FileChange> fileChanges, String commitMessage) throws Exception {
        if (fileChanges == null || fileChanges.isEmpty()) {
            throw new IllegalArgumentException("No file changes to commit");
        }

        String lastCommitSha = null;
        for (FileChange fileChange : fileChanges) {
            lastCommitSha = createOrUpdateFile(branch, fileChange);
        }

        return lastCommitSha;
    }

    public List<FileChange> parseAICodeOutput(String aiOutput) {
        List<FileChange> changes = new ArrayList<>();
        if (aiOutput == null || aiOutput.trim().isEmpty()) {
            return changes;
        }

        String[] lines = aiOutput.split("\n");
        String currentPath = null;
        List<String> currentContent = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            String newPath = null;

            // 1. Detect path from file: or path: prefix
            if (trimmed.toLowerCase().startsWith("file:")) {
                newPath = trimmed.substring(5).trim();
            } else if (trimmed.toLowerCase().startsWith("path:")) {
                newPath = trimmed.substring(5).trim();
            }
            // 2. Detect path from code block backticks ```lang:path
            else if (trimmed.startsWith("```")) {
                String remainder = trimmed.substring(3).trim();
                if (!remainder.isEmpty()) {
                    int colonIndex = remainder.indexOf(':');
                    if (colonIndex != -1) {
                        newPath = remainder.substring(colonIndex + 1).trim();
                    } else if (remainder.contains(".") || remainder.contains("/")) {
                        newPath = remainder;
                    }
                }
            }
            // 3. Detect path from code comments (e.g. // UserMapper.java) at the start of a block
            else if ((trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*")) && currentContent.isEmpty()) {
                String potentialPath = trimmed.replace("//", "").replace("#", "").replace("/*", "").replace("*/", "").trim();
                if (potentialPath.matches("[a-zA-Z0-9_\\-\\./]+\\.[a-zA-Z0-9]+")) {
                    newPath = potentialPath;
                }
            }

            if (newPath != null) {
                // Clean leading slashes/dots
                newPath = newPath.replaceAll("^\\.+/", "").replaceAll("^/+", "").trim();
                
                if (currentPath != null && !currentContent.isEmpty()) {
                    changes.add(FileChange.builder()
                            .path(currentPath)
                            .content(String.join("\n", currentContent))
                            .build());
                }
                currentPath = newPath;
                currentContent.clear();
                continue;
            }

            // Ignore markdown backtick lines completely
            if (trimmed.startsWith("```")) {
                continue;
            }

            if (currentPath != null) {
                currentContent.add(line);
            } else {
                if (trimmed.contains("class ") || trimmed.contains("function ") || trimmed.contains("import ") || trimmed.contains("public ")) {
                    currentPath = "lib/fix.ts"; // default fallback path if none inferred
                    currentContent.add(line);
                }
            }
        }

        if (currentPath != null && !currentContent.isEmpty()) {
            changes.add(FileChange.builder()
                    .path(currentPath)
                    .content(String.join("\n", currentContent))
                    .build());
        }

        // Default fallback
        if (changes.isEmpty() && !aiOutput.trim().isEmpty()) {
            changes.add(FileChange.builder()
                    .path("lib/ai-fix.ts")
                    .content(aiOutput.trim())
                    .build());
        }

        // Validate extensions
        return changes.stream()
                .filter(fc -> {
                    String path = fc.getPath();
                    if (path.contains("..") || path.startsWith("/")) {
                        return false;
                    }
                    return path.matches(".*\\.(ts|tsx|js|jsx|json|md|java|py|go|css|html|properties|xml|yml|yaml)$");
                })
                .collect(Collectors.toList());
    }

    public Map<String, Object> createPullRequest(String branch, JiraTicket ticket, List<FileChange> fileChanges, String aiSummary) throws Exception {
        String url = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/pulls";
        String modifiedFiles = fileChanges.stream()
                .map(fc -> "- `" + fc.getPath() + "`")
                .collect(Collectors.joining("\n"));

        String prBody = "## Jira Ticket\n" +
                "**Key:** " + ticket.getKey() + "\n" +
                "**Summary:** " + ticket.getSummary() + "\n" +
                "**Priority:** " + (ticket.getPriority() != null ? ticket.getPriority() : "Not specified") + "\n\n" +
                "## Description\n" +
                ticket.getDescription() + "\n\n" +
                (aiSummary != null ? "## AI-Generated Summary\n" + aiSummary + "\n\n" : "") +
                "## Modified Files\n" +
                modifiedFiles + "\n\n" +
                "---\n\n" +
                "## Review Checklist\n\n" +
                "- [ ] Human code review completed\n" +
                "- [ ] Tests executed\n" +
                "- [ ] Security validated\n\n" +
                "---\n\n" +
                "**Warning:** This PR was generated by Automata and requires human approval.\n\n" +
                "Note: This PR was generated by Automata and requires human review.\n\n" +
                "**Please review all changes before merging.**";

        Map<String, Object> body = Map.of(
                "title", "Fix: " + ticket.getKey() + " – " + ticket.getSummary(),
                "body", prBody,
                "head", branch,
                "base", defaultBranch,
                "draft", true
        );

        String requestBody = objectMapper.writeValueAsString(body);
        HttpRequest request = newRequestBuilder(url)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            String errorMsg = response.body();
            // Check if PR already exists for this branch (GitHub returns 422 with a specific error message)
            if (response.statusCode() == 422) {
                try {
                    String checkUrl = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/pulls?head=" + githubOwner + ":" + branch + "&state=all";
                    HttpRequest checkRequest = newRequestBuilder(checkUrl).GET().build();
                    HttpResponse<String> checkResponse = httpClient.send(checkRequest, HttpResponse.BodyHandlers.ofString());
                    if (checkResponse.statusCode() == 200) {
                        JsonNode prsNode = objectMapper.readTree(checkResponse.body());
                        if (prsNode.isArray() && prsNode.size() > 0) {
                            JsonNode firstPr = prsNode.get(0);
                            log.warn("[WARNING] PR already exists for branch {}: #{}", branch, firstPr.path("number").asInt());
                            return Map.of(
                                    "prUrl", firstPr.path("html_url").asText(),
                                    "prNumber", firstPr.path("number").asInt()
                            );
                        }
                    }
                } catch (Exception checkError) {
                    log.warn("[WARNING] Failed to query existing PRs: {}", checkError.getMessage());
                }
            }
            throw new RuntimeException("Failed to create pull request: " + errorMsg);
        }

        JsonNode node = objectMapper.readTree(response.body());
        String prUrl = node.path("html_url").asText();
        int prNumber = node.path("number").asInt();

        // Add label to PR
        try {
            String labelUrl = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/issues/" + prNumber + "/labels";
            Map<String, List<String>> labelBody = Map.of("labels", List.of("automata-generated"));
            String labelReqBody = objectMapper.writeValueAsString(labelBody);

            HttpRequest labelRequest = newRequestBuilder(labelUrl)
                    .POST(HttpRequest.BodyPublishers.ofString(labelReqBody))
                    .build();
            httpClient.send(labelRequest, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[WARNING] Failed to add label to PR: {}", e.getMessage());
        }

        return Map.of(
                "prUrl", prUrl,
                "prNumber", prNumber
        );
    }

    public CICheckStatus waitForCIChecks(int prNumber, int maxWaitTimeMs, int pollIntervalMs) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
            try {
                // Get PR details to fetch current head commit SHA
                String prUrl = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/pulls/" + prNumber;
                HttpRequest prRequest = newRequestBuilder(prUrl).GET().build();
                HttpResponse<String> prResponse = httpClient.send(prRequest, HttpResponse.BodyHandlers.ofString());

                if (prResponse.statusCode() != 200) {
                    throw new RuntimeException("Failed to fetch PR status: " + prResponse.body());
                }

                JsonNode prNode = objectMapper.readTree(prResponse.body());
                String commitSha = prNode.path("head").path("sha").asText();

                // 1. Check Commit Status
                String statusUrl = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/commits/" + commitSha + "/status";
                HttpRequest statusRequest = newRequestBuilder(statusUrl).GET().build();
                HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());

                if (statusResponse.statusCode() == 200) {
                    JsonNode statusNode = objectMapper.readTree(statusResponse.body());
                    String state = statusNode.path("state").asText();
                    if ("success".equals(state)) {
                        return CICheckStatus.builder().status("success").conclusion("success").build();
                    } else if ("failure".equals(state) || "error".equals(state)) {
                        return CICheckStatus.builder().status("failure").conclusion(state).build();
                    }
                }

                // 2. Check Check Runs
                String checksUrl = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/commits/" + commitSha + "/check-runs";
                HttpRequest checksRequest = newRequestBuilder(checksUrl).GET().build();
                HttpResponse<String> checksResponse = httpClient.send(checksRequest, HttpResponse.BodyHandlers.ofString());

                if (checksResponse.statusCode() == 200) {
                    JsonNode checksNode = objectMapper.readTree(checksResponse.body());
                    JsonNode checkRunsList = checksNode.path("check_runs");
                    
                    if (checkRunsList.isArray() && checkRunsList.size() > 0) {
                        boolean allCompleted = true;
                        boolean allPassed = true;
                        boolean anyFailed = false;
                        
                        List<CICheckStatus.CheckRun> checksList = new ArrayList<>();
                        for (JsonNode runNode : checkRunsList) {
                            String name = runNode.path("name").asText();
                            String runStatus = runNode.path("status").asText();
                            String conclusion = runNode.path("conclusion").asText();
                            
                            checksList.add(new CICheckStatus.CheckRun(name, runStatus, conclusion));
                            
                            if (!"completed".equals(runStatus)) {
                                allCompleted = false;
                            }
                            if (!"success".equals(conclusion)) {
                                allPassed = false;
                            }
                            if ("failure".equals(conclusion)) {
                                anyFailed = true;
                            }
                        }

                        if (allCompleted) {
                            if (allPassed) {
                                return CICheckStatus.builder().status("success").conclusion("success").checks(checksList).build();
                            } else if (anyFailed) {
                                return CICheckStatus.builder().status("failure").conclusion("failure").checks(checksList).build();
                            }
                        }
                    }
                }

                Thread.sleep(pollIntervalMs);
            } catch (Exception e) {
                log.error("[ERROR] Error checking CI status: {}", e.getMessage());
                try { Thread.sleep(pollIntervalMs); } catch (InterruptedException ignored) {}
            }
        }

        return CICheckStatus.builder().status("pending").conclusion("timeout").build();
    }

    public void markPRReadyForReview(int prNumber) throws Exception {
        String url = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/pulls/" + prNumber;
        Map<String, Boolean> body = Map.of("draft", false);
        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = newRequestBuilder(url)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to mark PR ready: " + response.body());
        }
    }

    public void addPRComment(int prNumber, String comment) throws Exception {
        String url = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/issues/" + prNumber + "/comments";
        Map<String, String> body = Map.of("body", comment);
        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = newRequestBuilder(url)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new RuntimeException("Failed to add PR comment: " + response.body());
        }
    }
}
