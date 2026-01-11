import { JiraTicket } from '@/types/ticket';

/**
 * Represents a file with its content from the repository
 */
export interface CodeContext {
  filename: string;
  content: string;
}

/**
 * GitHub API file entry from tree/blob listing
 */
interface GitHubFile {
  path: string;
  type: 'blob' | 'tree';
  sha?: string;
}

/**
 * GitHub API tree response
 */
interface GitHubTreeResponse {
  tree: GitHubFile[];
  truncated?: boolean;
}

/**
 * Extracts keywords from Jira ticket for file matching
 */
function extractKeywords(ticket: JiraTicket): string[] {
  const text = `${ticket.summary} ${ticket.description}`.toLowerCase();
  const words = text.split(/\s+/);
  
  // Filter out common stop words and short words
  const stopWords = new Set(['the', 'a', 'an', 'and', 'or', 'but', 'in', 'on', 'at', 'to', 'for', 'of', 'with', 'by', 'is', 'are', 'was', 'were', 'be', 'been', 'have', 'has', 'had', 'do', 'does', 'did', 'will', 'would', 'should', 'could', 'may', 'might', 'must', 'can', 'this', 'that', 'these', 'those', 'i', 'you', 'he', 'she', 'it', 'we', 'they', 'what', 'which', 'who', 'when', 'where', 'why', 'how', 'all', 'each', 'every', 'both', 'few', 'more', 'most', 'other', 'some', 'such', 'no', 'nor', 'not', 'only', 'own', 'same', 'so', 'than', 'too', 'very', 'just', 'now']);
  
  const keywords = words
    .filter(word => word.length > 3 && !stopWords.has(word))
    .filter(word => /^[a-z]+$/.test(word)) // Only alphabetic
    .slice(0, 20); // Limit to top 20 keywords
  
  return [...new Set(keywords)]; // Remove duplicates
}

/**
 * Checks if a file path should be ignored
 */
function shouldIgnoreFile(path: string): boolean {
  const ignorePatterns = [
    'node_modules',
    'dist',
    'build',
    '.next',
    '.env',
    '.git',
    'coverage',
    '.DS_Store',
  ];
  
  return ignorePatterns.some(pattern => path.includes(pattern));
}

/**
 * Checks if a file has a relevant extension
 */
function hasRelevantExtension(path: string): boolean {
  const extensions = ['.ts', '.tsx', '.js', '.jsx'];
  return extensions.some(ext => path.endsWith(ext));
}

/**
 * Scores a file path based on keyword matches
 */
function scoreFile(path: string, keywords: string[]): number {
  const lowerPath = path.toLowerCase();
  let score = 0;
  
  for (const keyword of keywords) {
    if (lowerPath.includes(keyword)) {
      score += 10;
    }
    
    // Bonus for filename matches
    const filename = path.split('/').pop()?.toLowerCase() || '';
    if (filename.includes(keyword)) {
      score += 20;
    }
  }
  
  return score;
}

/**
 * Fetches repository file tree from GitHub
 */
async function fetchRepositoryTree(): Promise<GitHubFile[]> {
  const token = process.env.GITHUB_TOKEN;
  const owner = process.env.GITHUB_OWNER;
  const repo = process.env.GITHUB_REPO;
  
  if (!token || !owner || !repo) {
    throw new Error('GitHub credentials not configured (GITHUB_TOKEN, GITHUB_OWNER, GITHUB_REPO)');
  }
  
  // Get default branch
  const repoResponse = await fetch(`https://api.github.com/repos/${owner}/${repo}`, {
    headers: {
      'Authorization': `token ${token}`,
      'Accept': 'application/vnd.github.v3+json',
    },
  });
  
  if (!repoResponse.ok) {
    throw new Error(`Failed to fetch repository: ${repoResponse.statusText}`);
  }
  
  const repoData = await repoResponse.json();
  const defaultBranch = repoData.default_branch;
  
  // Get tree recursively
  const treeResponse = await fetch(
    `https://api.github.com/repos/${owner}/${repo}/git/trees/${defaultBranch}?recursive=1`,
    {
      headers: {
        'Authorization': `token ${token}`,
        'Accept': 'application/vnd.github.v3+json',
      },
    }
  );
  
  if (!treeResponse.ok) {
    throw new Error(`Failed to fetch repository tree: ${treeResponse.statusText}`);
  }
  
  const treeData: GitHubTreeResponse = await treeResponse.json();
  return treeData.tree;
}

/**
 * Fetches file content from GitHub
 */
async function fetchFileContent(path: string): Promise<string> {
  const token = process.env.GITHUB_TOKEN;
  const owner = process.env.GITHUB_OWNER;
  const repo = process.env.GITHUB_REPO;
  
  if (!token || !owner || !repo) {
    throw new Error('GitHub credentials not configured');
  }
  
  const response = await fetch(
    `https://api.github.com/repos/${owner}/${repo}/contents/${encodeURIComponent(path)}`,
    {
      headers: {
        'Authorization': `token ${token}`,
        'Accept': 'application/vnd.github.v3+json',
      },
    }
  );
  
  if (!response.ok) {
    throw new Error(`Failed to fetch file ${path}: ${response.statusText}`);
  }
  
  const data = await response.json();
  
  if (data.encoding === 'base64' && data.content) {
    const content = Buffer.from(data.content, 'base64').toString('utf-8');
    // Limit to ~8000 characters per file (safe token size)
    return content.length > 8000 ? content.substring(0, 8000) + '\n// ... (truncated)' : content;
  }
  
  throw new Error(`Unexpected file encoding for ${path}`);
}

/**
 * Gets relevant code context for a Jira ticket
 */
export async function getCodeContext(ticket: JiraTicket): Promise<CodeContext[]> {
  try {
    const keywords = extractKeywords(ticket);
    console.log('Extracted keywords:', keywords);
    
    const allFiles = await fetchRepositoryTree();
    console.log(`Found ${allFiles.length} files in repository`);
    
    // Filter relevant files
    const relevantFiles = allFiles
      .filter(file => file.type === 'blob')
      .filter(file => hasRelevantExtension(file.path))
      .filter(file => !shouldIgnoreFile(file.path))
      .map(file => ({
        path: file.path,
        score: scoreFile(file.path, keywords),
      }))
      .sort((a, b) => b.score - a.score)
      .slice(0, 3); // Top 3 files
    
    console.log('Selected files:', relevantFiles.map(f => ({ path: f.path, score: f.score })));
    
    // Fetch content for selected files
    const contexts: CodeContext[] = [];
    for (const file of relevantFiles) {
      try {
        const content = await fetchFileContent(file.path);
        contexts.push({
          filename: file.path,
          content,
        });
      } catch (error) {
        console.error(`Failed to fetch content for ${file.path}:`, error);
        // Continue with other files
      }
    }
    
    return contexts;
  } catch (error) {
    console.error('Error fetching code context:', error);
    // Return empty array on error - AI will work without context
    return [];
  }
}

