import { FileChange } from './github';

/**
 * Blocked path patterns (case-insensitive)
 */
const BLOCKED_PATTERNS = [
  '.github/',
  'infra/',
  'auth/',
  'secrets',
  '.env',
  'package-lock.json',
  'yarn.lock',
  'pnpm-lock.yaml',
];

/**
 * Maximum number of files that can be changed in a single PR
 */
const MAX_FILE_CHANGES = 3;

/**
 * Safety guard validation result
 */
export interface GuardResult {
  allowed: boolean;
  reason?: string;
  blockedFiles?: string[];
}

/**
 * Validates file paths against blocked patterns
 */
function isPathBlocked(path: string): boolean {
  const lowerPath = path.toLowerCase();
  return BLOCKED_PATTERNS.some(pattern => lowerPath.includes(pattern.toLowerCase()));
}

/**
 * Validates file changes against safety rules
 */
export function validateFileChanges(fileChanges: FileChange[]): GuardResult {
  // Check file count limit
  if (fileChanges.length > MAX_FILE_CHANGES) {
    return {
      allowed: false,
      reason: `Exceeds maximum file change limit of ${MAX_FILE_CHANGES}`,
      blockedFiles: fileChanges.map(fc => fc.path),
    };
  }

  // Check for blocked paths
  const blockedFiles = fileChanges.filter(fc => isPathBlocked(fc.path));
  if (blockedFiles.length > 0) {
    return {
      allowed: false,
      reason: 'Contains files in blocked paths',
      blockedFiles: blockedFiles.map(fc => fc.path),
    };
  }

  // Validate file path format
  const invalidPaths = fileChanges.filter(fc => {
    const path = fc.path;
    // Reject paths with .. or absolute paths
    if (path.includes('..') || path.startsWith('/')) {
      return true;
    }
    // Only allow common code file extensions
    if (!path.match(/\.(ts|tsx|js|jsx|json|md|css|scss|html)$/)) {
      return true;
    }
    return false;
  });

  if (invalidPaths.length > 0) {
    return {
      allowed: false,
      reason: 'Contains invalid file paths',
      blockedFiles: invalidPaths.map(fc => fc.path),
    };
  }

  return {
    allowed: true,
  };
}

/**
 * Validates that file changes are safe before committing
 */
export function guardFileChanges(fileChanges: FileChange[]): void {
  const result = validateFileChanges(fileChanges);
  
  if (!result.allowed) {
    throw new Error(
      `Safety guard blocked: ${result.reason}. ` +
      (result.blockedFiles ? `Blocked files: ${result.blockedFiles.join(', ')}` : '')
    );
  }
}

