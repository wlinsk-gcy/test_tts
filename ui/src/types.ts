export type ArticleSummary = {
  articleId: string;
  title: string;
  author: string;
  language: string;
};

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
  turnNo?: number;
  clientSeq?: number;
  text: string;
  asrMeta?: Record<string, unknown>;
};

export type SubmitTurnResponse = {
  accepted: boolean;
  sessionId: string;
  status: string;
  currentRoundNo: number;
  currentTurnNo: number;
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
