import crypto from 'crypto';

/**
 * Audit event types
 */
export enum AuditEventType {
  JIRA_TICKET_RECEIVED = 'jira_ticket_received',
  CODE_CONTEXT_FETCHED = 'code_context_fetched',
  PROMPT_SENT_TO_AI = 'prompt_sent_to_ai',
  AI_OUTPUT_RECEIVED = 'ai_output_received',
  GITHUB_BRANCH_CREATED = 'github_branch_created',
  COMMIT_CREATED = 'commit_created',
  PULL_REQUEST_CREATED = 'pull_request_created',
  SAFETY_GUARD_BLOCKED = 'safety_guard_blocked',
  OPERATION_FAILED = 'operation_failed',
}

/**
 * Audit log entry structure
 */
export interface AuditLogEntry {
  ticketKey: string;
  timestamp: string;
  eventType: AuditEventType;
  status: 'success' | 'failed';
  metadata?: Record<string, unknown>;
}

/**
 * Generates a hash of sensitive data (for audit logging)
 */
function hashData(data: string): string {
  return crypto.createHash('sha256').update(data).digest('hex').substring(0, 16);
}

/**
 * Logs an audit event to console (structured JSON format)
 */
export function logAuditEvent(
  ticketKey: string,
  eventType: AuditEventType,
  status: 'success' | 'failed',
  metadata?: Record<string, unknown>
): void {
  const entry: AuditLogEntry = {
    ticketKey,
    timestamp: new Date().toISOString(),
    eventType,
    status,
    metadata: metadata || {},
  };

  // Log as structured JSON for easy parsing
  console.log(JSON.stringify({
    type: 'AUDIT_LOG',
    ...entry,
  }));
}

/**
 * Logs Jira ticket received event
 */
export function logJiraTicketReceived(ticketKey: string, summary: string, priority?: string): void {
  logAuditEvent(
    ticketKey,
    AuditEventType.JIRA_TICKET_RECEIVED,
    'success',
    {
      summary,
      priority: priority || 'not_specified',
    }
  );
}

/**
 * Logs code context fetched event
 */
export function logCodeContextFetched(ticketKey: string, fileCount: number): void {
  logAuditEvent(
    ticketKey,
    AuditEventType.CODE_CONTEXT_FETCHED,
    'success',
    {
      fileCount,
    }
  );
}

/**
 * Logs prompt sent to AI (stores hash only)
 */
export function logPromptSentToAI(ticketKey: string, prompt: string): void {
  const promptHash = hashData(prompt);
  logAuditEvent(
    ticketKey,
    AuditEventType.PROMPT_SENT_TO_AI,
    'success',
    {
      promptHash,
      promptLength: prompt.length,
    }
  );
}

/**
 * Logs AI output received (stores hash only)
 */
export function logAIOutputReceived(ticketKey: string, output: string): void {
  const outputHash = hashData(output);
  logAuditEvent(
    ticketKey,
    AuditEventType.AI_OUTPUT_RECEIVED,
    'success',
    {
      outputHash,
      outputLength: output.length,
    }
  );
}

/**
 * Logs GitHub branch created event
 */
export function logGitHubBranchCreated(ticketKey: string, branchName: string, sha: string): void {
  logAuditEvent(
    ticketKey,
    AuditEventType.GITHUB_BRANCH_CREATED,
    'success',
    {
      branchName,
      sha,
    }
  );
}

/**
 * Logs commit created event
 */
export function logCommitCreated(ticketKey: string, commitSha: string, fileCount: number): void {
  logAuditEvent(
    ticketKey,
    AuditEventType.COMMIT_CREATED,
    'success',
    {
      commitSha,
      fileCount,
    }
  );
}

/**
 * Logs pull request created event
 */
export function logPullRequestCreated(ticketKey: string, prUrl: string, prNumber: number): void {
  logAuditEvent(
    ticketKey,
    AuditEventType.PULL_REQUEST_CREATED,
    'success',
    {
      prUrl,
      prNumber,
    }
  );
}

/**
 * Logs safety guard blocked event
 */
export function logSafetyGuardBlocked(ticketKey: string, reason: string, details?: Record<string, unknown>): void {
  logAuditEvent(
    ticketKey,
    AuditEventType.SAFETY_GUARD_BLOCKED,
    'failed',
    {
      reason,
      ...details,
    }
  );
}

/**
 * Logs operation failed event
 */
export function logOperationFailed(ticketKey: string, eventType: AuditEventType, error: string): void {
  logAuditEvent(
    ticketKey,
    AuditEventType.OPERATION_FAILED,
    'failed',
    {
      failedEventType: eventType,
      error,
    }
  );
}

