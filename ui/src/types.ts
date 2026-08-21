export type DebugArticle = {
  id: string;
  title: string;
  author: string;
  language: string;
  content: string;
};

export type CreateSessionPayload = Pick<DebugArticle, "title" | "author" | "language" | "content">;

export type ApiResult<T> = {
  rspCd: string;
  rspInf: string;
  data: T;
  responseTm?: string;
  rspType?: number;
  v?: string;
};

export type CreateSessionResponse = {
  sessionId: string;
  status: string;
  currentRoundNo: number;
  currentTurnNo: number;
  wsUrl: string;
};

export type SubmitTurnRequest = {
  clientSeq?: number;
  text: string;
};

export type SubmitTurnResponse = {
  accepted: boolean;
  sessionId: string;
  status: string;
  currentRoundNo: number;
  currentTurnNo: number;
};

export type TtsSentenceStreamRequest = {
  sessionId: string | null;
  language: string;
  sentence: string;
  ssml?: boolean;
};

export type TtsCloseSessionRequest = {
  sessionId: string;
};

export type AssistantUsage = {
  turnNo: number;
  roundNo: number;
  llm: {
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
    cachedTokens: number;
  };
  ttsCharacters: number;
};

export type TtsStreamEndpoint = "cosyvoice" | "legacy";

export type TtsChunkEventType = "cosyvoice.event" | "audio.chunk" | "audio.done" | "audio.error";

export type TtsChunkEvent = {
  type: TtsChunkEventType;
  sessionId: string | null;
  data: Record<string, unknown>;
};

export type SessionSnapshotResponse = {
  sessionId: string;
  title: string;
  author: string;
  language: string;
  status: string;
  currentRoundNo: number;
  currentTurnNo: number;
  awaitingStudentAnswer: boolean;
  lastAssistantMessageText: string | null;
  createdAt: string;
  updatedAt: string;
  turns: TurnSnapshot[];
};

export type TurnSnapshot = {
  turnNo: number;
  roundNo: number;
  teacherReplyFinal: string | null;
  studentAnswerRaw: string | null;
  studentAnswerNormalized: string | null;
  decision: string;
  startedAt: string;
  completedAt: string;
};

export type AssistantEventType =
  | "assistant.text.delta"
  | "assistant.text.done"
  | "assistant.audio.chunk"
  | "assistant.audio.done"
  | "assistant.usage"
  | "assistant.turn.done"
  | "assistant.error"
  | "assistant.debug.timing";

export type AssistantEvent = {
  type: AssistantEventType;
  sessionId: string;
  turnNo: number;
  roundNo: number;
  data: Record<string, unknown>;
};

export type TimelineEntry = {
  id: string;
  at: number;
  label: string;
  detail?: string;
};

export type MetricsState = {
  sessionCreateStartedAt?: number;
  sessionCreatedAt?: number;
  wsConnectedAt?: number;
  firstTextDeltaAt?: number;
  textDoneAt?: number;
  firstAudioChunkAt?: number;
  audioDoneAt?: number;
  turnDoneAt?: number;
  serverTurnStartedAt?: number;
  serverLlmRequestStartedAt?: number;
  serverFirstTextDeltaAt?: number;
  serverTtsSessionReadyAt?: number;
  serverFirstAudioChunkAt?: number;
  serverTurnCompletedAt?: number;
};
