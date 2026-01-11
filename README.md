# Automata

A Next.js application for processing Jira webhooks.

## Getting Started

1. Install dependencies:
```bash
npm install
```

2. Run the development server:
```bash
npm run dev
```

3. The Jira webhook endpoint is available at:
```
http://localhost:3000/api/webhook/jira
```

## Project Structure

- `app/api/webhook/jira/route.ts` - Jira webhook endpoint
- `types/ticket.ts` - TypeScript interfaces for Jira tickets

