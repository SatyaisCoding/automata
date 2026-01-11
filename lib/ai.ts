import { getVertexClient } from './vertex';

/**
 * Development mode: Returns mock code when USE_MOCK_AI is set to 'true'
 */
function getMockCode(prompt: string): string {
  return `// Mock AI-generated code fix
// This is a placeholder response when using development mode

function fixIssue() {
  // TODO: Implement the actual fix based on the Jira ticket
  // Issue description from prompt:
  // ${prompt.split('\n').slice(0, 3).join('\n')}
  
  console.log('Fix implementation needed');
  return true;
}

export default fixIssue;`;
}

/**
 * Generates code using Vertex AI Gemini model
 */
export async function generateCode(prompt: string): Promise<string> {
  // Check if mock mode is enabled
  if (process.env.USE_MOCK_AI === 'true') {
    console.log('⚠️  Using MOCK AI mode (development only)');
    return getMockCode(prompt);
  }

  try {
    const vertexAI = getVertexClient();
    const model = 'gemini-1.5-pro';

    const generativeModel = vertexAI.getGenerativeModel({
      model: model,
    });

    const request = {
      contents: [
        {
          role: 'user',
          parts: [{ text: prompt }],
        },
      ],
    };

    const result = await generativeModel.generateContent(request);
    const response = result.response;

    if (!response.candidates || response.candidates.length === 0) {
      throw new Error('No response generated from Vertex AI');
    }

    const candidate = response.candidates[0];
    if (!candidate.content || !candidate.content.parts || candidate.content.parts.length === 0) {
      throw new Error('Empty response from Vertex AI');
    }

    const textPart = candidate.content.parts[0];
    if (!textPart.text) {
      throw new Error('No text content in Vertex AI response');
    }

    return textPart.text;
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    
    // If billing/permission error, suggest using mock mode
    if (errorMessage.includes('BILLING_DISABLED') || errorMessage.includes('PERMISSION_DENIED')) {
      console.error('⚠️  Vertex AI error (billing/permissions):', errorMessage);
      console.log('💡 Tip: Set USE_MOCK_AI=true in .env.local to use mock mode for development');
      throw new Error(`Vertex AI requires billing to be enabled. Error: ${errorMessage}`);
    }
    
    console.error('Vertex AI error:', errorMessage);
    if (error instanceof Error && error.stack) {
      console.error('Stack trace:', error.stack);
    }
    throw new Error(`Vertex AI generation failed: ${errorMessage}`);
  }
}

