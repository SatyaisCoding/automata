import { JiraTicket } from '@/types/ticket';
import { CodeContext } from './context';
import { ExtractedErrorInfo, formatErrorInfoForPrompt } from './error-extractor';

/**
 * Builds a prompt for code generation based on Jira ticket and code context
 */
export function buildPrompt(
  ticket: JiraTicket,
  codeContext: CodeContext[] = [],
  errorInfo?: ExtractedErrorInfo
): string {
  const priorityText = ticket.priority ? `Priority: ${ticket.priority}` : 'Priority: Not specified';

  let contextSection = '';
  if (codeContext.length > 0) {
    contextSection = '\n---\nRelevant Code Context:\n\n';
    for (const context of codeContext) {
      contextSection += `File: ${context.filename}\n${context.content}\n\n`;
    }
    contextSection += '---\n';
  }

  let errorSection = '';
  if (errorInfo) {
    errorSection = formatErrorInfoForPrompt(errorInfo);
  }

  return `You are a senior full-stack engineer. A bug has been reported in Jira.

Issue Key: ${ticket.key}
Summary: ${ticket.summary}
${priorityText}

Description:
${ticket.description}
${errorSection}${contextSection}
Please provide a FIX in code for this bug. 

Requirements:
- Do not change public APIs
- Output only code (no explanations)
- Provide a complete, production-ready solution
- Use the provided code context to understand the codebase structure
- Address the specific error information provided above

Code fix:`;
}

