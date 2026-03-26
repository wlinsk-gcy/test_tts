# AI Teacher Realtime Streaming Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a session-based AI teacher flow that streams LLM text from Bailian, segments text into realtime TTS input, and pushes both text and audio to the frontend over a session WebSocket without using Spring AI.

**Architecture:** The backend keeps the reading workflow in a session state machine. HTTP handles business actions such as creating sessions and submitting student text, while a session-scoped WebSocket pushes assistant text deltas and audio chunks. A dedicated streaming orchestration layer bridges `openai-java` text streaming and Aliyun realtime TTS WebSocket streaming.

**Tech Stack:** Spring Boot Web MVC, Java 21, `openai-java`, Java `HttpClient` WebSocket, JSON over HTTP/WS

---

## Impacted Modules

- `src/main/resources/application.properties`
- `src/main/java/com/wlinsk/rd_machine/config`
- `src/main/java/com/wlinsk/rd_machine/article`
- `src/main/java/com/wlinsk/rd_machine/session`
- `src/main/java/com/wlinsk/rd_machine/prompt`
- `src/main/java/com/wlinsk/rd_machine/llm`
- `src/main/java/com/wlinsk/rd_machine/tts`
- `src/main/java/com/wlinsk/rd_machine/streaming`
- `src/main/java/com/wlinsk/rd_machine/transport/http`
- `src/main/java/com/wlinsk/rd_machine/transport/ws`

## Acceptance Criteria

- Frontend can fetch a fixed article list.
- Creating a session immediately triggers round 1 assistant generation.
- Assistant text is streamed to the frontend over WS.
- Student ASR final text can be submitted by HTTP and advances the session.
- LLM output is segmented before being sent to realtime TTS.
- TTS audio chunks are pushed to the frontend over the same session WS.
- Session status transitions are observable and do not require a long-running background Agent.
- The project compiles successfully with the new code.

## Validation Approach

- Compile check: `mvnw.cmd -q -DskipTests compile`
- Manual API check for article list, session creation, and turn submission
- Manual WS check for `assistant.text.delta`, `assistant.text.done`, `assistant.audio.chunk`, `assistant.turn.done`
- Log inspection for session status transitions and streaming pipeline events

### Task 1: Establish package skeleton and typed configuration

**Files:**
- Modify: `src/main/resources/application.properties`
- Create: `src/main/java/com/wlinsk/rd_machine/config/AiLlmProperties.java`
- Create: `src/main/java/com/wlinsk/rd_machine/config/AiTtsProperties.java`
- Create: `src/main/java/com/wlinsk/rd_machine/config/AiConfig.java`

**Step 1: Split runtime properties**

Add distinct property namespaces for LLM and TTS:

```properties
rd.ai.llm.api-key=
rd.ai.llm.base-url=
rd.ai.llm.model=

rd.ai.tts.api-key=
rd.ai.tts.ws-url=
rd.ai.tts.model=
rd.ai.tts.voice=
rd.ai.tts.sample-rate=24000
```

**Step 2: Add typed property classes**

Create configuration holders for the `rd.ai.llm.*` and `rd.ai.tts.*` namespaces.

**Step 3: Register configuration binding**

Wire the property classes into Spring Boot configuration so downstream services do not read raw strings from the environment.

**Step 4: Verify**

Run: `mvnw.cmd -q -DskipTests compile`

Expected: compile passes with the new configuration classes.

### Task 2: Create article catalog and session domain model

**Files:**
- Create: `src/main/java/com/wlinsk/rd_machine/article/ArticleSummary.java`
- Create: `src/main/java/com/wlinsk/rd_machine/article/ArticleDetail.java`
- Create: `src/main/java/com/wlinsk/rd_machine/article/ArticleCatalogService.java`
- Create: `src/main/java/com/wlinsk/rd_machine/session/SessionStatus.java`
- Create: `src/main/java/com/wlinsk/rd_machine/session/TurnDecision.java`
- Create: `src/main/java/com/wlinsk/rd_machine/session/ReadingSession.java`
- Create: `src/main/java/com/wlinsk/rd_machine/session/ReadingTurn.java`
- Create: `src/main/java/com/wlinsk/rd_machine/session/InMemorySessionStore.java`

**Step 1: Add article models**

Define summary/detail models for the fixed article list exposed to the frontend.

**Step 2: Add session and turn models**

Model the fields agreed in design:

- session identifiers and article snapshot
- `currentRoundNo`
- `currentTurnNo`
- `status`
- latest assistant text
- turn history

**Step 3: Add an in-memory session store**

Keep the first version simple and session-scoped in memory. Do not introduce a database in this milestone.

**Step 4: Verify**

Run: `mvnw.cmd -q -DskipTests compile`

Expected: compile passes and the domain model is available for service wiring.

### Task 3: Add prompt model and round strategy

**Files:**
- Create: `src/main/java/com/wlinsk/rd_machine/prompt/RoundGoal.java`
- Create: `src/main/java/com/wlinsk/rd_machine/prompt/PromptContext.java`
- Create: `src/main/java/com/wlinsk/rd_machine/prompt/PromptBuilder.java`
- Create: `src/main/java/com/wlinsk/rd_machine/prompt/RoundPlanner.java`

**Step 1: Encode the five-round teaching strategy**

Represent rounds 1 to 5 as explicit backend-controlled goals rather than model-chosen flow.

**Step 2: Create a prompt context object**

Include article snapshot, current round, prior assistant text, latest student answer, and recent context summary.

**Step 3: Implement prompt assembly**

Generate:

- stable system instructions
- dynamic user content for the current round

Do not let this module know anything about HTTP, WS, or TTS.

**Step 4: Verify**

Run: `mvnw.cmd -q -DskipTests compile`

Expected: prompt generation compiles cleanly and remains transport-agnostic.

### Task 4: Expose article and session HTTP endpoints

**Files:**
- Create: `src/main/java/com/wlinsk/rd_machine/transport/http/ArticleController.java`
- Create: `src/main/java/com/wlinsk/rd_machine/transport/http/SessionController.java`
- Create: `src/main/java/com/wlinsk/rd_machine/transport/http/dto/CreateSessionRequest.java`
- Create: `src/main/java/com/wlinsk/rd_machine/transport/http/dto/CreateSessionResponse.java`
- Create: `src/main/java/com/wlinsk/rd_machine/transport/http/dto/SubmitTurnRequest.java`
- Create: `src/main/java/com/wlinsk/rd_machine/transport/http/dto/SessionSnapshotResponse.java`
- Create: `src/main/java/com/wlinsk/rd_machine/session/SessionService.java`

**Step 1: Implement `GET /api/articles`**

Return the fixed article list from `ArticleCatalogService`.

**Step 2: Implement `POST /api/sessions`**

Create a session from `articleId`, initialize it for round 1, and return `sessionId`, current state, and WS URL.

**Step 3: Implement `POST /api/sessions/{sessionId}/turns`**

Accept student final text, apply idempotency via `clientSeq`, and hand the session to the next round trigger.

**Step 4: Implement `GET /api/sessions/{sessionId}`**

Return the latest snapshot for refresh/reconnect flows.

**Step 5: Verify**

Run: `mvnw.cmd -q -DskipTests compile`

Expected: compile passes and the endpoints are available for manual invocation.

### Task 5: Add session-scoped WebSocket transport and outbound event model

**Files:**
- Create: `src/main/java/com/wlinsk/rd_machine/transport/ws/WsConfig.java`
- Create: `src/main/java/com/wlinsk/rd_machine/transport/ws/SessionWebSocketHandler.java`
- Create: `src/main/java/com/wlinsk/rd_machine/transport/ws/SessionConnectionRegistry.java`
- Create: `src/main/java/com/wlinsk/rd_machine/transport/ws/AssistantEvent.java`
- Create: `src/main/java/com/wlinsk/rd_machine/transport/ws/AssistantEventPublisher.java`

**Step 1: Add WS endpoint**

Expose `/ws/sessions/{sessionId}`.

**Step 2: Track active session connections**

Register and remove WebSocket sessions safely so the backend can publish assistant events by `sessionId`.

**Step 3: Define outbound event envelopes**

Model at least:

- `assistant.text.delta`
- `assistant.text.done`
- `assistant.audio.chunk`
- `assistant.audio.done`
- `assistant.turn.done`
- `assistant.error`

**Step 4: Verify**

Run: `mvnw.cmd -q -DskipTests compile`

Expected: compile passes and the WebSocket handler starts with the app.

### Task 6: Wrap Bailian LLM streaming with `openai-java`

**Files:**
- Create: `src/main/java/com/wlinsk/rd_machine/llm/LlmDeltaListener.java`
- Create: `src/main/java/com/wlinsk/rd_machine/llm/BailianLlmClient.java`
- Create: `src/main/java/com/wlinsk/rd_machine/llm/BailianLlmService.java`
- Modify: `src/main/java/com/wlinsk/rd_machine/config/AiConfig.java`

**Step 1: Build the OpenAI-compatible client**

Initialize `openai-java` with the configured `baseUrl`, `apiKey`, and model.

**Step 2: Add a streaming callback interface**

Expose text delta, completion, and error callbacks so the orchestration layer stays independent from SDK details.

**Step 3: Implement round text generation**

Use the prompt module to request the current assistant utterance as a stream.

**Step 4: Verify**

Run: `mvnw.cmd -q -DskipTests compile`

Expected: compile passes and the LLM service can be called by the orchestrator.

### Task 7: Build the text segmentation and orchestration layer

**Files:**
- Create: `src/main/java/com/wlinsk/rd_machine/streaming/TextSegment.java`
- Create: `src/main/java/com/wlinsk/rd_machine/streaming/TextSegmenter.java`
- Create: `src/main/java/com/wlinsk/rd_machine/streaming/AssistantStreamingOrchestrator.java`
- Create: `src/main/java/com/wlinsk/rd_machine/streaming/StreamingSessionContext.java`
- Modify: `src/main/java/com/wlinsk/rd_machine/session/SessionService.java`

**Step 1: Implement segmentation rules**

Flush text to TTS when any of these is true:

- punctuation boundary is reached
- minimum length threshold is reached
- max wait threshold is reached

**Step 2: Publish text deltas immediately**

Every LLM delta should be forwarded to the WebSocket publisher without waiting for TTS.

**Step 3: Trigger orchestration from session actions**

Creating a session should trigger round 1 generation. Submitting student text should trigger the next round generation.

**Step 4: Keep TTS out for the first orchestration pass**

At this step, complete the text-only stream path first so event ordering and session transitions are correct before audio is introduced.

**Step 5: Verify**

Run: `mvnw.cmd -q -DskipTests compile`

Expected: compile passes and text-only streaming can be observed from WS events.

### Task 8: Add Aliyun realtime TTS WebSocket client

**Files:**
- Create: `src/main/java/com/wlinsk/rd_machine/tts/TtsAudioListener.java`
- Create: `src/main/java/com/wlinsk/rd_machine/tts/TtsSynthesisRequest.java`
- Create: `src/main/java/com/wlinsk/rd_machine/tts/AliyunRealtimeTtsClient.java`
- Create: `src/main/java/com/wlinsk/rd_machine/tts/AliyunRealtimeTtsService.java`

**Step 1: Build a dedicated TTS WebSocket client**

Use Java 21 `HttpClient` WebSocket rather than Spring AI abstractions.

**Step 2: Model the realtime TTS lifecycle**

Handle:

- open/connect
- send text segment
- finish/commit
- receive audio chunks
- completion
- error

**Step 3: Convert provider messages into internal callbacks**

The rest of the code should consume neutral audio events rather than provider-specific payloads.

**Step 4: Verify**

Run: `mvnw.cmd -q -DskipTests compile`

Expected: compile passes and the TTS client is ready for orchestration integration.

### Task 9: Integrate TTS into the streaming pipeline

**Files:**
- Modify: `src/main/java/com/wlinsk/rd_machine/streaming/AssistantStreamingOrchestrator.java`
- Modify: `src/main/java/com/wlinsk/rd_machine/transport/ws/AssistantEventPublisher.java`
- Modify: `src/main/java/com/wlinsk/rd_machine/session/SessionService.java`

**Step 1: Connect segmented text to TTS**

Send each flushed `TextSegment` into the realtime TTS client with ordered `segmentSeq`.

**Step 2: Publish audio chunks to frontend WS**

Forward audio data as either:

- binary WS frames if the handler supports it cleanly
- JSON base64 chunks for the first working version

Prefer binary frames as the stable target.

**Step 3: Close each round cleanly**

Only publish `assistant.turn.done` after:

- `assistant.text.done`
- `assistant.audio.done`
- session state is updated to `WAITING_STUDENT`

**Step 4: Verify**

Run: `mvnw.cmd -q -DskipTests compile`

Expected: compile passes and both text and audio flows are wired through the same session WS.

### Task 10: Harden recovery, idempotency, and observability

**Files:**
- Modify: `src/main/java/com/wlinsk/rd_machine/session/SessionService.java`
- Modify: `src/main/java/com/wlinsk/rd_machine/session/InMemorySessionStore.java`
- Modify: `src/main/java/com/wlinsk/rd_machine/transport/http/SessionController.java`
- Modify: `src/main/java/com/wlinsk/rd_machine/transport/ws/AssistantEventPublisher.java`

**Step 1: Add duplicate-submit protection**

Use `clientSeq` to ignore repeated turn submissions safely.

**Step 2: Make session snapshots reconnect-friendly**

Ensure `GET /api/sessions/{sessionId}` returns enough information for the frontend to rebuild state after refresh.

**Step 3: Improve logging**

Log key events:

- session created
- generation started
- text delta completed
- segment flushed
- TTS completed
- round completed
- session failed

**Step 4: Verify**

Run: `mvnw.cmd -q -DskipTests compile`

Expected: compile passes and logs are sufficient to trace a full round-trip.

### Task 11: Manual end-to-end validation

**Files:**
- Modify: `src/main/resources/application.properties`
- Optional notes: `docs/plans/2026-03-26-ai-teacher-realtime-streaming-design.md`

**Step 1: Fill local configuration**

Provide working values for:

- Bailian OpenAI-compatible endpoint and model
- Aliyun realtime TTS endpoint, model, and voice

**Step 2: Start the application**

Run: `mvnw.cmd spring-boot:run`

Expected: application starts and exposes HTTP/WS endpoints.

**Step 3: Validate the happy path manually**

Check:

- fetch article list
- create session
- connect WS
- observe first assistant text deltas
- observe audio chunks
- submit one student turn
- observe next round response

**Step 4: Validate failure handling**

Check:

- invalid `sessionId`
- repeated `clientSeq`
- missing provider credentials
- provider stream failure surfaces as `assistant.error`

**Step 5: Record follow-up gaps**

Capture any issues that should be deferred, such as persistence, cancellation, binary audio transport, or frontend buffering strategy.

## Notes

- Do not introduce Spring AI in this implementation plan.
- Do not add persistence in this milestone unless the scope changes.
- Do not treat automated tests as mandatory for this repository unless requested later.
- Git commit steps are intentionally omitted because the current workspace is not a Git repository.
