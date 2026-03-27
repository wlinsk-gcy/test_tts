# Open-Ended Session And Manual Close Design

## Goal

Replace the current five-round reading loop with an open-ended session that continues as long as the user keeps answering, and ends only when the frontend explicitly closes the session.

## Confirmed Product Decisions

- The backend must not enforce a fixed round limit.
- The LLM must keep guiding the conversation based on the student's latest answer and the accumulated chat history.
- Session termination is explicit, not model-driven.
- The frontend will expose an HTTP action to end the session.
- Closing a session must interrupt any in-flight LLM or TTS work immediately.
- A closed session is terminal and cannot be resumed.
- Any later conversation starts by creating a brand-new session.

## Current Problem

The current runtime still treats the conversation as a fixed five-round flow.

This appears in three places:

1. `ReadingSession` increments `currentRoundNo` with `Math.min(..., 5)` and auto-completes when round five finishes.
2. `RoundPlanner` hard-codes five round goals and marks the fifth one as final.
3. `PromptBuilder` changes the closing instruction when the round is considered final.

That design prevents the model from naturally adapting to the student's responses and makes the session lifecycle depend on round count instead of an explicit user action.

## Design Decision

Move the system from a fixed-round workflow to an explicit session lifecycle.

- `currentRoundNo` remains as a conversational counter only.
- Session completion is no longer tied to round number.
- The model never decides that the session is over.
- The frontend owns the decision to close a session.
- The backend owns the responsibility to immediately stop all running work and persist the closed state.

## Target Session Lifecycle

### Status Model

Use the existing status enum and treat `CLOSED` as the terminal state for user-driven termination.

Expected lifecycle:

- `CREATED -> GENERATING -> WAITING_STUDENT`
- `WAITING_STUDENT -> GENERATING`
- `GENERATING -> CLOSED`
- `WAITING_STUDENT -> CLOSED`
- `FAILED` remains terminal
- `CLOSED` remains terminal

The backend must no longer auto-transition to `COMPLETED` because of round count.

### Session Semantics

- A session is active while it is in `GENERATING` or `WAITING_STUDENT`.
- A session is closed only by an explicit API call.
- Once closed, the session history remains readable through the snapshot API.
- Once closed, the session rejects new student turns.
- Once closed, the session never restarts generation.

## HTTP Contract

Add a manual close endpoint:

```http
POST /api/sessions/{sessionId}/close
```

Why `POST` instead of `DELETE`:

- The action changes lifecycle state.
- The session record should remain queryable after closure.
- The operation is a command, not resource deletion.

### Response Shape

The close endpoint should return the latest session snapshot or a dedicated close response with at least:

- `sessionId`
- `status`
- `currentRoundNo`
- `currentTurnNo`
- `awaitingStudentAnswer`

Returning the normal session snapshot is preferred because the frontend already knows how to consume it.

### Idempotency

Closing the same session more than once should be safe.

- First close request: transition to `CLOSED` and cancel active work.
- Later close requests: return the already-closed state without error.

### Submit Contract After Close

`POST /api/sessions/{sessionId}/turns` must reject new answers for a closed session.

Recommended behavior:

- Return `409 Conflict`
- Include a clear message such as `Session is closed`

This is preferable to silently ignoring the request because the frontend can react deterministically.

## Reading Session Changes

File: `src/main/java/com/wlinsk/rd_machine/session/ReadingSession.java`

### Required Changes

- Remove the hard cap in round advancement.
- Remove the `currentRoundNo >= 5` completion branch.
- Add an explicit close transition.
- Guard student submission against closed sessions.
- Preserve turn history and `lastAssistantMessageText` for diagnostics and snapshot rendering.

### Behavioral Rules

When a student answer is accepted:

- Append the new `ReadingTurn`
- Increment `currentRoundNo` by one with no fixed upper bound
- Increment `currentTurnNo`
- Move the session to `GENERATING`

When an assistant turn completes normally:

- Persist the final assistant text
- Move the session to `WAITING_STUDENT`
- Never end the session based on round count

When the session is closed:

- Set status to `CLOSED`
- Set `awaitingStudentAnswer` to `false`
- Reject future submissions

## Prompt And Planner Changes

The backend should stop pretending the conversation has predefined stages such as round two detail recall or round five summary.

### `RoundPlanner`

File: `src/main/java/com/wlinsk/rd_machine/prompt/RoundPlanner.java`

Reduce the planner to two stable prompt goals:

1. First-turn goal
2. Follow-up goal

Suggested behavior:

- Round one asks one clear opening question based on the article.
- Round two and later respond to the student's latest answer, then ask the next most useful question.

The follow-up goal should explicitly tell the model to:

- use the full conversation history
- give a brief reaction first
- ask exactly one next question
- lower the difficulty or provide hints if the student struggles
- deepen or correct understanding when the student answers well
- avoid repeating the same question unless the wording is improved with new guidance
- never decide that the session should end on its own

### `RoundGoal`

File: `src/main/java/com/wlinsk/rd_machine/prompt/RoundGoal.java`

Remove `finalRound`.

The open-ended design no longer needs a prompt-level concept of "this is the last round".

### `PromptBuilder`

File: `src/main/java/com/wlinsk/rd_machine/prompt/PromptBuilder.java`

Required changes:

- Remove final-round prompt branching
- Keep the stable `system` message
- Keep reconstruction of chronological chat history
- Use one first-turn instruction and one follow-up instruction

The system prompt should explicitly state:

- the assistant is a reading teacher
- the teacher must stay grounded in the article content
- each reply must remain speech-friendly
- the teacher should not end the conversation on its own
- the conversation ends only when the client closes the session

The per-turn user prompt should always end with a non-final instruction such as:

- respond briefly to the student's answer
- then ask exactly one clear next question

## Turn History Model

File: `src/main/java/com/wlinsk/rd_machine/session/TurnDecision.java`

The existing `FINISH` decision no longer matches the new lifecycle.

Recommended direction:

- keep `FOLLOW_UP`
- keep `NEXT_ROUND` if useful for compatibility
- stop emitting `FINISH` during normal teaching flow

Session closure should be represented by session status, not by a synthetic terminal turn decision.

## Streaming Cancellation Design

Immediate termination requires more than marking the session as closed. The running assistant turn must also stop emitting text and audio.

File: `src/main/java/com/wlinsk/rd_machine/streaming/AssistantStreamingOrchestrator.java`

### New Runtime Concept

Introduce a lightweight registry of active assistant turns keyed by `sessionId`.

Each active turn should expose a cancel handle containing at least:

- `AtomicBoolean cancelled`
- the running `Future<?>`
- the current `TtsStreamSession` if opened
- the active LLM streaming handle if the SDK supports direct interruption

### Close Flow

When the close endpoint is called:

1. Mark the session as `CLOSED`
2. Look up the active turn handle for that `sessionId`
3. Set the cancellation flag
4. Close the LLM stream if supported
5. Close the TTS session
6. Prevent any later status transition back to `WAITING_STUDENT`
7. Return the closed session snapshot

### Orchestrator Rules

The streaming loop must check the cancellation flag at all important boundaries:

- before publishing text deltas
- before enqueueing TTS segments
- before publishing final text
- before publishing audio completion
- before marking the assistant turn completed
- before publishing `assistant.turn.done`

If cancellation is observed:

- stop publishing further output
- stop enqueueing audio
- close TTS resources
- remove the active handle from the registry
- leave the session in `CLOSED`

This avoids the current failure mode where the frontend appears closed but backend streaming continues.

## LLM Client Considerations

File: `src/main/java/com/wlinsk/rd_machine/llm/BailianLlmClient.java`

The existing client method returns only after the streaming loop finishes. For true immediate interruption, the runtime should be able to break out of the stream early.

Preferred direction:

- make the stream loop cancellation-aware
- if the SDK supports closing the underlying stream response, wire that into the cancel handle
- if direct stream closure is not available, stop consuming deltas and close surrounding resources as soon as cancellation is observed

The exact mechanism can vary by SDK, but the design requirement is fixed: close must stop user-visible output immediately.

## TTS Considerations

File: `src/main/java/com/wlinsk/rd_machine/tts/TtsStreamSession.java`

The close path must treat TTS as a cancelable stream, not only a stream that naturally finishes.

Requirements:

- queued but not yet played segments must stop being sent
- the current TTS session must close promptly
- no extra `assistant.audio.chunk` events should be emitted after cancellation is processed
- the frontend should also clear local audio playback state after a successful close response

## Frontend Changes

File: `ui/src/App.tsx`

Add a visible "End Session" action.

Expected behavior:

- the button is enabled for active sessions
- clicking it calls the close endpoint
- after success, the UI updates to `CLOSED`
- the UI stops accepting new student input
- local PCM playback and queued audio are cleared
- the WebSocket is closed after the close request succeeds

The frontend should not attempt to reuse a closed `sessionId`.

Any later conversation starts with the existing create-session flow.

## API And UI Compatibility

Existing snapshot and creation APIs can remain mostly unchanged.

Compatibility notes:

- `currentRoundNo` stays in responses for observability
- the frontend may continue showing the current round count
- consumers must stop assuming there is a fixed maximum round count
- status handling in the UI must recognize `CLOSED` as terminal

## Error Handling

- Close on an unknown session returns `404`
- Close on an already closed session returns `200` with the closed state
- Submit on a closed session returns `409`
- If cancellation races with normal completion, `CLOSED` wins and the session must stay closed
- If stream teardown throws during close, preserve `CLOSED` and log the teardown failure

## Testing Strategy

### Session Unit Tests

- accepting student answers increments rounds without an upper bound
- assistant completion no longer auto-completes at round five
- closing from `WAITING_STUDENT` transitions to `CLOSED`
- closing from `GENERATING` transitions to `CLOSED`
- closed sessions reject new student answers

### Prompt Tests

- round one uses the first-turn goal
- later rounds use the follow-up goal
- prompt assembly contains no final-round branch
- prompt assembly still rebuilds chronological message history correctly

### Service Tests

- close endpoint is idempotent
- close returns closed snapshot data
- submit after close returns the expected error

### Streaming Tests

- cancel during LLM streaming stops further text events
- cancel during TTS streaming stops further audio events
- cancel prevents `assistant.turn.done(awaitingStudentAnswer=true)`
- cancel does not allow the session to return to `WAITING_STUDENT`

## Implementation Outline

1. Refactor `ReadingSession` to remove the five-round cap and add explicit close behavior.
2. Refactor `RoundGoal`, `RoundPlanner`, and `PromptBuilder` for open-ended prompting.
3. Add the close endpoint in controller and service layers.
4. Introduce an active-turn cancellation registry for the orchestrator.
5. Make LLM and TTS streaming cancellation-aware.
6. Add the frontend close action and terminal-state handling.
7. Add unit and integration tests around lifecycle and cancellation.

## Acceptance Criteria

- Sessions can continue beyond five rounds
- The model keeps asking the next question based on user answers and prior context
- Sessions do not end unless the frontend calls the close endpoint
- Closing a session interrupts in-flight LLM and TTS work immediately
- Closed sessions cannot be resumed
- A new conversation always starts by creating a new session
