import type {
  ArticleSummary,
  CreateSessionResponse,
  SessionSnapshotResponse,
  SubmitTurnRequest,
  SubmitTurnResponse
} from "./types";

const API_BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.replace(/\/$/, "") ?? "http://localhost:8080";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers ?? undefined);
  if (init?.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${response.status} ${response.statusText}: ${text}`);
  }

  return response.json() as Promise<T>;
}

export const apiBaseUrl = API_BASE;

export function fetchArticles(): Promise<ArticleSummary[]> {
  return request<ArticleSummary[]>("/api/articles", { method: "GET" });
}

export function createSession(articleId: string): Promise<CreateSessionResponse> {
  return request<CreateSessionResponse>("/api/sessions", {
    method: "POST",
    body: JSON.stringify({ articleId })
  });
}

export function submitTurn(sessionId: string, payload: SubmitTurnRequest): Promise<SubmitTurnResponse> {
  return request<SubmitTurnResponse>(`/api/sessions/${sessionId}/turns`, {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

export function closeSession(sessionId: string): Promise<SessionSnapshotResponse> {
  return request<SessionSnapshotResponse>(`/api/sessions/${sessionId}/close`, {
    method: "POST"
  });
}

export function fetchSessionSnapshot(sessionId: string): Promise<SessionSnapshotResponse> {
  return request<SessionSnapshotResponse>(`/api/sessions/${sessionId}`, { method: "GET" });
}
