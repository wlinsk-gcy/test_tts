package com.wlinsk.rd_machine.streaming;

import com.wlinsk.rd_machine.llm.BailianLlmClient;
import com.wlinsk.rd_machine.llm.LlmMessage;
import com.wlinsk.rd_machine.llm.LlmDeltaListener;
import com.wlinsk.rd_machine.prompt.PromptBuilder;
import com.wlinsk.rd_machine.prompt.PromptContext;
import com.wlinsk.rd_machine.prompt.RoundGoal;
import com.wlinsk.rd_machine.prompt.RoundPlanner;
import com.wlinsk.rd_machine.session.InMemorySessionStore;
import com.wlinsk.rd_machine.session.ReadingSession;
import com.wlinsk.rd_machine.transport.ws.AssistantEventPublisher;
import com.wlinsk.rd_machine.transport.ws.SessionConnectionRegistry;
import com.wlinsk.rd_machine.tts.AliyunRealtimeTtsService;
import com.wlinsk.rd_machine.tts.TtsAudioListener;
import com.wlinsk.rd_machine.tts.TtsStreamSession;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AssistantStreamingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AssistantStreamingOrchestrator.class);

    private final InMemorySessionStore sessionStore;
    private final RoundPlanner roundPlanner;
    private final PromptBuilder promptBuilder;
    private final BailianLlmClient llmClient;
    private final AliyunRealtimeTtsService ttsService;
    private final AssistantEventPublisher eventPublisher;
    private final SessionConnectionRegistry connectionRegistry;
    private final ExecutorService executorService;

    public AssistantStreamingOrchestrator(
            InMemorySessionStore sessionStore,
            RoundPlanner roundPlanner,
            PromptBuilder promptBuilder,
            BailianLlmClient llmClient,
            AliyunRealtimeTtsService ttsService,
            AssistantEventPublisher eventPublisher,
            SessionConnectionRegistry connectionRegistry,
            ExecutorService executorService
    ) {
        this.sessionStore = sessionStore;
        this.roundPlanner = roundPlanner;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.ttsService = ttsService;
        this.eventPublisher = eventPublisher;
        this.connectionRegistry = connectionRegistry;
        this.executorService = executorService;
    }

    public void startAssistantTurn(String sessionId) {
        executorService.submit(() -> streamTurn(sessionId));
    }

    private void streamTurn(String sessionId) {
        ReadingSession session = sessionStore.getRequired(sessionId);
        connectionRegistry.awaitAtLeastOneConnection(sessionId, Duration.ofMillis(800));
        int turnNo = session.getCurrentTurnNo();
        int roundNo = session.getCurrentRoundNo();
        StreamingSessionContext context = new StreamingSessionContext(sessionId, turnNo, roundNo);
        long turnStartedAtMs = System.currentTimeMillis();
        long turnStartedAtNs = System.nanoTime();
        publishTiming(context, "turn.start", turnStartedAtMs, turnStartedAtNs);
        RoundGoal roundGoal = roundPlanner.goalForRound(roundNo);
        PromptContext promptContext = new PromptContext(session, roundGoal);
        List<LlmMessage> messages = promptBuilder.buildMessages(promptContext);
        StringBuilder fullText = new StringBuilder();
        TextSegmenter textSegmenter = ttsService.createTextSegmenter();
        TtsStreamSession ttsStreamSession = null;
        AtomicBoolean sawFirstTextDelta = new AtomicBoolean();
        AtomicBoolean sawFirstAudioChunk = new AtomicBoolean();

        try {
            if (ttsService.isConfigured()) {
                publishTiming(context, "tts.open.start", System.currentTimeMillis(), turnStartedAtNs);
                ttsStreamSession = ttsService.openSession(session.getArticle().language(), new TtsAudioListener() {
                    @Override
                    public void onSessionReady() {
                        publishTiming(context, "tts.session.ready", System.currentTimeMillis(), turnStartedAtNs);
                    }

                    @Override
                    public void onAudioChunk(int segmentSeq, byte[] audioBytes) {
                        if (sawFirstAudioChunk.compareAndSet(false, true)) {
                            publishTiming(context, "tts.first.audio", System.currentTimeMillis(), turnStartedAtNs);
                        }
                        eventPublisher.publishAudioChunk(context, segmentSeq, ttsService.responseFormat(), ttsService.sampleRate(), audioBytes);
                    }

                    @Override
                    public void onCompleted() {
                        publishTiming(context, "tts.stream.completed", System.currentTimeMillis(), turnStartedAtNs);
                        eventPublisher.publishAudioDone(context);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        eventPublisher.publishError(context, "TTS_STREAM_FAILED", throwable.getMessage());
                    }
                });
                publishTiming(context, "tts.open.returned", System.currentTimeMillis(), turnStartedAtNs);
            }

            TtsStreamSession finalTtsStreamSession = ttsStreamSession;
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

            eventPublisher.publishTextDone(context, fullText.toString());

            if (ttsStreamSession != null) {
                ttsStreamSession.finish();
                ttsStreamSession.awaitFinished(Duration.ofSeconds(60));
                ttsStreamSession.close();
            } else {
                eventPublisher.publishAudioDone(context);
            }

            session.markAssistantTurnCompleted(fullText.toString());
            publishTiming(context, "turn.completed", System.currentTimeMillis(), turnStartedAtNs);
            eventPublisher.publishTurnDone(context, session.isAwaitingStudentAnswer());
        } catch (Exception exception) {
            session.markFailed();
            eventPublisher.publishError(context, "ASSISTANT_STREAM_FAILED", exception.getMessage());
            if (ttsStreamSession != null) {
                try {
                    ttsStreamSession.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void publishTiming(StreamingSessionContext context, String phase, long serverTimestampMs, long turnStartedAtNs) {
        long elapsedMs = "turn.start".equals(phase)
                ? 0L
                : Math.max(0L, (System.nanoTime() - turnStartedAtNs) / 1_000_000L);
        log.info("assistant-phase sessionId={} turnNo={} roundNo={} phase={} elapsedMs={}",
                context.sessionId(), context.turnNo(), context.roundNo(), phase, elapsedMs);
        eventPublisher.publishTiming(context, phase, serverTimestampMs, elapsedMs);
    }
}
