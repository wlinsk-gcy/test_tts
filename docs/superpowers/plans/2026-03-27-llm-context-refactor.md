# LLM Context Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor prompt assembly from one synthetic `systemPrompt + userPrompt` pair into a true multi-turn chat message list with XML-wrapped article context in the stable `system` message.

**Architecture:** Introduce a lightweight `LlmMessage` record shared by the prompt layer and LLM client. `PromptBuilder` will rebuild the full `system -> user -> assistant -> user` sequence across all completed turns from `ReadingSession.turns` plus `RoundPlanner`, `BailianLlmClient` will map that list into OpenAI SDK chat params, and `ReadingSession` will drop prompt-cache fields so canonical turn history is the only source of conversational state.

**Tech Stack:** Java 21, Spring Boot 4.0.5, openai-java 4.29.1, JUnit 5, Maven Wrapper

---

## File Structure

- Create: `src/main/java/com/wlinsk/rd_machine/llm/LlmMessage.java`
  Purpose: Prompt-domain message type shared by `PromptBuilder` and `BailianLlmClient`.
- Modify: `src/main/java/com/wlinsk/rd_machine/prompt/PromptBuilder.java`
  Purpose: Replace synthetic string prompts with ordered `List<LlmMessage>` reconstruction, XML article wrapping, and round-aware user-message formatting.
- Modify: `src/main/java/com/wlinsk/rd_machine/llm/BailianLlmClient.java`
  Purpose: Accept `List<LlmMessage>`, convert each role to the SDK message type, and keep streaming behavior unchanged.
- Modify: `src/main/java/com/wlinsk/rd_machine/streaming/AssistantStreamingOrchestrator.java`
  Purpose: Request a message list from `PromptBuilder` and pass it into `BailianLlmClient`.
- Modify: `src/main/java/com/wlinsk/rd_machine/session/ReadingSession.java`
  Purpose: Remove prompt-only cache fields while preserving canonical turn history and UI-facing `lastAssistantMessageText`.
- Create: `src/test/java/com/wlinsk/rd_machine/prompt/PromptBuilderTest.java`
  Purpose: Lock prompt reconstruction semantics for opening rounds, follow-up rounds, final rounds, XML escaping, and legacy-field removal.
- Create: `src/test/java/com/wlinsk/rd_machine/llm/BailianLlmClientTest.java`
  Purpose: Verify role-preserving conversion from `LlmMessage` to `ChatCompletionCreateParams`.
- Create: `src/test/java/com/wlinsk/rd_machine/session/ReadingSessionTest.java`
  Purpose: Guard session-state cleanup and ensure raw/normalized answers still persist in canonical turn history.

### Task 1: Introduce Prompt-Domain Messages And Rebuild Prompt History

**Files:**
- Create: `src/main/java/com/wlinsk/rd_machine/llm/LlmMessage.java`
- Modify: `src/main/java/com/wlinsk/rd_machine/prompt/PromptBuilder.java`
- Test: `src/test/java/com/wlinsk/rd_machine/prompt/PromptBuilderTest.java`

- [ ] **Step 1: Write the failing prompt reconstruction tests**

```java
package com.wlinsk.rd_machine.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wlinsk.rd_machine.article.ArticleDetail;
import com.wlinsk.rd_machine.llm.LlmMessage;
import com.wlinsk.rd_machine.session.ReadingSession;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {

    private final RoundPlanner roundPlanner = new RoundPlanner();
    private final PromptBuilder promptBuilder = new PromptBuilder(roundPlanner);

    @Test
    void buildsRoundOneAsSystemPlusOpeningUserMessage() {
        ReadingSession session = new ReadingSession("session-1", article("內容含有 <scene> 與 & 符號"));

        List<LlmMessage> messages = promptBuilder.buildMessages(
                new PromptContext(session, roundPlanner.goalForRound(1))
        );

        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("user", messages.get(1).role());
        assertTrue(messages.get(0).content().contains("<article>"));
        assertTrue(messages.get(0).content().contains("<title>少年閏土</title>"));
        assertTrue(messages.get(0).content().contains("內容含有 &lt;scene&gt; 與 &amp; 符號"));
        assertTrue(messages.get(1).content().contains("<turnGoal>请围绕文章主要人物、主题或整体场景提出第一个问题。</turnGoal>"));
        assertTrue(messages.get(1).content().contains("请先做简短反馈，再用一个明确问题收尾。"));
        assertFalse(messages.get(1).content().contains("最近几轮摘要"));
    }

    @Test
    void rebuildsRoundTwoFromAssistantHistoryAndNormalizedStudentAnswer() {
        ReadingSession session = new ReadingSession("session-2", article("我於是日日盼望新年。"));
        session.markAssistantTurnCompleted("請你先描述閏土的外貌。");
        session.acceptStudentAnswer(1L, "  我   不知道  ", Map.of("source", "test"));

        List<LlmMessage> messages = promptBuilder.buildMessages(
                new PromptContext(session, roundPlanner.goalForRound(2))
        );

        assertEquals(4, messages.size());
        assertEquals("user", messages.get(1).role());
        assertEquals("assistant", messages.get(2).role());
        assertEquals("請你先描述閏土的外貌。", messages.get(2).content());
        assertEquals("user", messages.get(3).role());
        assertTrue(messages.get(3).content().contains("<studentAnswer>我 不知道</studentAnswer>"));
        assertTrue(messages.get(3).content().contains("<turnGoal>请追问文章中的具体细节、行为或事件。</turnGoal>"));
        assertFalse(messages.get(3).content().contains("老师上一轮话术"));
    }

    @Test
    void usesFinalRoundClosingInstructionInsteadOfQuestionEnding() {
        ReadingSession session = new ReadingSession("session-3", article("我便飛跑地去看。"));

        advanceRound(session, "第一輪老師", "第一輪學生", 1L);
        advanceRound(session, "第二輪老師", "第二輪學生", 2L);
        advanceRound(session, "第三輪老師", "第三輪學生", 3L);
        advanceRound(session, "第四輪老師", "第四輪學生", 4L);

        List<LlmMessage> messages = promptBuilder.buildMessages(
                new PromptContext(session, roundPlanner.goalForRound(5))
        );

        String finalUserMessage = messages.getLast().content();
        assertTrue(finalUserMessage.contains("<turnGoal>请对本次学习做简短总结，并给出鼓励性的收束反馈。</turnGoal>"));
        assertTrue(finalUserMessage.contains("请直接做总结并结束，不要再向学生提问。"));
        assertFalse(finalUserMessage.contains("请先做简短反馈，再用一个明确问题收尾。"));
    }

    private ArticleDetail article(String content) {
        return new ArticleDetail("juvenile-runtu", "少年閏土", "魯迅", "zh-HK", content);
    }

    private void advanceRound(ReadingSession session, String teacherReply, String studentReply, long clientSeq) {
        session.markAssistantTurnCompleted(teacherReply);
        session.acceptStudentAnswer(clientSeq, studentReply, Map.of("source", "test"));
    }
}
```

- [ ] **Step 2: Run the prompt tests to verify they fail**

Run: `.\mvnw test -Dtest=PromptBuilderTest`
Expected: FAIL with compilation errors because `LlmMessage` does not exist and `PromptBuilder` does not expose `buildMessages(PromptContext)`.

- [ ] **Step 3: Add the prompt-domain message record**

```java
package com.wlinsk.rd_machine.llm;

public record LlmMessage(String role, String content) {
}
```

- [ ] **Step 4: Refactor `PromptBuilder` to build ordered chat history**

```java
package com.wlinsk.rd_machine.prompt;

import com.wlinsk.rd_machine.article.ArticleDetail;
import com.wlinsk.rd_machine.llm.LlmMessage;
import com.wlinsk.rd_machine.session.ReadingSession;
import com.wlinsk.rd_machine.session.ReadingTurn;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PromptBuilder {

    private static final String SYSTEM_RULES = "你是一名语言学习老师。你只能基于给定文章内容提问、评价和引导。"
            + "输出必须适合语音播报，保持自然、简洁、稳定，不要使用 Markdown、HTML、emoji 或列表。"
            + "每次输出控制在 3 到 5 句话以内。"
            + "如果学生回答可能受到 ASR 误识别影响，请结合上下文做温和理解，不要直接指出识别错误。";
    private static final String FOLLOW_UP_CLOSING = "请先做简短反馈，再用一个明确问题收尾。";
    private static final String FINAL_CLOSING = "请直接做总结并结束，不要再向学生提问。";

    private final RoundPlanner roundPlanner;

    public PromptBuilder(RoundPlanner roundPlanner) {
        this.roundPlanner = roundPlanner;
    }

    public List<LlmMessage> buildMessages(PromptContext context) {
        ReadingSession session = context.session();
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("system", buildSystemPrompt(session.getArticle())));

        if (session.getTurns().isEmpty()) {
            messages.add(new LlmMessage("user", buildUserTurnMessage(null, context.roundGoal())));
            return List.copyOf(messages);
        }

        messages.add(new LlmMessage("user", buildUserTurnMessage(null, roundPlanner.goalForRound(1))));
        for (ReadingTurn turn : session.getTurns()) {
            if (hasText(turn.teacherReplyFinal())) {
                messages.add(new LlmMessage("assistant", turn.teacherReplyFinal().trim()));
            }
            if (hasText(turn.studentAnswerNormalized())) {
                int nextRoundNo = turn.roundNo() + 1;
                RoundGoal nextGoal = nextRoundNo == context.roundGoal().roundNo()
                        ? context.roundGoal()
                        : roundPlanner.goalForRound(nextRoundNo);
                messages.add(new LlmMessage("user", buildUserTurnMessage(turn.studentAnswerNormalized(), nextGoal)));
            }
        }
        return List.copyOf(messages);
    }

    private String buildSystemPrompt(ArticleDetail article) {
        return SYSTEM_RULES + "\n\n"
                + "<article>\n"
                + "  <title>" + escapeXml(article.title()) + "</title>\n"
                + "  <author>" + escapeXml(article.author()) + "</author>\n"
                + "  <language>" + escapeXml(article.language()) + "</language>\n"
                + "  <content>" + escapeXml(article.content()) + "</content>\n"
                + "</article>";
    }

    private String buildUserTurnMessage(String studentAnswer, RoundGoal goal) {
        StringBuilder builder = new StringBuilder();
        if (hasText(studentAnswer)) {
            builder.append("<studentAnswer>")
                    .append(escapeXml(studentAnswer.trim()))
                    .append("</studentAnswer>\n");
        }
        builder.append("<turnGoal>")
                .append(escapeXml(goal.instruction()))
                .append("</turnGoal>\n")
                .append(goal.finalRound() ? FINAL_CLOSING : FOLLOW_UP_CLOSING);
        return builder.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
```

- [ ] **Step 5: Run the prompt tests again**

Run: `.\mvnw test -Dtest=PromptBuilderTest`
Expected: PASS with 3 tests green.

- [ ] **Step 6: Commit the prompt-layer refactor**

```bash
git add src/main/java/com/wlinsk/rd_machine/llm/LlmMessage.java src/main/java/com/wlinsk/rd_machine/prompt/PromptBuilder.java src/test/java/com/wlinsk/rd_machine/prompt/PromptBuilderTest.java
git commit -m "refactor: rebuild llm context from chat history"
```

### Task 2: Refactor The LLM Client To Accept Message Lists

**Files:**
- Modify: `src/main/java/com/wlinsk/rd_machine/llm/BailianLlmClient.java`
- Test: `src/test/java/com/wlinsk/rd_machine/llm/BailianLlmClientTest.java`

- [ ] **Step 1: Write the failing LLM client mapping tests**

```java
package com.wlinsk.rd_machine.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.wlinsk.rd_machine.config.AiLlmProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class BailianLlmClientTest {

    @Test
    void buildsOpenAiParamsInTheSameOrderAsPromptMessages() {
        AiLlmProperties properties = new AiLlmProperties();
        properties.setModel("qwen-plus");
        BailianLlmClient client = new BailianLlmClient(properties, new BailianLlmService(properties));

        ChatCompletionCreateParams params = client.buildParams(List.of(
                new LlmMessage("system", "<article><title>少年閏土</title></article>"),
                new LlmMessage("user", "<turnGoal>請提第一個問題</turnGoal>"),
                new LlmMessage("assistant", "你先說說閏土給你的第一印象。")
        ));

        List<ChatCompletionMessageParam> messages = params.messages();
        assertEquals(3, messages.size());
        assertTrue(messages.get(0).isSystem());
        assertTrue(messages.get(1).isUser());
        assertTrue(messages.get(2).isAssistant());
        assertEquals("<article><title>少年閏土</title></article>", messages.get(0).asSystem().content().asText());
        assertEquals("<turnGoal>請提第一個問題</turnGoal>", messages.get(1).asUser().content().asText());
        assertEquals("你先說說閏土給你的第一印象。", messages.get(2).asAssistant().content().asText());
    }

    @Test
    void rejectsUnsupportedMessageRoles() {
        AiLlmProperties properties = new AiLlmProperties();
        properties.setModel("qwen-plus");
        BailianLlmClient client = new BailianLlmClient(properties, new BailianLlmService(properties));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> client.buildParams(List.of(new LlmMessage("tool", "unsupported")))
        );

        assertEquals("Unsupported role: tool", error.getMessage());
    }
}
```

- [ ] **Step 2: Run the LLM client tests to verify they fail**

Run: `.\mvnw test -Dtest=BailianLlmClientTest`
Expected: FAIL with compilation errors because `buildParams(List<LlmMessage>)` does not exist and `streamChatCompletion(List<LlmMessage>, LlmDeltaListener)` does not exist and the client still expects two strings.

- [ ] **Step 3: Refactor `BailianLlmClient` around `List<LlmMessage>`**

```java
package com.wlinsk.rd_machine.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.wlinsk.rd_machine.config.AiLlmProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BailianLlmClient {

    private final AiLlmProperties properties;
    private final BailianLlmService llmService;

    public BailianLlmClient(AiLlmProperties properties, BailianLlmService llmService) {
        this.properties = properties;
        this.llmService = llmService;
    }

    public void streamChatCompletion(List<LlmMessage> messages, LlmDeltaListener listener) {
        ChatCompletionCreateParams params = buildParams(messages);
        OpenAIClient openAIClient = OpenAIOkHttpClient.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .timeout(Duration.ofMinutes(2))
                .build();
        try (StreamResponse<ChatCompletionChunk> response = openAIClient.chat().completions().createStreaming(params)) {
            response.stream()
                    .flatMap(chunk -> chunk.choices().stream())
                    .flatMap(choice -> choice.delta().content().stream())
                    .filter(delta -> !delta.isBlank())
                    .forEach(listener::onDelta);
            listener.onComplete();
        } catch (Exception exception) {
            listener.onError(exception);
            throw exception;
        }
    }

    ChatCompletionCreateParams buildParams(List<LlmMessage> messages) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(llmService.getModel());
        messages.forEach(message -> builder.addMessage(toMessageParam(message)));
        return builder.build();
    }

    private ChatCompletionMessageParam toMessageParam(LlmMessage message) {
        return switch (message.role()) {
            case "system" -> ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                            .content(ChatCompletionSystemMessageParam.Content.ofText(message.content()))
                            .build()
            );
            case "user" -> ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                            .content(ChatCompletionUserMessageParam.Content.ofText(message.content()))
                            .build()
            );
            case "assistant" -> ChatCompletionMessageParam.ofAssistant(
                    ChatCompletionAssistantMessageParam.builder()
                            .content(ChatCompletionAssistantMessageParam.Content.ofText(message.content()))
                            .build()
            );
            default -> throw new IllegalArgumentException("Unsupported role: " + message.role());
        };
    }
}
```

- [ ] **Step 4: Run the prompt and client tests together**

Run: `.\mvnw test -Dtest=PromptBuilderTest,BailianLlmClientTest`
Expected: PASS with both test classes green.

- [ ] **Step 5: Commit the LLM client refactor**

```bash
git add src/main/java/com/wlinsk/rd_machine/llm/BailianLlmClient.java src/test/java/com/wlinsk/rd_machine/llm/BailianLlmClientTest.java
git commit -m "refactor: send ordered chat messages to llm"
```

### Task 3: Remove Prompt Cache State And Wire The New Message Flow

**Files:**
- Modify: `src/main/java/com/wlinsk/rd_machine/streaming/AssistantStreamingOrchestrator.java`
- Modify: `src/main/java/com/wlinsk/rd_machine/session/ReadingSession.java`
- Test: `src/test/java/com/wlinsk/rd_machine/session/ReadingSessionTest.java`

- [ ] **Step 1: Write the failing session cleanup tests**

```java
package com.wlinsk.rd_machine.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wlinsk.rd_machine.article.ArticleDetail;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReadingSessionTest {

    @Test
    void removesPromptCacheFieldsFromSessionState() {
        Set<String> fieldNames = Arrays.stream(ReadingSession.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertFalse(fieldNames.contains("summaryContext"));
        assertFalse(fieldNames.contains("pendingStudentAnswerRaw"));
        assertFalse(fieldNames.contains("pendingStudentAnswerNormalized"));
        assertFalse(fieldNames.contains("pendingAsrMeta"));
    }

    @Test
    void keepsRawAndNormalizedAnswersInsideCanonicalTurnHistory() {
        ReadingSession session = new ReadingSession(
                "session-1",
                new ArticleDetail("juvenile-runtu", "少年閏土", "魯迅", "zh-HK", "我便飛跑地去看。")
        );

        session.markAssistantTurnCompleted("請你描述一下閏土。");
        session.acceptStudentAnswer(1L, "  我   不知道  ", Map.of("source", "test"));

        ReadingTurn turn = session.getTurns().getFirst();
        assertEquals("  我   不知道  ", turn.studentAnswerRaw());
        assertEquals("我 不知道", turn.studentAnswerNormalized());
        assertEquals("請你描述一下閏土。", turn.teacherReplyFinal());
        assertEquals(SessionStatus.GENERATING, session.getStatus());
        assertEquals(2, session.getCurrentRoundNo());
        assertTrue(session.getLastAssistantMessageText().contains("請你描述一下閏土。"));
    }
}
```

- [ ] **Step 2: Run the session cleanup tests to verify they fail**

Run: `.\mvnw test -Dtest=ReadingSessionTest`
Expected: FAIL because `ReadingSession` still declares the four prompt-cache fields.

- [ ] **Step 3: Remove prompt-cache fields and switch the orchestrator to message lists**

```java
// src/main/java/com/wlinsk/rd_machine/session/ReadingSession.java
package com.wlinsk.rd_machine.session;

import com.wlinsk.rd_machine.article.ArticleDetail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReadingSession {

    private final String sessionId;
    private final ArticleDetail article;
    private final Instant createdAt;
    private final List<ReadingTurn> turns = new ArrayList<>();
    private SessionStatus status;
    private int currentRoundNo;
    private int currentTurnNo;
    private boolean awaitingStudentAnswer;
    private String lastAssistantMessageText;
    private Long lastClientSeq;
    private Instant updatedAt;

    public ReadingSession(String sessionId, ArticleDetail article) {
        this.sessionId = sessionId;
        this.article = article;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = SessionStatus.CREATED;
        this.currentRoundNo = 1;
        this.currentTurnNo = 1;
        this.awaitingStudentAnswer = false;
    }

    public synchronized String getSessionId() {
        return sessionId;
    }

    public synchronized ArticleDetail getArticle() {
        return article;
    }

    public synchronized Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized SessionStatus getStatus() {
        return status;
    }

    public synchronized int getCurrentRoundNo() {
        return currentRoundNo;
    }

    public synchronized int getCurrentTurnNo() {
        return currentTurnNo;
    }

    public synchronized boolean isAwaitingStudentAnswer() {
        return awaitingStudentAnswer;
    }

    public synchronized String getLastAssistantMessageText() {
        return lastAssistantMessageText;
    }

    public synchronized Long getLastClientSeq() {
        return lastClientSeq;
    }

    public synchronized Instant getUpdatedAt() {
        return updatedAt;
    }

    public synchronized List<ReadingTurn> getTurns() {
        return List.copyOf(turns);
    }

    public synchronized void markGenerating() {
        this.status = SessionStatus.GENERATING;
        this.awaitingStudentAnswer = false;
        touch();
    }

    public synchronized boolean acceptStudentAnswer(long clientSeq, String rawText, Map<String, Object> asrMeta) {
        if (lastClientSeq != null && lastClientSeq == clientSeq) {
            return false;
        }
        if (!awaitingStudentAnswer || status != SessionStatus.WAITING_STUDENT) {
            throw new IllegalStateException("Session is not waiting for student input");
        }
        String normalized = normalizeStudentAnswer(rawText);
        turns.add(new ReadingTurn(
                currentTurnNo,
                currentRoundNo,
                lastAssistantMessageText,
                rawText,
                normalized,
                asrMeta,
                TurnDecision.NEXT_ROUND,
                updatedAt,
                Instant.now()
        ));
        lastClientSeq = clientSeq;
        currentRoundNo = Math.min(currentRoundNo + 1, 5);
        currentTurnNo = currentTurnNo + 1;
        status = SessionStatus.GENERATING;
        awaitingStudentAnswer = false;
        touch();
        return true;
    }

    public synchronized void markAssistantTurnCompleted(String assistantText) {
        this.lastAssistantMessageText = assistantText;
        if (currentRoundNo >= 5) {
            turns.add(new ReadingTurn(
                    currentTurnNo,
                    currentRoundNo,
                    assistantText,
                    null,
                    null,
                    Map.of(),
                    TurnDecision.FINISH,
                    updatedAt,
                    Instant.now()
            ));
            status = SessionStatus.COMPLETED;
            awaitingStudentAnswer = false;
        } else {
            status = SessionStatus.WAITING_STUDENT;
            awaitingStudentAnswer = true;
        }
        touch();
    }

    public synchronized void markFailed() {
        this.status = SessionStatus.FAILED;
        this.awaitingStudentAnswer = false;
        touch();
    }

    private String normalizeStudentAnswer(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText.trim().replaceAll("\\s+", " ");
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
```

```java
// src/main/java/com/wlinsk/rd_machine/streaming/AssistantStreamingOrchestrator.java
import com.wlinsk.rd_machine.llm.LlmMessage;

PromptContext promptContext = new PromptContext(session, roundGoal);
List<LlmMessage> messages = promptBuilder.buildMessages(promptContext);
StringBuilder fullText = new StringBuilder();
TextSegmenter textSegmenter = ttsService.createTextSegmenter();
TtsStreamSession ttsStreamSession = null;
AtomicBoolean sawFirstTextDelta = new AtomicBoolean();
AtomicBoolean sawFirstAudioChunk = new AtomicBoolean();

publishTiming(context, "llm.request.start", System.currentTimeMillis(), turnStartedAtNs);
llmClient.streamChatCompletion(messages, new LlmDeltaListener() {
    @Override
    public void onDelta(String delta) {
        if (sawFirstTextDelta.compareAndSet(false, true)) {
            publishTiming(context, "llm.first.delta", System.currentTimeMillis(), turnStartedAtNs);
        }
        fullText.append(delta);
        eventPublisher.publishTextDelta(context, delta);
        List<TextSegment> segments = textSegmenter.append(delta);
        if (finalTtsStreamSession != null) {
            segments.forEach(finalTtsStreamSession::enqueue);
        }
    }

    @Override
    public void onComplete() {
        publishTiming(context, "llm.stream.completed", System.currentTimeMillis(), turnStartedAtNs);
        TextSegment remaining = textSegmenter.flushRemaining();
        if (finalTtsStreamSession != null && remaining != null) {
            finalTtsStreamSession.enqueue(remaining);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        throw new IllegalStateException(throwable);
    }
});
```

- [ ] **Step 4: Run targeted tests, then the full suite**

Run: `.\mvnw test -Dtest=PromptBuilderTest,BailianLlmClientTest,ReadingSessionTest`
Expected: PASS with the prompt, client, and session tests green.

Run: `.\mvnw test`
Expected: PASS for the full backend test suite.

- [ ] **Step 5: Commit the session cleanup and orchestration wiring**

```bash
git add src/main/java/com/wlinsk/rd_machine/streaming/AssistantStreamingOrchestrator.java src/main/java/com/wlinsk/rd_machine/session/ReadingSession.java src/test/java/com/wlinsk/rd_machine/session/ReadingSessionTest.java
git commit -m "refactor: remove prompt cache state from reading session"
```
