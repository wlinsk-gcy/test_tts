import { useEffect, useRef, useState } from "react";
import { apiBaseUrl, createSession, fetchArticles, fetchSessionSnapshot, submitTurn } from "./api";
import { PcmPlayer } from "./audio/pcmPlayer";
import { ArticleList } from "./components/ArticleList";
import { AssistantStreamPanel } from "./components/AssistantStreamPanel";
import { EventTimeline } from "./components/EventTimeline";
import { SessionPanel } from "./components/SessionPanel";
import { StudentInputPanel } from "./components/StudentInputPanel";
import type { ArticleSummary, AssistantEvent, MetricsState, TimelineEntry } from "./types";
import { openSessionSocket } from "./ws";

const MAX_TIMELINE = 120;

function pushTimeline(setter: React.Dispatch<React.SetStateAction<TimelineEntry[]>>, entry: Omit<TimelineEntry, "id">) {
  setter((previous) => [{ id: `${entry.at}-${Math.random()}`, ...entry }, ...previous].slice(0, MAX_TIMELINE));
}

export default function App() {
  const [articles, setArticles] = useState<ArticleSummary[]>([]);
  const [loadingArticles, setLoadingArticles] = useState(false);
  const [activeArticleId, setActiveArticleId] = useState<string>();
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [roundNo, setRoundNo] = useState<number | null>(null);
  const [socketState, setSocketState] = useState("idle");
  const [textStream, setTextStream] = useState("");
  const [finalText, setFinalText] = useState("");
  const [studentText, setStudentText] = useState("");
  const [timeline, setTimeline] = useState<TimelineEntry[]>([]);
  const [lastError, setLastError] = useState<string | null>(null);
  const [metrics, setMetrics] = useState<MetricsState>({});
  const [playerVersion, setPlayerVersion] = useState(0);
  const socketRef = useRef<WebSocket | null>(null);
  const playerRef = useRef(new PcmPlayer());

  useEffect(() => {
    void loadArticles();
    return () => {
      socketRef.current?.close();
    };
  }, []);

  async function loadArticles() {
    try {
      setLoadingArticles(true);
      setLastError(null);
      const loaded = await fetchArticles();
      setArticles(loaded);
      pushTimeline(setTimeline, { at: Date.now(), label: "articles.loaded", detail: `${loaded.length} articles` });
    } catch (error) {
      setLastError(error instanceof Error ? error.message : String(error));
    } finally {
      setLoadingArticles(false);
    }
  }

  async function handleSelectArticle(articleId: string) {
    try {
      setActiveArticleId(articleId);
      await playerRef.current.ensureReady();
      setPlayerVersion((value) => value + 1);
      setTextStream("");
      setFinalText("");
      setTimeline([]);
      setMetrics({ sessionCreateStartedAt: Date.now() });
      setLastError(null);
      socketRef.current?.close();
      setSocketState("creating-session");
      pushTimeline(setTimeline, { at: Date.now(), label: "session.create.start", detail: articleId });
      const created = await createSession(articleId);
      setSessionId(created.sessionId);
      setStatus(created.status);
      setRoundNo(created.currentRoundNo);
      setMetrics((previous) => ({ ...previous, sessionCreatedAt: Date.now() }));
      pushTimeline(setTimeline, { at: Date.now(), label: "session.created", detail: created.sessionId });
      connectSocket(created.sessionId);
    } catch (error) {
      setSocketState("error");
      setLastError(error instanceof Error ? error.message : String(error));
    }
  }

  function connectSocket(nextSessionId: string) {
    setSocketState("connecting");
    const socket = openSessionSocket(nextSessionId, apiBaseUrl, {
      onOpen: () => {
        setSocketState("open");
        setMetrics((previous) => ({ ...previous, wsConnectedAt: Date.now() }));
        pushTimeline(setTimeline, { at: Date.now(), label: "ws.open", detail: nextSessionId });
      },
      onClose: () => {
        setSocketState("closed");
        pushTimeline(setTimeline, { at: Date.now(), label: "ws.close" });
      },
      onError: () => {
        setSocketState("error");
        setLastError("WebSocket transport error");
        pushTimeline(setTimeline, { at: Date.now(), label: "ws.error" });
      },
      onEvent: (event) => {
        void handleAssistantEvent(event);
      }
    });
    socketRef.current = socket;
  }

  async function handleAssistantEvent(event: AssistantEvent) {
    const now = Date.now();
    switch (event.type) {
      case "assistant.debug.timing": {
        const phase = String(event.data.phase ?? "unknown");
        const serverTimestampMs = Number(event.data.serverTimestampMs ?? now);
        const elapsedMs = Number(event.data.elapsedMs ?? 0);
        setMetrics((previous) => {
          switch (phase) {
            case "turn.start":
              return { ...previous, serverTurnStartedAt: serverTimestampMs };
            case "llm.request.start":
              return { ...previous, serverLlmRequestStartedAt: serverTimestampMs };
            case "llm.first.delta":
              return { ...previous, serverFirstTextDeltaAt: serverTimestampMs };
            case "tts.session.ready":
              return { ...previous, serverTtsSessionReadyAt: serverTimestampMs };
            case "tts.first.audio":
              return { ...previous, serverFirstAudioChunkAt: serverTimestampMs };
            case "turn.completed":
              return { ...previous, serverTurnCompletedAt: serverTimestampMs };
            default:
              return previous;
          }
        });
        pushTimeline(setTimeline, {
          at: Number.isFinite(serverTimestampMs) ? serverTimestampMs : now,
          label: `server.${phase}`,
          detail: `${elapsedMs} ms`
        });
        break;
      }
      case "assistant.text.delta": {
        const delta = String(event.data.delta ?? "");
        setTextStream((previous) => previous + delta);
        setMetrics((previous) => previous.firstTextDeltaAt ? previous : { ...previous, firstTextDeltaAt: now });
        pushTimeline(setTimeline, { at: now, label: event.type, detail: delta.slice(0, 80) });
        break;
      }
      case "assistant.text.done": {
        setFinalText(String(event.data.text ?? ""));
        setStatus("TEXT_DONE");
        setMetrics((previous) => ({ ...previous, textDoneAt: now }));
        pushTimeline(setTimeline, { at: now, label: event.type });
        break;
      }
      case "assistant.audio.chunk": {
        const chunkBase64 = String(event.data.chunkBase64 ?? "");
        const sampleRate = Number(event.data.sampleRate ?? 24000);
        setMetrics((previous) => previous.firstAudioChunkAt ? previous : { ...previous, firstAudioChunkAt: now });
        await playerRef.current.enqueueBase64Pcm(chunkBase64, sampleRate);
        setPlayerVersion((value) => value + 1);
        pushTimeline(setTimeline, {
          at: now,
          label: event.type,
          detail: `segment=${String(event.data.segmentSeq ?? "?")}, bytes=${Math.round((chunkBase64.length * 3) / 4)}`
        });
        break;
      }
      case "assistant.audio.done": {
        setMetrics((previous) => ({ ...previous, audioDoneAt: now }));
        setPlayerVersion((value) => value + 1);
        pushTimeline(setTimeline, { at: now, label: event.type });
        break;
      }
      case "assistant.turn.done": {
        setMetrics((previous) => ({ ...previous, turnDoneAt: now }));
        setStatus("WAITING_STUDENT");
        pushTimeline(setTimeline, { at: now, label: event.type, detail: JSON.stringify(event.data) });
        if (sessionId) {
          const snapshot = await fetchSessionSnapshot(sessionId);
          setStatus(snapshot.status);
          setRoundNo(snapshot.currentRoundNo);
        }
        break;
      }
      case "assistant.error": {
        const message = `${String(event.data.code ?? "assistant.error")}: ${String(event.data.message ?? "unknown")}`;
        setLastError(message);
        setStatus("ERROR");
        pushTimeline(setTimeline, { at: now, label: event.type, detail: message });
        break;
      }
    }
  }

  async function handleSubmitStudentTurn() {
    if (!sessionId || !studentText.trim()) {
      return;
    }
    try {
      setLastError(null);
      setTextStream("");
      setFinalText("");
      setMetrics({ sessionCreateStartedAt: Date.now() });
      pushTimeline(setTimeline, { at: Date.now(), label: "student.submit", detail: studentText.trim() });
      const result = await submitTurn(sessionId, {
        clientSeq: Date.now(),
        text: studentText.trim(),
        asrMeta: { final: true, source: "debug-ui" }
      });
      setStudentText("");
      setStatus(result.status);
      setRoundNo(result.currentRoundNo);
    } catch (error) {
      setLastError(error instanceof Error ? error.message : String(error));
    }
  }

  const playerStats = playerRef.current.getStats();
  void playerVersion;

  return (
    <main className="app-shell">
      <header className="hero">
        <div>
          <p className="eyebrow">RD MACHINE DEBUG CONSOLE</p>
          <h1>Teacher Stream Observatory</h1>
          <p className="subcopy">Observe TTFT, TTFA, text deltas, audio chunk delivery, and real PCM playback in one screen.</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => setTimeline([])}>Clear Timeline</button>
      </header>

      <div className="dashboard-grid">
        <ArticleList
          articles={articles}
          loading={loadingArticles}
          activeArticleId={activeArticleId}
          onSelect={(articleId) => { void handleSelectArticle(articleId); }}
        />
        <SessionPanel
          sessionId={sessionId}
          roundNo={roundNo}
          status={status}
          socketState={socketState}
          lastError={lastError}
          metrics={metrics}
          queuedChunks={playerStats.queuedChunks}
          queuedBytes={playerStats.queuedBytes}
        />
        <StudentInputPanel
          value={studentText}
          disabled={!sessionId}
          onChange={setStudentText}
          onSubmit={() => { void handleSubmitStudentTurn(); }}
        />
        <AssistantStreamPanel textStream={textStream} finalText={finalText} />
        <EventTimeline entries={timeline} />
      </div>
    </main>
  );
}
