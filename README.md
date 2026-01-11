# Automata

A workflow automation engine for app integrations and event-driven tasks.

## About

Automata is a Next.js application that processes Jira webhooks and generates AI-powered code fixes using Vertex AI (Gemini) with repository context awareness.

## Features

- **Phase 1**: Jira webhook endpoint with payload parsing and validation
- **Phase 2**: Vertex AI (Gemini) integration for intelligent code generation
- **Phase 3**: GitHub code context engine for repository-aware AI suggestions

## Getting Started

1. Install dependencies:
```bash
npm install
```

2. Configure environment variables in `.env.local`:
```
GCP_PROJECT_ID=your-project-id
GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json
GITHUB_TOKEN=your-github-token
GITHUB_OWNER=your-github-username
GITHUB_REPO=your-repository-name
USE_MOCK_AI=true  # Set to false when billing is enabled
```

3. Run the development server:
```bash
npm run dev
```

4. The Jira webhook endpoint is available at:
```
http://localhost:3000/api/webhook/jira
```

## Project Structure

- `app/api/webhook/jira/route.ts` - Jira webhook endpoint
- `lib/ai.ts` - Vertex AI code generation service
- `lib/context.ts` - GitHub repository context fetcher
- `lib/prompt.ts` - AI prompt builder with context injection
- `lib/vertex.ts` - Vertex AI client initialization
- `types/ticket.ts` - TypeScript interfaces for Jira tickets

## How It Works

1. Receives Jira webhook with ticket information
2. Extracts keywords from ticket summary and description
3. Fetches relevant code files from GitHub repository
4. Builds enhanced prompt with repository context
5. Generates AI-powered code fix suggestions
