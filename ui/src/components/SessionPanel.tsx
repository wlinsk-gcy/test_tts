import type { MetricsState } from "../types";

type Props = {
  sessionId: string | null;
  roundNo: number | null;
  status: string;
  socketState: string;
  lastError: string | null;
  metrics: MetricsState;
  queuedChunks: number;
  queuedBytes: number;
};

function metricDelta(from?: number, to?: number): string {
  if (!from || !to) {
    return "--";
  }
  return `${to - from} ms`;
}

export function SessionPanel({ sessionId, roundNo, status, socketState, lastError, metrics, queuedChunks, queuedBytes }: Props) {
  return (
    <section className="panel">
      <div className="panel-header">
        <h2>Session</h2>
        <span>{socketState}</span>
      </div>
      <div className="facts-grid">
        <div><label>Session</label><strong>{sessionId ?? "--"}</strong></div>
        <div><label>Status</label><strong>{status || "--"}</strong></div>
        <div><label>Round</label><strong>{roundNo ?? "--"}</strong></div>
        <div><label>Queued Audio</label><strong>{queuedChunks} chunks / {queuedBytes} bytes</strong></div>
        <div><label>Client TTFT</label><strong>{metricDelta(metrics.sessionCreateStartedAt, metrics.firstTextDeltaAt)}</strong></div>
        <div><label>Client TTFA</label><strong>{metricDelta(metrics.sessionCreateStartedAt, metrics.firstAudioChunkAt)}</strong></div>
        <div><label>Server TTFT</label><strong>{metricDelta(metrics.serverTurnStartedAt, metrics.serverFirstTextDeltaAt)}</strong></div>
        <div><label>Server TTFA</label><strong>{metricDelta(metrics.serverTurnStartedAt, metrics.serverFirstAudioChunkAt)}</strong></div>
        <div><label>LLM Req→1st Delta</label><strong>{metricDelta(metrics.serverLlmRequestStartedAt, metrics.serverFirstTextDeltaAt)}</strong></div>
        <div><label>TTS Ready</label><strong>{metricDelta(metrics.serverTurnStartedAt, metrics.serverTtsSessionReadyAt)}</strong></div>
      </div>
      {lastError ? <p className="error-box">{lastError}</p> : null}
    </section>
  );
}
