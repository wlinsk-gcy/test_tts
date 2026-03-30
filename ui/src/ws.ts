import type { AssistantEvent } from "./types";

export type SessionSocketCallbacks = {
  onOpen?: () => void;
  onClose?: (event: CloseEvent) => void;
  onError?: (event: Event) => void;
  onEvent?: (event: AssistantEvent) => void;
};

function isAssistantEvent(value: unknown): value is AssistantEvent {
  return typeof value === "object"
    && value !== null
    && typeof (value as { type?: unknown }).type === "string"
    && typeof (value as { sessionId?: unknown }).sessionId === "string"
    && typeof (value as { turnNo?: unknown }).turnNo === "number"
    && typeof (value as { roundNo?: unknown }).roundNo === "number"
    && typeof (value as { data?: unknown }).data === "object"
    && (value as { data?: unknown }).data !== null;
}

export function parseAssistantEventMessage(messageData: unknown): AssistantEvent | null {
  try {
    const parsed = JSON.parse(String(messageData)) as unknown;
    return isAssistantEvent(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function openSessionSocket(sessionId: string, backendBaseUrl: string, callbacks: SessionSocketCallbacks): WebSocket {
  const base = backendBaseUrl || "http://localhost:8080";
  const url = new URL(`/ws/sessions/${sessionId}`, base);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(url.toString());
  socket.addEventListener("open", () => callbacks.onOpen?.());
  socket.addEventListener("close", (event) => callbacks.onClose?.(event));
  socket.addEventListener("error", (event) => callbacks.onError?.(event));
  socket.addEventListener("message", (message) => {
    const parsed = parseAssistantEventMessage(message.data);
    if (parsed) {
      callbacks.onEvent?.(parsed);
    }
  });
  return socket;
}
