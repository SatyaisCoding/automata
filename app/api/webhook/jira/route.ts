import { NextRequest, NextResponse } from 'next/server';
import { JiraTicket } from '@/types/ticket';
import { buildPrompt } from '@/lib/prompt';
import { generateCode } from '@/lib/ai';
import { getCodeContext, CodeContext } from '@/lib/context';
import { createPRFromAICode } from '@/lib/github';
import {
  logJiraTicketReceived,
  logCodeContextFetched,
  logPromptSentToAI,
  logAIOutputReceived,
  logGitHubBranchCreated,
  logCommitCreated,
  logPullRequestCreated,
  logSafetyGuardBlocked,
  logOperationFailed,
  AuditEventType,
} from '@/lib/audit';
import { guardFileChanges, validateFileChanges } from '@/lib/guards';
import { parseAICodeOutput } from '@/lib/github';
import { extractErrorInfo } from '@/lib/error-extractor';
import { validateGeneratedCode } from '@/lib/validation';
import { waitForCIChecks, markPRReadyForReview, addPRComment } from '@/lib/ci-checker';

/**
 * Raw Jira webhook payload structure
 */
interface JiraWebhookPayload {
  issue?: {
    id?: string;
    key?: string;
    fields?: {
      summary?: string;
      description?: string;
      priority?: {
        name?: string;
      };
    };
  };
}

/**
 * Validates the Jira webhook payload structure
 */
function isValidJiraPayload(payload: unknown): payload is JiraWebhookPayload {
  if (!payload || typeof payload !== 'object') {
    return false;
  }

  const p = payload as Record<string, unknown>;
  if (!p.issue || typeof p.issue !== 'object') {
    return false;
  }

  const issue = p.issue as Record<string, unknown>;
  if (!issue.fields || typeof issue.fields !== 'object') {
    return false;
  }

  return true;
}

/**
 * Converts raw Jira webhook payload to JiraTicket object
 */
function convertToJiraTicket(payload: JiraWebhookPayload): JiraTicket | null {
  if (!payload.issue) {
    return null;
  }

  const issue = payload.issue;
  const fields = issue.fields;

  if (!issue.key || !fields?.summary || !fields?.description) {
    return null;
  }

  const ticket: JiraTicket = {
    id: issue.id || '',
    key: issue.key,
    summary: fields.summary,
    description: fields.description,
    priority: fields.priority?.name,
  };

  return ticket;
}

/**
 * POST handler for Jira webhook
 */
export async function POST(request: NextRequest) {
  try {
    // Parse the JSON payload
    let payload: unknown;
    try {
      payload = await request.json();
    } catch (error) {
      console.error('Failed to parse JSON payload:', error);
      return NextResponse.json(
        { error: 'Invalid JSON payload' },
        { status: 400 }
      );
    }

    // Validate the payload structure
    if (!isValidJiraPayload(payload)) {
      console.error('Invalid Jira webhook payload structure');
      return NextResponse.json(
        { error: 'Invalid webhook payload structure' },
        { status: 400 }
      );
    }

    // Convert to JiraTicket
    const ticket = convertToJiraTicket(payload);

    if (!ticket) {
      console.error('Failed to extract required fields from payload');
      return NextResponse.json(
        { error: 'Missing required fields in payload' },
        { status: 400 }
      );
    }

    // Audit log: Jira ticket received
    logJiraTicketReceived(ticket.key, ticket.summary, ticket.priority);

    // Log extracted values
    console.log('=== Jira Webhook Received ===');
    console.log('Issue ID:', ticket.id);
    console.log('Issue Key:', ticket.key);
    console.log('Summary:', ticket.summary);
    console.log('Description:', ticket.description);
    console.log('Priority:', ticket.priority || 'Not set');
    console.log('============================');

    // Extract error information from ticket description
    const errorInfo = extractErrorInfo(ticket.description);
    if (errorInfo.errorMessage || errorInfo.stackTrace) {
      console.log('Extracted error information:', {
        errorType: errorInfo.errorType,
        hasStackTrace: !!errorInfo.stackTrace,
        hasTestFailure: !!errorInfo.testFailure,
      });
    }

    // Fetch code context from repository
    let codeContext: CodeContext[] = [];
    try {
      console.log('Fetching code context from repository...');
      codeContext = await getCodeContext(ticket);
      console.log(`Retrieved ${codeContext.length} relevant file(s) from repository`);
      // Audit log: Code context fetched
      logCodeContextFetched(ticket.key, codeContext.length);
    } catch (error) {
      console.error('Error fetching code context:', error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      logOperationFailed(ticket.key, AuditEventType.CODE_CONTEXT_FETCHED, errorMessage);
      codeContext = []; // Continue without context
    }

    // Generate code using AI
    let generatedCode: string;
    try {
      const prompt = buildPrompt(ticket, codeContext, errorInfo);
      // Audit log: Prompt sent to AI (hash only)
      logPromptSentToAI(ticket.key, prompt);
      
      generatedCode = await generateCode(prompt);
      
      // Audit log: AI output received (hash only)
      logAIOutputReceived(ticket.key, generatedCode);
      
      console.log('=== AI Generated Code ===');
      console.log(generatedCode);
      console.log('=========================');
    } catch (error) {
      console.error('Error generating code:', error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      const errorStack = error instanceof Error ? error.stack : undefined;
      console.error('Error details:', { message: errorMessage, stack: errorStack });
      logOperationFailed(ticket.key, AuditEventType.AI_OUTPUT_RECEIVED, errorMessage);
      return NextResponse.json(
        { error: 'Failed to generate code', details: errorMessage },
        { status: 500 }
      );
    }

    // Apply safety guards before creating PR
    let fileChanges;
    try {
      fileChanges = parseAICodeOutput(generatedCode);
      console.log(`Parsed ${fileChanges.length} file change(s) from AI output`);
      
      // Apply safety guards
      const guardResult = validateFileChanges(fileChanges);
      if (!guardResult.allowed) {
        logSafetyGuardBlocked(ticket.key, guardResult.reason || 'Unknown reason', {
          blockedFiles: guardResult.blockedFiles,
        });
        return NextResponse.json(
          {
            error: 'Safety guard blocked',
            reason: guardResult.reason,
            blockedFiles: guardResult.blockedFiles,
          },
          { status: 403 }
        );
      }
      
      // Enforce safety guards (throws if validation fails)
      guardFileChanges(fileChanges);
      console.log('✅ Safety guards passed');
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      logSafetyGuardBlocked(ticket.key, errorMessage);
      return NextResponse.json(
        { error: 'Safety guard validation failed', details: errorMessage },
        { status: 403 }
      );
    }

    // Validate generated code (Phase 6.2: Pre-PR Validation)
    console.log('=== Validating Generated Code ===');
    try {
      const validationResult = await validateGeneratedCode(fileChanges, process.cwd());
      
      if (!validationResult.success) {
        console.error('Code validation failed:', validationResult.errors);
        logOperationFailed(ticket.key, AuditEventType.COMMIT_CREATED, `Validation failed: ${validationResult.errors.join(', ')}`);
        return NextResponse.json(
          {
            error: 'Code validation failed',
            validationErrors: validationResult.errors,
            warnings: validationResult.warnings,
          },
          { status: 400 }
        );
      }
      
      if (validationResult.warnings.length > 0) {
        console.warn('Validation warnings:', validationResult.warnings);
      }
      
      console.log('✅ Code validation passed');
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      console.error('Validation error:', errorMessage);
      // Continue with PR creation if validation system fails (don't block)
      console.warn('Validation system error - continuing with PR creation');
    }

    // Create Pull Request from AI-generated code
    let prUrl: string | undefined;
    let prNumber: number | undefined;
    let prError: string | undefined;
    let ciStatus: string | undefined;
    try {
      console.log('=== Creating Pull Request ===');
      console.log('AI Generated Code Length:', generatedCode.length);
      
      const prResult = await createPRFromAICode(ticket, generatedCode);
      prUrl = prResult.prUrl;
      prNumber = prResult.prNumber;
      
      // Audit log: Pull request created
      logPullRequestCreated(ticket.key, prUrl, prNumber);
      
      console.log(`✅ Pull Request created (draft): ${prUrl}`);
      console.log(`PR Number: #${prNumber}`);
      
      // Phase 6.3: Wait for CI checks (non-blocking)
      if (prNumber) {
        console.log('=== Waiting for CI Checks ===');
        try {
          const ciResult = await waitForCIChecks(prNumber, 60000); // Wait up to 1 minute
          
          if (ciResult.status === 'success') {
            console.log('✅ CI checks passed');
            await markPRReadyForReview(prNumber);
            await addPRComment(
              prNumber,
              '✅ **Automata CI Check**: All checks passed. PR is ready for review.'
            );
            ciStatus = 'success';
          } else if (ciResult.status === 'failure') {
            console.log('❌ CI checks failed');
            await addPRComment(
              prNumber,
              `❌ **Automata CI Check**: Some checks failed.\n\n` +
              `Checks:\n${ciResult.checks?.map(c => `- ${c.name}: ${c.conclusion || c.status}`).join('\n') || 'No check details available'}\n\n` +
              `Please review the failures before merging.`
            );
            ciStatus = 'failure';
          } else {
            console.log('⏳ CI checks still pending');
            await addPRComment(
              prNumber,
              '⏳ **Automata CI Check**: Checks are still running. This PR will remain in draft until checks complete.\n\n' +
              'The PR will be automatically marked as ready for review once all checks pass.'
            );
            ciStatus = 'pending';
          }
        } catch (ciError) {
          console.error('Error checking CI status:', ciError);
          // Don't fail the request if CI check fails
          ciStatus = 'error';
        }
      }
      
      console.log('============================');
    } catch (error) {
      console.error('❌ Error creating Pull Request:', error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      const errorStack = error instanceof Error ? error.stack : undefined;
      console.error('Error message:', errorMessage);
      if (errorStack) {
        console.error('Error stack:', errorStack);
      }
      
      // Audit log: Operation failed
      logOperationFailed(ticket.key, AuditEventType.PULL_REQUEST_CREATED, errorMessage);
      
      prError = errorMessage;
      // Log but don't fail the request - AI generation succeeded
    }

    // Return success response
    return NextResponse.json({
      status: 'ai_generated',
      pr_url: prUrl,
      pr_number: prNumber,
      pr_error: prError,
      ci_status: ciStatus,
      error_info_extracted: !!(errorInfo.errorMessage || errorInfo.stackTrace),
    });
  } catch (error) {
    console.error('Unexpected error processing webhook:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
}

