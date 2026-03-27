# LLM Context Design For Multi-Turn Reading Sessions

## Goal

Replace the current single-turn prompt assembly with a true multi-turn chat context so the model receives stable session rules and article background once, then sees the real teacher and student conversation history as ordered chat messages.

## Current Problem

The current runtime builds one `systemPrompt` and one large `userPrompt` per round. Multi-turn context is simulated by appending fields such as recent-round summaries, the previous teacher utterance, and the previous student answer into a single user message.

This causes three problems:

1. The model does not see a real `system -> user -> assistant -> user` history.
2. Session state carries prompt-only cache fields such as summaries and pending answers.
3. Prompt quality depends on server-side rewriting of history instead of the original conversation.

## Design Decision

Use a real chat message list for every LLM request.

- One `system` message is created from fixed teacher rules and full article content.
- The `system` message remains constant for the entire session.
- Each round appends real `user` and `assistant` messages in chronological order.
- `RoundPlanner` remains the source of per-round teaching goals.
- The prompt layer no longer injects summary text, previous-teacher helper text, or duplicate article content into a single user prompt.

This design is intentionally optimized for the current product constraints:

- One article per session
- Article does not change mid-session
- Maximum of five rounds
- Token pressure is low enough to keep full history without summarization

## Runtime Message Model

### System Message

The `system` message is constant within a session and contains:

- Teacher role and behavioral rules
- Speech-friendly output constraints
- ASR-tolerant interpretation guidance
- Article title, author, language, and full content wrapped in XML tags

Example shape:

```text
你是一名语言学习老师。你只能基于给定文章内容提问、评价和引导。输出必须适合语音播报，保持自然、简洁、稳定，不要使用 Markdown、HTML、emoji 或列表。每次输出控制在 3 到 5 句话以内。如果学生回答可能受到 ASR 误识别影响，请结合上下文做温和理解，不要直接指出识别错误。

<article>
  <title>少年閏土</title>
  <author>魯迅</author>
  <language>zh-HK</language>
  <content>......</content>
</article>
```

### User Message

The first-round user message contains only the round goal plus the response-shape instruction.

```text
<turnGoal>请围绕文章主要人物、主题或整体场景提出第一个问题。</turnGoal>
请先做简短反馈，再用一个明确问题收尾。
```

Later-round user messages contain only the normalized student answer, the next round goal, and the response-shape instruction.

```text
<studentAnswer>我不知道</studentAnswer>
<turnGoal>请追问文章中的具体细节、行为或事件。</turnGoal>
请先做简短反馈，再用一个明确问题收尾。
```

For the final round, the last line changes to:

```text
请直接做总结并结束，不要再向学生提问。
```

### Assistant Message

Each assistant message is the model's original teacher output for that round. It is stored and replayed verbatim as chat history. The prompt layer does not rewrite or summarize assistant content.

## Message Reconstruction Algorithm

Before each LLM request, rebuild the entire message list from session state.

1. Read `article`, `currentRoundNo`, and `turns` from `ReadingSession`.
2. Create the single fixed `system` message from teacher rules plus the XML-wrapped article block.
3. Append the first-round `user` message using `RoundPlanner.goalForRound(1)`.
4. Iterate over `ReadingSession.turns` in order.
5. For each turn, append one `assistant` message using `teacherReplyFinal`.
6. If the turn has a non-blank `studentAnswerNormalized`, compute `nextRoundNo = turn.roundNo() + 1`.
7. Use `RoundPlanner.goalForRound(nextRoundNo)` to build the next `user` message.
8. If that next round is final, append the final-round closing instruction; otherwise append the non-final closing instruction.
9. Send the resulting ordered message list to the LLM client.

Invariant:

- While generating round `N`, the last message in the list must be the `user` message for round `N`.

## Component Responsibilities

### `RoundPlanner`

File: `src/main/java/com/wlinsk/rd_machine/prompt/RoundPlanner.java`

Responsibility:

- Return `RoundGoal` by round number
- Remain the single source of truth for `instruction` and `finalRound`

Non-responsibility:

- It does not format prompts
- It does not know about conversation history

### `PromptBuilder`

File: `src/main/java/com/wlinsk/rd_machine/prompt/PromptBuilder.java`

New responsibility:

- Build the fixed `system` message
- Reconstruct `List<LlmMessage>` for the current request from `ReadingSession` and `RoundGoal`
- Wrap article metadata and article content in one consistent XML structure inside the `system` message

Removed responsibility:

- It no longer builds one large synthetic user prompt containing summaries and helper fields

### `BailianLlmClient`

File: `src/main/java/com/wlinsk/rd_machine/llm/BailianLlmClient.java`

New responsibility:

- Accept `List<LlmMessage>`
- Map each message to the OpenAI SDK request format in order
- Stream completion output exactly as before

Removed responsibility:

- It no longer assumes only one system message and one user message

### `AssistantStreamingOrchestrator`

File: `src/main/java/com/wlinsk/rd_machine/streaming/AssistantStreamingOrchestrator.java`

Responsibility after the change:

- Resolve the current `RoundGoal`
- Ask `PromptBuilder` for the chat message list
- Pass the list to `BailianLlmClient`
- Keep the rest of the streaming and TTS pipeline unchanged

### `ReadingSession`

File: `src/main/java/com/wlinsk/rd_machine/session/ReadingSession.java`

Responsibility after the change:

- Store the article, round counters, status flags, and canonical turn history
- Treat `turns` as the single source of truth for reconstructing conversation context

Fields that can be removed because they only support the old prompt strategy:

- `summaryContext`
- `pendingStudentAnswerRaw`
- `pendingStudentAnswerNormalized`
- `pendingAsrMeta`

Field to keep:

- `lastAssistantMessageText`

Reason to keep it:

- It is still useful for snapshot and UI convenience
- It is not used to build LLM context

### `ReadingTurn`

File: `src/main/java/com/wlinsk/rd_machine/session/ReadingTurn.java`

Responsibility:

- Persist the teacher reply for a completed round
- Persist both raw and normalized student answers
- Preserve ASR metadata for debugging and replay

Prompt rule:

- Only `studentAnswerNormalized` is injected into LLM context
- `studentAnswerRaw` remains stored for diagnostics, not prompt assembly

## Proposed Supporting Type

Add a lightweight prompt-domain type such as:

```java
public record LlmMessage(String role, String content) {
}
```

Reason:

- Keeps `PromptBuilder` tests independent from OpenAI SDK types
- Makes message reconstruction logic easy to assert
- Preserves a clean boundary between prompt logic and transport logic

## Data Flow

1. Session is created with one fixed article.
2. Round 1 request is built as `system + first user`.
3. LLM response is streamed and stored as the round's assistant output.
4. Student submits an answer.
5. The answer is normalized and stored in `ReadingTurn`.
6. The next LLM request is rebuilt from `system + historical assistant/user messages + current round user`.
7. The process repeats until the final round produces a summary instead of another question.

## Error Handling

The design does not change the existing stream failure behavior. Current error handling in the orchestrator remains valid.

Additional prompt-layer rules:

- Skip appending a `studentAnswer` block if `studentAnswerNormalized` is null or blank.
- Never append a next-round user message for a turn that has no student answer.
- Always derive final-round behavior from `RoundGoal.finalRound()` rather than hard-coding round text in multiple places.

## Testing Strategy

### PromptBuilder Unit Tests

Add tests that verify:

- Round 1 builds exactly two messages: one `system`, one `user`
- Round 2 rebuilds `system -> user(round1 goal) -> assistant(round1 output) -> user(student1 + round2 goal)`
- Final-round user message contains the summary-only closing instruction
- Non-final-round user message contains the question-ending closing instruction
- Prompt assembly uses `studentAnswerNormalized`, not `studentAnswerRaw`
- Prompt assembly does not include `summaryContext`
- Prompt assembly does not include "老师上一轮话术" style synthetic helper text

### LLM Client Unit Tests

Add tests that verify:

- `BailianLlmClient` maps `List<LlmMessage>` to request messages in the same order
- Roles are preserved correctly when creating the SDK request

### Session Unit Tests

Add tests that verify:

- `ReadingSession.turns` is sufficient to reconstruct the next-round context
- Removing prompt-cache fields does not break round progression
- Stored raw answers remain available even though only normalized answers enter the prompt

## Scope Boundaries

This design does not include:

- Context-window optimization for very long articles
- Summarization of older turns
- Mid-session article switching
- Changes to UI rendering behavior
- Changes to TTS segmentation or playback

These can be revisited later if session length or article size grows.

## Recommended Implementation Order

1. Introduce `LlmMessage`
2. Refactor `PromptBuilder` to produce `system` plus ordered message history
3. Refactor `BailianLlmClient` to accept a message list
4. Update `AssistantStreamingOrchestrator` to use the new API
5. Remove obsolete prompt-cache fields from `ReadingSession`
6. Add unit tests for prompt reconstruction, session behavior, and LLM message mapping

## Acceptance Criteria

The design is complete when all of the following are true:

- One session uses one stable `system` message containing the article and teacher rules
- Article metadata and article content are wrapped in XML tags inside the `system` message
- Each LLM request contains the real chronological chat history for that session
- `RoundPlanner` remains the source of round-specific goals
- Prompt construction no longer depends on recent-turn summaries or duplicated helper fields
- Session history can be reconstructed from canonical turn data
- Tests cover first-round, later-round, and final-round prompt assembly
