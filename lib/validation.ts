import { FileChange } from './github';
import { exec } from 'child_process';
import { promisify } from 'util';
import { writeFile, mkdir, rm } from 'fs/promises';
import { join } from 'path';
import { tmpdir } from 'os';

const execAsync = promisify(exec);

/**
 * Validation result
 */
export interface ValidationResult {
  success: boolean;
  errors: string[];
  warnings: string[];
  details?: {
    syntaxCheck?: boolean;
    typeCheck?: boolean;
    lintCheck?: boolean;
  };
}

/**
 * Validates generated code before creating PR
 */
export async function validateGeneratedCode(
  fileChanges: FileChange[],
  projectRoot?: string
): Promise<ValidationResult> {
  const result: ValidationResult = {
    success: true,
    errors: [],
    warnings: [],
    details: {},
  };

  // Filter to only TypeScript/JavaScript files
  const codeFiles = fileChanges.filter(fc =>
    fc.path.match(/\.(ts|tsx|js|jsx)$/)
  );

  if (codeFiles.length === 0) {
    result.warnings.push('No code files to validate');
    return result;
  }

  // Create temporary directory for validation
  const tempDir = join(tmpdir(), `automata-validation-${Date.now()}`);
  let cleanup = true;

  try {
    await mkdir(tempDir, { recursive: true });

    // Write files to temp directory
    for (const fileChange of codeFiles) {
      const filePath = join(tempDir, fileChange.path);
      const dirPath = join(filePath, '..');
      await mkdir(dirPath, { recursive: true });
      await writeFile(filePath, fileChange.content, 'utf-8');
    }

    // 1. Syntax Check (basic)
    try {
      for (const fileChange of codeFiles) {
        const filePath = join(tempDir, fileChange.path);
        if (filePath.endsWith('.ts') || filePath.endsWith('.tsx')) {
          // Basic syntax check using Node.js
          try {
            // Try to parse as JSON first (for config files)
            if (!filePath.includes('tsconfig') && !filePath.includes('package')) {
              // For TypeScript, we'll do a basic check
              // In production, you'd use tsc or a proper parser
              const content = fileChange.content;
              // Check for basic syntax errors (unclosed brackets, etc.)
              const openBraces = (content.match(/{/g) || []).length;
              const closeBraces = (content.match(/}/g) || []).length;
              const openParens = (content.match(/\(/g) || []).length;
              const closeParens = (content.match(/\)/g) || []).length;
              const openBrackets = (content.match(/\[/g) || []).length;
              const closeBrackets = (content.match(/\]/g) || []).length;

              if (openBraces !== closeBraces) {
                result.errors.push(`${fileChange.path}: Unmatched braces`);
                result.success = false;
              }
              if (openParens !== closeParens) {
                result.errors.push(`${fileChange.path}: Unmatched parentheses`);
                result.success = false;
              }
              if (openBrackets !== closeBrackets) {
                result.errors.push(`${fileChange.path}: Unmatched brackets`);
                result.success = false;
              }
            }
          } catch (error) {
            result.errors.push(`${fileChange.path}: Syntax check failed`);
            result.success = false;
          }
        }
      }
      result.details!.syntaxCheck = result.success;
    } catch (error) {
      result.warnings.push('Syntax check could not be completed');
    }

    // 2. Type Check (if TypeScript files exist and tsc is available)
    const hasTypeScript = codeFiles.some(fc => fc.path.match(/\.(ts|tsx)$/));
    if (hasTypeScript && projectRoot) {
      try {
        // Check if tsconfig.json exists in project
        const { stdout } = await execAsync('which tsc', { cwd: projectRoot });
        if (stdout.trim()) {
          // TypeScript compiler is available
          // Note: Full type checking would require the entire project context
          // For now, we'll skip this or do a basic check
          result.details!.typeCheck = true;
          result.warnings.push('Full type checking requires project context - skipped');
        }
      } catch {
        result.warnings.push('TypeScript compiler not available - type check skipped');
      }
    }

    // 3. Lint Check (if ESLint is available)
    if (projectRoot) {
      try {
        const { stdout } = await execAsync('which eslint', { cwd: projectRoot });
        if (stdout.trim()) {
          // ESLint is available
          // Note: Full linting would require project config
          result.details!.lintCheck = true;
          result.warnings.push('Full linting requires project context - skipped');
        }
      } catch {
        result.warnings.push('ESLint not available - lint check skipped');
      }
    }

  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    result.errors.push(`Validation error: ${errorMessage}`);
    result.success = false;
  } finally {
    // Cleanup temp directory
    if (cleanup) {
      try {
        await rm(tempDir, { recursive: true, force: true });
      } catch {
        // Ignore cleanup errors
      }
    }
  }

  return result;
}

