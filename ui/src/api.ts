import type {
  ApiResult,
  CreateSessionPayload,
  CreateSessionResponse,
  SessionSnapshotResponse,
  TtsChunkEvent,
  TtsCloseSessionRequest,
  TtsStreamEndpoint,
  TtsSentenceStreamRequest,
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

export type TtsSentenceStreamHandle = {
  cancel: () => void;
  done: Promise<void>;
};

const TTS_STREAM_PATHS: Record<TtsStreamEndpoint, string> = {
  cosyvoice: "/api/cosyvoice/tts/stream",
  legacy: "/api/tts/sessions/stream"
};

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

export function closeTtsSession(sessionId: string): Promise<void> {
  return request<void>("/api/tts/sessions/close", {
    method: "POST",
    body: JSON.stringify({ sessionId } satisfies TtsCloseSessionRequest)
  });
}

export function streamTtsSentence(
  payload: TtsSentenceStreamRequest,
  onEvent: (event: TtsChunkEvent) => void | Promise<void>,
  endpoint: TtsStreamEndpoint = "legacy"
): TtsSentenceStreamHandle {
  const abortController = new AbortController();
  const done = (async () => {
    const requestPayload = endpoint === "cosyvoice"
      ? payload
      : {
          sessionId: payload.sessionId,
          language: payload.language,
          sentence: payload.sentence
        };

    const response = await fetch(`${API_BASE}${TTS_STREAM_PATHS[endpoint]}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify(requestPayload),
      signal: abortController.signal
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(`${response.status} ${response.statusText}: ${text}`);
    }

    if (!response.body) {
      throw new Error("SSE response body is empty");
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { value, done: streamDone } = await reader.read();
      if (value) {
        buffer += decoder.decode(value, { stream: !streamDone });
        buffer = buffer.replace(/\r\n/g, "\n");
      }

      let boundaryIndex = buffer.indexOf("\n\n");
      while (boundaryIndex >= 0) {
        const frame = buffer.slice(0, boundaryIndex);
        buffer = buffer.slice(boundaryIndex + 2);
        await parseTtsSseFrame(frame, onEvent);
        boundaryIndex = buffer.indexOf("\n\n");
      }

      if (streamDone) {
        break;
      }
    }

    const tailFrame = buffer.trim();
    if (tailFrame) {
      await parseTtsSseFrame(tailFrame, onEvent);
    }
  })();

  return {
    cancel: () => abortController.abort(),
    done
  };
}

async function parseTtsSseFrame(
  frame: string,
  onEvent: (event: TtsChunkEvent) => void | Promise<void>
): Promise<void> {
  const dataLines = frame
    .split("\n")
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart());

  if (dataLines.length === 0) {
    return;
  }

  const event = JSON.parse(dataLines.join("\n")) as TtsChunkEvent;
  await onEvent(event);
}
