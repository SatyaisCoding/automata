import { NextRequest, NextResponse } from 'next/server';
import { JiraTicket } from '@/types/ticket';
import { buildPrompt } from '@/lib/prompt';
import { generateCode } from '@/lib/ai';
import { getCodeContext } from '@/lib/context';

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

    // Log extracted values
    console.log('=== Jira Webhook Received ===');
    console.log('Issue ID:', ticket.id);
    console.log('Issue Key:', ticket.key);
    console.log('Summary:', ticket.summary);
    console.log('Description:', ticket.description);
    console.log('Priority:', ticket.priority || 'Not set');
    console.log('============================');

    // Fetch code context from repository
    let codeContext;
    try {
      console.log('Fetching code context from repository...');
      codeContext = await getCodeContext(ticket);
      console.log(`Retrieved ${codeContext.length} relevant file(s) from repository`);
    } catch (error) {
      console.error('Error fetching code context:', error);
      codeContext = []; // Continue without context
    }

    // Generate code using AI
    try {
      const prompt = buildPrompt(ticket, codeContext);
      const generatedCode = await generateCode(prompt);
      
      console.log('=== AI Generated Code ===');
      console.log(generatedCode);
      console.log('=========================');
    } catch (error) {
      console.error('Error generating code:', error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      const errorStack = error instanceof Error ? error.stack : undefined;
      console.error('Error details:', { message: errorMessage, stack: errorStack });
      return NextResponse.json(
        { error: 'Failed to generate code', details: errorMessage },
        { status: 500 }
      );
    }

    // Return success response
    return NextResponse.json({ status: 'ai_generated' });
  } catch (error) {
    console.error('Unexpected error processing webhook:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
}

