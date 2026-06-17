package com.automata.service.ai;

import org.springframework.stereotype.Service;
import java.util.stream.Collectors;

@Service
public class MockAiService implements AiService {
    @Override
    public String generateCode(String prompt) {
        String details = prompt.lines()
                .limit(3)
                .map(line -> "// " + line)
                .collect(Collectors.joining("\n"));

        return "File: lib/fix.ts\n\n" +
               "// Mock AI-generated code fix\n" +
               "// This is a placeholder response when using development mode\n\n" +
               "function fixIssue() {\n" +
               "  // TODO: Implement the actual fix based on the Jira ticket\n" +
               "  // Issue description from prompt:\n" +
               "  " + details + "\n" +
               "  \n" +
               "  console.log('Fix implementation needed');\n" +
               "  return true;\n" +
               "}\n\n" +
               "export default fixIssue;";
    }
}
