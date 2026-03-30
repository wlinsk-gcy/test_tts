import type {
  ApiResult,
  CreateSessionPayload,
  CreateSessionResponse,
  SessionSnapshotResponse,
  SubmitTurnRequest,
  SubmitTurnResponse
} from "./types";

const API_BASE = ((import.meta as ImportMeta & { env?: Record<string, string | undefined> }).env?.VITE_API_BASE_URL)?.replace(/\/$/, "") ?? "http://localhost:8080";

export function unwrapApiResult<T>(payload: ApiResult<T>): T {
  if (payload.rspCd !== "00000") {
    throw new Error(payload.rspInf || `Request failed with code ${payload.rspCd}`);
  }
  return payload.data;
}

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

  const payload = await response.json() as ApiResult<T>;
  return unwrapApiResult(payload);
}

export const apiBaseUrl = API_BASE;

export function createSession(payload: CreateSessionPayload): Promise<CreateSessionResponse> {
  return request<CreateSessionResponse>("/api/sessions", {
    method: "POST",
    body: JSON.stringify(payload)
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