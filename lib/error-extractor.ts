/**
 * Extracted error information from Jira ticket
 */
export interface ExtractedErrorInfo {
  errorMessage?: string;
  stackTrace?: string;
  testFailure?: string;
  errorType?: string;
  lineNumber?: number;
  filePath?: string;
}

/**
 * Extracts error information from Jira ticket description
 */
export function extractErrorInfo(description: string): ExtractedErrorInfo {
  const info: ExtractedErrorInfo = {};

  // Extract stack traces (common patterns)
  const stackTracePatterns = [
    /(?:Error|Exception|TypeError|ReferenceError|SyntaxError)[:\s]+([^\n]+)/i,
    /at\s+([^\s]+)\s+\(([^:]+):(\d+):(\d+)\)/g,
    /(?:Stack trace|StackTrace):\s*([\s\S]+?)(?:\n\n|\n[A-Z]|$)/i,
  ];

  for (const pattern of stackTracePatterns) {
    const matches = description.match(pattern);
    if (matches) {
      if (!info.stackTrace) {
        info.stackTrace = '';
      }
      info.stackTrace += matches[0] + '\n';
    }
  }

  // Extract error messages (common patterns)
  const errorMessagePatterns = [
    /(?:Error|Exception|Failed|Fails?):\s*([^\n]+)/i,
    /(?:Error message|Error Message):\s*([^\n]+)/i,
    /"([^"]*Error[^"]*)"|'([^']*Error[^']*)'/i,
  ];

  for (const pattern of errorMessagePatterns) {
    const match = description.match(pattern);
    if (match && !info.errorMessage) {
      info.errorMessage = match[1] || match[2] || match[0];
      break;
    }
  }

  // Extract test failures
  const testFailurePatterns = [
    /(?:Test|Spec)\s+(?:failed|failure|error)[:\s]+([^\n]+)/i,
    /(?:FAIL|FAILED|ERROR)\s+([^\n]+)/i,
    /(?:Expected|Expected:)\s+([^\n]+)/i,
    /(?:Actual|Actual:)\s+([^\n]+)/i,
  ];

  for (const pattern of testFailurePatterns) {
    const match = description.match(pattern);
    if (match && !info.testFailure) {
      info.testFailure = match[1] || match[0];
      break;
    }
  }

  // Extract file path and line number from stack traces
  const fileLinePattern = /([\/\w\-\.]+\.(?:ts|tsx|js|jsx)):(\d+)/;
  const fileMatch = description.match(fileLinePattern);
  if (fileMatch) {
    info.filePath = fileMatch[1];
    info.lineNumber = parseInt(fileMatch[2], 10);
  }

  // Extract error type
  const errorTypePattern = /(TypeError|ReferenceError|SyntaxError|Error|Exception|ValidationError|RuntimeError)/i;
  const typeMatch = description.match(errorTypePattern);
  if (typeMatch) {
    info.errorType = typeMatch[1];
  }

  return info;
}

/**
 * Formats extracted error info for AI prompt
 */
export function formatErrorInfoForPrompt(errorInfo: ExtractedErrorInfo): string {
  if (!errorInfo.errorMessage && !errorInfo.stackTrace && !errorInfo.testFailure) {
    return '';
  }

  let formatted = '\n---\nError Information:\n\n';

  if (errorInfo.errorType) {
    formatted += `Error Type: ${errorInfo.errorType}\n`;
  }

  if (errorInfo.errorMessage) {
    formatted += `Error Message: ${errorInfo.errorMessage}\n`;
  }

  if (errorInfo.filePath && errorInfo.lineNumber) {
    formatted += `Location: ${errorInfo.filePath}:${errorInfo.lineNumber}\n`;
  }

  if (errorInfo.stackTrace) {
    formatted += `\nStack Trace:\n${errorInfo.stackTrace}\n`;
  }

  if (errorInfo.testFailure) {
    formatted += `\nTest Failure:\n${errorInfo.testFailure}\n`;
  }

  formatted += '---\n';

  return formatted;
}

