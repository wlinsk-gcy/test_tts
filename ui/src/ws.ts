import type { AssistantEvent } from "./types";

export type SessionSocketCallbacks = {
  onOpen?: () => void;
  onClose?: (event: CloseEvent) => void;
  onError?: (event: Event) => void;
  onEvent?: (event: AssistantEvent) => void;
};

export function openSessionSocket(sessionId: string, backendBaseUrl: string, callbacks: SessionSocketCallbacks): WebSocket {
  const base = backendBaseUrl || "http://localhost:8080";
  const url = new URL(`/ws/sessions/${sessionId}`, base);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(url.toString());
  socket.addEventListener("open", () => callbacks.onOpen?.());
  socket.addEventListener("close", (event) => callbacks.onClose?.(event));
  socket.addEventListener("error", (event) => callbacks.onError?.(event));
  socket.addEventListener("message", (message) => {
    try {
      const parsed = JSON.parse(String(message.data)) as AssistantEvent;
      callbacks.onEvent?.(parsed);
    } catch {
      // Ignore malformed debug messages.
    }
  });
  return socket;
}