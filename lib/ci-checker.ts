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
 * Gets GitHub API headers with authentication
 */
function getGitHubHeaders(): HeadersInit {
  const token = process.env.GITHUB_TOKEN;
  if (!token) {
    throw new Error('GITHUB_TOKEN environment variable is not set');
  }

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
 * CI check status
 */
export interface CICheckStatus {
  status: 'pending' | 'success' | 'failure' | 'error';
  conclusion?: string;
  checks?: Array<{
    name: string;
    status: string;
    conclusion?: string;
  }>;
}

/**
 * Waits for CI checks to complete on a PR
 */
export async function waitForCIChecks(
  prNumber: number,
  maxWaitTime: number = 300000, // 5 minutes
  pollInterval: number = 10000 // 10 seconds
): Promise<CICheckStatus> {
  const { owner, repo } = getRepoInfo();
  const startTime = Date.now();

  while (Date.now() - startTime < maxWaitTime) {
    try {
      const response = await fetch(
        `https://api.github.com/repos/${owner}/${repo}/pulls/${prNumber}`,
        {
          headers: getGitHubHeaders(),
        }
      );

      if (!response.ok) {
        throw new Error(`Failed to fetch PR status: ${response.statusText}`);
      }

      const pr = await response.json();

      // Check commit status
      const commitSha = pr.head.sha;
      const statusResponse = await fetch(
        `https://api.github.com/repos/${owner}/${repo}/commits/${commitSha}/status`,
        {
          headers: getGitHubHeaders(),
        }
      );

      if (statusResponse.ok) {
        const status = await statusResponse.json();
        
        if (status.state === 'success') {
          return {
            status: 'success',
            conclusion: 'success',
          };
        } else if (status.state === 'failure' || status.state === 'error') {
          return {
            status: 'failure',
            conclusion: status.state,
          };
        }
      }

      // Check check runs
      const checksResponse = await fetch(
        `https://api.github.com/repos/${owner}/${repo}/commits/${commitSha}/check-runs`,
        {
          headers: {
            ...getGitHubHeaders(),
            'Accept': 'application/vnd.github.v3+json',
          },
        }
      );

      if (checksResponse.ok) {
        const checksData = await checksResponse.json();
        const checkRuns = checksData.check_runs || [];

        if (checkRuns.length > 0) {
          const allCompleted = checkRuns.every((check: any) => check.status === 'completed');
          const allPassed = checkRuns.every(
            (check: any) => check.status === 'completed' && check.conclusion === 'success'
          );
          const anyFailed = checkRuns.some(
            (check: any) => check.status === 'completed' && check.conclusion === 'failure'
          );

          if (allCompleted) {
            if (allPassed) {
              return {
                status: 'success',
                conclusion: 'success',
                checks: checkRuns.map((check: any) => ({
                  name: check.name,
                  status: check.status,
                  conclusion: check.conclusion,
                })),
              };
            } else if (anyFailed) {
              return {
                status: 'failure',
                conclusion: 'failure',
                checks: checkRuns.map((check: any) => ({
                  name: check.name,
                  status: check.status,
                  conclusion: check.conclusion,
                })),
              };
            }
          }
        }
      }

      // Wait before next poll
      await new Promise(resolve => setTimeout(resolve, pollInterval));
    } catch (error) {
      console.error('Error checking CI status:', error);
      // Continue polling
      await new Promise(resolve => setTimeout(resolve, pollInterval));
    }
  }

  // Timeout - return pending status
  return {
    status: 'pending',
    conclusion: 'timeout',
  };
}

/**
 * Updates PR from draft to ready for review
 */
export async function markPRReadyForReview(prNumber: number): Promise<void> {
  const { owner, repo } = getRepoInfo();

  const response = await fetch(
    `https://api.github.com/repos/${owner}/${repo}/pulls/${prNumber}`,
    {
      method: 'PATCH',
      headers: getGitHubHeaders(),
      body: JSON.stringify({
        draft: false,
      }),
    }
  );

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Failed to mark PR ready: ${errorText}`);
  }
}

/**
 * Adds a comment to a PR
 */
export async function addPRComment(prNumber: number, comment: string): Promise<void> {
  const { owner, repo } = getRepoInfo();

  const response = await fetch(
    `https://api.github.com/repos/${owner}/${repo}/issues/${prNumber}/comments`,
    {
      method: 'POST',
      headers: getGitHubHeaders(),
      body: JSON.stringify({
        body: comment,
      }),
    }
  );

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Failed to add PR comment: ${errorText}`);
  }
}

