# Automata — AI-Driven Bug-to-Pull-Request Automation

Automata converts Jira tickets, stack traces, and CI failures into 
repository-aware GitHub pull requests automatically — reducing bug 
resolution effort by 40–60%.

## Tech Stack
TypeScript · Next.js · Node.js · GitHub API · RAG · LLMs

## How it works
- Jira webhooks trigger the pipeline on new bug tickets or CI failures
- RAG pipeline analyzes 100+ source files and injects the most relevant 
  modules into LLM prompts for accurate code fixes
- Human-in-the-loop workflow creates draft PRs with CI validation, 
  syntax checks, and audit logging — zero auto-merges
- Full PR lifecycle automated: branch creation → commits → PR generation

## Key Results
- 40–60% reduction in bug resolution effort
- Handles stack traces, Jira tickets, and CI failures as input triggers
- Safe by design — every fix goes through human review before merge
