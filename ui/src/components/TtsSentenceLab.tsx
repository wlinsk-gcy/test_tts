import type { TimelineEntry, TtsStreamEndpoint } from "../types";

type Props = {
  sentence: string;
  language: string;
  endpoint: TtsStreamEndpoint;
  sessionId: string | null;
  streamState: string;
  lastError: string | null;
  queuedChunks: number;
  queuedBytes: number;
  timeline: TimelineEntry[];
  onSentenceChange: (value: string) => void;
  onLanguageChange: (value: string) => void;
  onEndpointChange: (value: TtsStreamEndpoint) => void;
  onStart: () => void;
  onClose: () => void;
  onResetPlayer: () => void;
};

export function TtsSentenceLab({
  sentence,
  language,
  endpoint,
  sessionId,
  streamState,
  lastError,
  queuedChunks,
  queuedBytes,
  timeline,
  onSentenceChange,
  onLanguageChange,
  onEndpointChange,
  onStart,
  onClose,
  onResetPlayer
}: Props) {
  const streamPath = endpoint === "cosyvoice" ? "/api/cosyvoice/tts/stream" : "/api/tts/sessions/stream";
  const canCloseSession = endpoint === "legacy" ? Boolean(sessionId) : streamState === "streaming";
  const closeButtonText = endpoint === "legacy" ? "Close TTS Session" : "Cancel Stream";

  return (
    <section className="panel">
      <div className="panel-header">
        <h2>TTS Sentence Lab</h2>
        <span>{streamState}</span>
      </div>

      <div className="tts-lab-meta">
        <div>
          <label>TTS Session</label>
          <strong>{sessionId ?? "--"}</strong>
        </div>
        <div>
          <label>Endpoint</label>
          <strong>{streamPath}</strong>
        </div>
        <div>
          <label>Queued Audio</label>
          <strong>{queuedChunks} chunks / {queuedBytes} bytes</strong>
        </div>
      </div>

      <label htmlFor="tts-endpoint">TTS Endpoint</label>
      <select
        id="tts-endpoint"
        className="tts-lab-select"
        value={endpoint}
        onChange={(event) => onEndpointChange(event.target.value as TtsStreamEndpoint)}
      >
        <option value="cosyvoice">CosyVoice v3 Flash</option>
        <option value="legacy">Legacy Realtime</option>
      </select>

      <label htmlFor="tts-language">Language</label>
      <select
        id="tts-language"
        className="tts-lab-select"
        value={language}
        onChange={(event) => onLanguageChange(event.target.value)}
      >
        <option value="zh-CN">zh-CN</option>
        <option value="zh-HK">zh-HK</option>
        <option value="zh-TW">zh-TW</option>
        <option value="en-US">en-US</option>
      </select>

      <textarea
        className="student-input"
        value={sentence}
        onChange={(event) => onSentenceChange(event.target.value)}
        placeholder={`Type one sentence to stream through ${streamPath}`}
      />

      <div className="tts-lab-actions">
        <button className="primary-button" type="button" disabled={!sentence.trim()} onClick={onStart}>
          Start TTS
        </button>
        <button className="ghost-button" type="button" disabled={!canCloseSession} onClick={onClose}>
          {closeButtonText}
        </button>
        <button className="ghost-button" type="button" onClick={onResetPlayer}>
          Reset Player
        </button>
      </div>

      <div className="tts-lab-timeline">
        {timeline.map((entry) => (
          <div className="timeline-item" key={entry.id}>
            <strong>{new Date(entry.at).toLocaleTimeString()}</strong>
            <span>{entry.label}</span>
            <small>{entry.detail ?? ""}</small>
          </div>
        ))}
      </div>

      {lastError ? <p className="error-box">{lastError}</p> : null}
    </section>
  );
}
