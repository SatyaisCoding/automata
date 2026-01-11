import { VertexAI } from '@google-cloud/vertexai';

/**
 * Initialize and return VertexAI client instance
 */
export function getVertexClient(): VertexAI {
  const projectId = process.env.GCP_PROJECT_ID;
  const location = process.env.GCP_LOCATION || 'us-central1';

  if (!projectId) {
    throw new Error('GCP_PROJECT_ID environment variable is not set');
  }

  const vertexAI = new VertexAI({
    project: projectId,
    location: location,
  });

  return vertexAI;
}

