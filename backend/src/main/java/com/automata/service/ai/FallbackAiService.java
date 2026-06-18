package com.automata.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class FallbackAiService {

    @Value("${automata.use-mock-ai:false}")
    private boolean useMockAi;

    @Value("${automata.gemini-api-key:}")
    private String geminiApiKey;

    @Value("${automata.groq-api-key:}")
    private String groqApiKey;

    @Autowired
    private MockAiService mockAiService;

    @Autowired
    private GeminiAiService geminiAiService;

    @Autowired
    private GroqAiService groqAiService;

    public String generateCode(String prompt, String preferredModel) {
        if (useMockAi) {
            log.info("[WARNING] Using MOCK AI mode (development only)");
            return mockAiService.generateCode(prompt);
        }

        String modelPreference = preferredModel != null ? preferredModel.toLowerCase() : "gemini";
        List<Attempt> attempts = new ArrayList<>();

        if ("groq".equals(modelPreference)) {
            if (hasKey(groqApiKey)) {
                attempts.add(new Attempt("Groq", () -> groqAiService.generateCode(prompt)));
            }
            if (hasKey(geminiApiKey)) {
                attempts.add(new Attempt("Gemini", () -> geminiAiService.generateCode(prompt)));
            }
        } else {
            // Default: Gemini first
            if (hasKey(geminiApiKey)) {
                attempts.add(new Attempt("Gemini", () -> geminiAiService.generateCode(prompt)));
            }
            if (hasKey(groqApiKey)) {
                attempts.add(new Attempt("Groq", () -> groqAiService.generateCode(prompt)));
            }
        }

        if (attempts.isEmpty()) {
            throw new IllegalStateException("No AI model keys are configured. Please set GEMINI_API_KEY or GROQ_API_KEY.");
        }

        Exception lastError = null;
        for (Attempt attempt : attempts) {
            try {
                return attempt.action.run();
            } catch (Exception e) {
                lastError = e;
                log.warn("[WARNING] Model {} failed or was exhausted: {}", attempt.name, e.getMessage());
                log.info("[INFO] Attempting next fallback model...");
            }
        }

        throw new RuntimeException("All configured AI models failed to generate code. Last error: " + 
                (lastError != null ? lastError.getMessage() : "Unknown error"), lastError);
    }

    private boolean hasKey(String key) {
        return key != null && !key.trim().isEmpty() && !key.startsWith("your_");
    }

    private static class Attempt {
        String name;
        AiAction action;

        Attempt(String name, AiAction action) {
            this.name = name;
            this.action = action;
        }
    }

    @FunctionalInterface
    private interface AiAction {
        String run() throws Exception;
    }
}
