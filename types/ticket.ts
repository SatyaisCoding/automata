/**
 * JiraTicket interface representing a parsed Jira issue
 */
export interface JiraTicket {
  id: string;
  key: string;
  summary: string;
  description: string;
  priority?: string;
}

