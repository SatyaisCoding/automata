import { JiraTicket } from '@/types/ticket';

/**
 * Represents a file change to be committed
 */
export interface FileChange {
  path: string;
  content: string;
  sha?: string; // Existing file SHA for updates
}

/**
 * GitHub API response for a reference (branch)
 */
interface GitHubRef {
  ref: string;
  object: {
    sha: string;
  };
}

/**
 * GitHub API response for a file
 */
interface GitHubFileContent {
  sha: string;
  content: string;
  encoding: string;
}

/**
 * GitHub API response for creating a PR
 */
interface GitHubPRResponse {
  html_url: string;
  number: number;
  title: string;
}

/**
 * Gets GitHub API headers with authentication
 */
function getGitHubHeaders(): HeadersInit {
  const token = process.env.GITHUB_TOKEN;
  if (!token) {
    throw new Error('GITHUB_TOKEN environment variable is not set');
  }

  // Support both classic tokens (ghp_...) and fine-grained tokens (github_pat_...)
  // Fine-grained tokens use Bearer, classic tokens use token
  const authHeader = token.startsWith('github_pat_') 
    ? `Bearer ${token}` 
    : `token ${token}`;

  return {
    'Authorization': authHeader,
    'Accept': 'application/vnd.github.v3+json',
    'Content-Type': 'application/json',
  };
}

/**
 * Gets repository owner and name from environment
 */
function getRepoInfo(): { owner: string; repo: string } {
  const owner = process.env.GITHUB_OWNER;
  const repo = process.env.GITHUB_REPO;

  if (!owner || !repo) {
    throw new Error('GITHUB_OWNER and GITHUB_REPO environment variables must be set');
  }

  return { owner, repo };
}

/**
 * Gets the default branch SHA
 */
async function getDefaultBranchSha(): Promise<string> {
  const { owner, repo } = getRepoInfo();
  const defaultBranch = process.env.GITHUB_DEFAULT_BRANCH || 'main';

  const response = await fetch(
    `https://api.github.com/repos/${owner}/${repo}/git/ref/heads/${defaultBranch}`,
    {
      headers: getGitHubHeaders(),
    }
  );

  if (!response.ok) {
    throw new Error(`Failed to get default branch: ${response.statusText}`);
  }

  const data: GitHubRef = await response.json();
  return data.object.sha;
}

/**
 * Creates a new branch from the default branch
 */
export async function createBranch(branchName: string): Promise<string> {
  const { owner, repo } = getRepoInfo();
  const defaultBranchSha = await getDefaultBranchSha();

  const response = await fetch(
    `https://api.github.com/repos/${owner}/${repo}/git/refs`,
    {
      method: 'POST',
      headers: getGitHubHeaders(),
      body: JSON.stringify({
        ref: `refs/heads/${branchName}`,
        sha: defaultBranchSha,
      }),
    }
  );

  if (!response.ok) {
    const errorText = await response.text();
    // Branch might already exist, try to get it
    if (response.status === 422) {
      const existingRef = await fetch(
        `https://api.github.com/repos/${owner}/${repo}/git/ref/heads/${branchName}`,
        { headers: getGitHubHeaders() }
      );
      if (existingRef.ok) {
        const refData: GitHubRef = await existingRef.json();
        return refData.object.sha;
      }
    }
    throw new Error(`Failed to create branch: ${errorText}`);
  }

  const data: GitHubRef = await response.json();
  return data.object.sha;
}

/**
 * Gets file SHA if file exists
 */
async function getFileSha(branch: string, path: string): Promise<string | null> {
  const { owner, repo } = getRepoInfo();

  try {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/contents/${encodeURIComponent(path)}?ref=${branch}`,
      {
        headers: getGitHubHeaders(),
      }
    );

    if (response.status === 404) {
      return null; // File doesn't exist
    }

    if (!response.ok) {
      return null; // Error, treat as new file
    }

    const data: GitHubFileContent = await response.json();
    return data.sha;
  } catch {
    return null;
  }
}

/**
 * Creates or updates a file in the repository
 */
async function createOrUpdateFile(
  branch: string,
  fileChange: FileChange
): Promise<string> {
  const { owner, repo } = getRepoInfo();
  const sha = await getFileSha(branch, fileChange.path);

  const content = Buffer.from(fileChange.content).toString('base64');

  const response = await fetch(
    `https://api.github.com/repos/${owner}/${repo}/contents/${encodeURIComponent(fileChange.path)}`,
    {
      method: 'PUT',
      headers: getGitHubHeaders(),
      body: JSON.stringify({
        message: `Update ${fileChange.path}`,
        content: content,
        branch: branch,
        sha: sha || undefined,
      }),
    }
  );

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Failed to update file ${fileChange.path}: ${errorText}`);
  }

  const data = await response.json();
  return data.commit.sha;
}

/**
 * Parses AI-generated code to extract file changes
 * Assumes format: File: <path>\n<code>\n\n or similar patterns
 */
export function parseAICodeOutput(aiOutput: string): FileChange[] {
  const changes: FileChange[] = [];
  const lines = aiOutput.split('\n');
  
  let currentPath: string | null = null;
  let currentContent: string[] = [];
  
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    
    // Look for file path indicators
    if (line.match(/^File:\s*(.+)$/i) || line.match(/^```(\w+)?:?(.+)$/)) {
      // Save previous file if exists
      if (currentPath && currentContent.length > 0) {
        changes.push({
          path: currentPath.trim(),
          content: currentContent.join('\n'),
        });
      }
      
      // Extract path
      const pathMatch = line.match(/^File:\s*(.+)$/i) || line.match(/^```\w*:?(.+)$/);
      if (pathMatch) {
        currentPath = pathMatch[1].trim();
        currentContent = [];
      }
      continue;
    }
    
    // Skip code block markers
    if (line.trim() === '```' || line.trim().startsWith('```')) {
      continue;
    }
    
    // Collect content
    if (currentPath) {
      currentContent.push(line);
    } else {
      // If no file path found yet, try to infer from common patterns
      // Look for import/require statements to guess file path
      if (line.includes('import') || line.includes('export') || line.includes('function')) {
        // Default to a common file if no path specified
        if (changes.length === 0 && currentContent.length === 0) {
          currentPath = 'lib/fix.ts'; // Default path
        }
        if (currentPath) {
          currentContent.push(line);
        }
      }
    }
  }
  
  // Save last file
  if (currentPath && currentContent.length > 0) {
    changes.push({
      path: currentPath.trim(),
      content: currentContent.join('\n'),
    });
  }
  
  // If no structured format found, treat entire output as a single file
  if (changes.length === 0 && aiOutput.trim().length > 0) {
    // Try to extract file path from comments or use default
    const defaultPath = 'lib/ai-fix.ts';
    changes.push({
      path: defaultPath,
      content: aiOutput.trim(),
    });
  }
  
  // Validate file paths
  const validChanges = changes.filter(change => {
    const path = change.path;
    // Reject paths with .. or absolute paths
    if (path.includes('..') || path.startsWith('/')) {
      console.warn(`Rejected invalid file path: ${path}`);
      return false;
    }
    // Only allow common code file extensions
    if (!path.match(/\.(ts|tsx|js|jsx|json|md)$/)) {
      console.warn(`Rejected file with unsupported extension: ${path}`);
      return false;
    }
    return true;
  });
  
  return validChanges;
}

/**
 * Commits file changes to a branch
 */
export async function commitChanges(
  branch: string,
  fileChanges: FileChange[],
  commitMessage: string
): Promise<string> {
  if (fileChanges.length === 0) {
    throw new Error('No file changes to commit');
  }

  let lastCommitSha: string | undefined;

  for (const fileChange of fileChanges) {
    const commitSha = await createOrUpdateFile(branch, fileChange);
    lastCommitSha = commitSha;
  }

  if (!lastCommitSha) {
    throw new Error('Failed to commit changes');
  }

  return lastCommitSha;
}

/**
 * Creates a Pull Request
 */
export async function createPullRequest(
  branch: string,
  ticket: JiraTicket,
  fileChanges: FileChange[],
  aiSummary?: string
): Promise<GitHubPRResponse> {
  const { owner, repo } = getRepoInfo();
  const defaultBranch = process.env.GITHUB_DEFAULT_BRANCH || 'main';

  const modifiedFiles = fileChanges.map(fc => `- \`${fc.path}\``).join('\n');

  const prBody = `## Jira Ticket
**Key:** ${ticket.key}
**Summary:** ${ticket.summary}
**Priority:** ${ticket.priority || 'Not specified'}

## Description
${ticket.description}

${aiSummary ? `## AI-Generated Summary\n${aiSummary}\n\n` : ''}## Modified Files
${modifiedFiles}

---

**Note:** This PR was generated by Automata and requires human review.

**⚠️ Please review all changes before merging.**`;

  const response = await fetch(
    `https://api.github.com/repos/${owner}/${repo}/pulls`,
    {
      method: 'POST',
      headers: getGitHubHeaders(),
      body: JSON.stringify({
        title: `Fix: ${ticket.key} – ${ticket.summary}`,
        body: prBody,
        head: branch,
        base: defaultBranch,
      }),
    }
  );

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Failed to create pull request: ${errorText}`);
  }

  return await response.json();
}

/**
 * Main function to create PR from AI-generated code
 */
export async function createPRFromAICode(
  ticket: JiraTicket,
  aiGeneratedCode: string,
  aiSummary?: string
): Promise<{ prUrl: string; prNumber: number }> {
  try {
    // Parse AI output to extract file changes
    console.log('Parsing AI output for file changes...');
    const fileChanges = parseAICodeOutput(aiGeneratedCode);
    
    console.log(`Found ${fileChanges.length} file change(s):`, fileChanges.map(fc => fc.path));
    
    if (fileChanges.length === 0) {
      throw new Error('No valid file changes found in AI output');
    }

    // Create branch
    const branchName = `automata/${ticket.key}-ai-fix`;
    console.log(`Creating branch: ${branchName}`);
    const branchSha = await createBranch(branchName);
    console.log(`Branch created with SHA: ${branchSha}`);

    // Commit changes
    const commitMessage = `Automata AI fix for ${ticket.key}`;
    console.log(`Committing ${fileChanges.length} file(s) to branch: ${branchName}`);
    await commitChanges(branchName, fileChanges, commitMessage);
    console.log('Changes committed successfully');

    // Create PR
    console.log(`Creating pull request for branch: ${branchName}`);
    const pr = await createPullRequest(branchName, ticket, fileChanges, aiSummary);
    console.log(`PR created: #${pr.number}`);

    return {
      prUrl: pr.html_url,
      prNumber: pr.number,
    };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    const errorStack = error instanceof Error ? error.stack : undefined;
    console.error('❌ Error creating PR from AI code:', errorMessage);
    if (errorStack) {
      console.error('Stack trace:', errorStack);
    }
    throw new Error(`Failed to create PR: ${errorMessage}`);
  }
}

