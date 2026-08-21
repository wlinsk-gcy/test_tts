package com.wlinsk.rd_machine.core.streaming;

import com.wlinsk.rd_machine.basic.enums.SysCode;
import com.wlinsk.rd_machine.basic.exception.BasicException;
import com.wlinsk.rd_machine.basic.model.bo.*;
import com.wlinsk.rd_machine.core.llm.LlmClient;
import com.wlinsk.rd_machine.core.llm.LlmDeltaListener;
import com.wlinsk.rd_machine.core.llm.prompt.PromptBuilder;
import com.wlinsk.rd_machine.core.llm.prompt.RoundPlanner;
import com.wlinsk.rd_machine.core.session.InMemorySessionStore;
import com.wlinsk.rd_machine.core.tts.*;
import com.wlinsk.rd_machine.transport.ws.AssistantEventPublisher;
import com.wlinsk.rd_machine.transport.ws.SessionConnectionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class AssistantStreamingOrchestrator {


    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    private final InMemorySessionStore sessionStore;
    private final RoundPlanner roundPlanner;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final AliyunRealtimeTtsService ttsService;
    private final AssistantTtsSessionManager assistantTtsSessionManager;
    private final AssistantEventPublisher eventPublisher;
    private final SessionConnectionRegistry connectionRegistry;
    private final ActiveAssistantTurnRegistry activeTurnRegistry;
    private final ExecutorService executorService;

    public AssistantStreamingOrchestrator(
            InMemorySessionStore sessionStore,
            RoundPlanner roundPlanner,
            PromptBuilder promptBuilder,
            LlmClient llmClient,
            AliyunRealtimeTtsService ttsService,
            AssistantTtsSessionManager assistantTtsSessionManager,
            AssistantEventPublisher eventPublisher,
            SessionConnectionRegistry connectionRegistry,
            ActiveAssistantTurnRegistry activeTurnRegistry,
            @Qualifier("assistantStreamingExecutor") ExecutorService executorService
    ) {
        this.sessionStore = sessionStore;
        this.roundPlanner = roundPlanner;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.ttsService = ttsService;
        this.assistantTtsSessionManager = assistantTtsSessionManager;
        this.eventPublisher = eventPublisher;
        this.connectionRegistry = connectionRegistry;
        this.activeTurnRegistry = activeTurnRegistry;
        this.executorService = executorService;
    }

    public void startAssistantTurn(String sessionId) {
        ActiveAssistantTurnHandle handle = activeTurnRegistry.register(sessionId);
        Future<?> future = executorService.submit(() -> streamTurn(sessionId, handle));
        handle.attachFuture(future);
    }

    private void streamTurn(String sessionId, ActiveAssistantTurnHandle handle) {
        ReadingSession session = sessionStore.findById(sessionId).orElse(null);
        if (session == null || handle.isCancelled() || session.isClosed()) {
            activeTurnRegistry.complete(sessionId, handle);
            return;
        }
        connectionRegistry.awaitAtLeastOneConnection(sessionId, Duration.ofMillis(800));
        int turnNo = session.getCurrentTurnNo();
        int roundNo = session.getCurrentRoundNo();
        StreamingSessionContext context = new StreamingSessionContext(sessionId, turnNo, roundNo);
        long turnStartedAtMs = System.currentTimeMillis();
        long turnStartedAtNs = System.nanoTime();
        Thread turnThread = Thread.currentThread();
        publishTiming(context, "turn.start", turnStartedAtMs, turnStartedAtNs);
        RoundGoal roundGoal = roundPlanner.goalForRound(roundNo);
        PromptContext promptContext = new PromptContext(session, roundGoal);
        List<LlmMessage> messages = promptBuilder.buildMessages(promptContext);
        StringBuilder fullText = new StringBuilder();
        TextSegmenter textSegmenter = ttsService.createTextSegmenter();
        TtsRealtimeSession ttsRealtimeSession = null;
        TtsUtterance ttsUtterance = null;
        AtomicBoolean sawFirstTextDelta = new AtomicBoolean();
        AtomicBoolean sawFirstAudioChunk = new AtomicBoolean();
        AtomicReference<Throwable> fatalFailure = new AtomicReference<>();
        AtomicReference<LlmUsage> llmUsageRef = new AtomicReference<>();
        AtomicLong ttsCharacters = new AtomicLong();

        try {
            if (handle.isCancelled() || session.isClosed()) {
                return;
            }
            if (ttsService.isConfigured()) {
                publishTiming(context, "tts.open.start", System.currentTimeMillis(), turnStartedAtNs);
                TtsSessionRef ttsSessionRef = assistantTtsSessionManager.getOrCreate(sessionId, session.getArticle().language());
                ttsRealtimeSession = ttsSessionRef.session();
                ttsUtterance = ttsRealtimeSession.openUtterance(new TtsAudioListener() {
                    @Override
                    public void onSessionReady() {
                        if (handle.isCancelled()) {
                            return;
                        }
                        publishTiming(context, "tts.session.ready", System.currentTimeMillis(), turnStartedAtNs);
                    }

                    @Override
                    public void onAudioChunk(int segmentSeq, byte[] audioBytes) {
                        if (handle.isCancelled()) {
                            return;
                        }
                        if (sawFirstAudioChunk.compareAndSet(false, true)) {
                            publishTiming(context, "tts.first.audio", System.currentTimeMillis(), turnStartedAtNs);
                        }
                        eventPublisher.publishAudioChunk(context, segmentSeq, ttsService.responseFormat(), ttsService.sampleRate(), audioBytes);
                    }

                    @Override
                    public void onUsage(TtsUsage usage) {
                        if (usage != null) {
                            ttsCharacters.addAndGet(usage.characters());
                        }
                    }

                    @Override
                    public void onCompleted() {
                        if (handle.isCancelled()) {
                            return;
                        }
                        publishTiming(context, "tts.stream.completed", System.currentTimeMillis(), turnStartedAtNs);
                        eventPublisher.publishAudioDone(context);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        if (handle.isCancelled() || session.isClosed()) {
                            return;
                        }
                        Throwable normalizedFailure = normalizeFailure(throwable);
                        markFatalFailure(fatalFailure, normalizedFailure, turnThread);
                        eventPublisher.publishError(context, failureCode(normalizedFailure, SysCode.TTS_STREAM_FAILED), failureMessage(normalizedFailure));
                    }
                });
                handle.attachTtsRealtimeSession(ttsRealtimeSession);
                publishTiming(context, "tts.open.returned", System.currentTimeMillis(), turnStartedAtNs);
            }

            TtsUtterance finalTtsUtterance = ttsUtterance;
            publishTiming(context, "llm.request.start", System.currentTimeMillis(), turnStartedAtNs);
            llmClient.streamChatCompletion(messages, new LlmDeltaListener() {
                @Override
                public void onDelta(String delta) {
                    throwIfFatalFailure(fatalFailure);
                    if (handle.isCancelled() || session.isClosed()) {
                        throw new CancellationException("Session closed");
                    }
                    if (sawFirstTextDelta.compareAndSet(false, true)) {
                        publishTiming(context, "llm.first.delta", System.currentTimeMillis(), turnStartedAtNs);
                    }

                    fullText.append(delta);
                    eventPublisher.publishTextDelta(context, delta);
                    List<TextSegment> segments = toTtsSegments(textSegmenter.append(delta), session.getArticle().language());
                    if (finalTtsUtterance != null) {
                        for (TextSegment segment : segments) {
                            throwIfFatalFailure(fatalFailure);
                            if (handle.isCancelled()) {
                                throw new CancellationException("Session closed");
                            }
                            finalTtsUtterance.enqueue(segment);
                        }
                    }
                }

                @Override
                public void onComplete(LlmUsage usage) {
                    llmUsageRef.set(usage);
                    throwIfFatalFailure(fatalFailure);
                    if (handle.isCancelled() || session.isClosed()) {
                        return;
                    }
                    publishTiming(context, "llm.stream.completed", System.currentTimeMillis(), turnStartedAtNs);
                    TextSegment remaining = textSegmenter.flushRemaining();
                    if (finalTtsUtterance != null && remaining != null) {
                        throwIfFatalFailure(fatalFailure);
                        finalTtsUtterance.enqueue(toTtsSegment(remaining, session.getArticle().language()));
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    throw new IllegalStateException(throwable);
                }
            }, () -> handle.isCancelled() || fatalFailure.get() != null);

            throwIfFatalFailure(fatalFailure);
            if (handle.isCancelled() || session.isClosed()) {
                return;
            }
            eventPublisher.publishTextDone(context, fullText.toString());

            if (ttsUtterance != null) {
                throwIfFatalFailure(fatalFailure);
                if (handle.isCancelled()) {
                    return;
                }
                ttsUtterance.finish();
                throwIfFatalFailure(fatalFailure);
                if (handle.isCancelled()) {
                    return;
                }
                ttsUtterance.awaitFinished(Duration.ofSeconds(60));
                throwIfFatalFailure(fatalFailure);
            } else {
                if (handle.isCancelled()) {
                    return;
                }
                eventPublisher.publishAudioDone(context);
            }

            throwIfFatalFailure(fatalFailure);
            if (handle.isCancelled() || session.isClosed()) {
                return;
            }
            session.markAssistantTurnCompleted(fullText.toString());
            publishTiming(context, "turn.completed", System.currentTimeMillis(), turnStartedAtNs);
            eventPublisher.publishUsage(context, llmUsageRef.get(), ttsCharacters.get());
            eventPublisher.publishTurnDone(context, session.isAwaitingStudentAnswer());
        } catch (CancellationException exception) {
            Thread.interrupted();
            Throwable failure = fatalFailure.get();
            if (failure != null && !session.isClosed()) {
                session.markFailed();
                eventPublisher.publishError(context, failureCode(failure, SysCode.ASSISTANT_STREAM_FAILED), failureMessage(failure));
            }
            assistantTtsSessionManager.close(sessionId);
        } catch (Exception exception) {
            Thread.interrupted();
            Throwable failure = fatalFailure.get();
            Throwable effectiveFailure = failure != null ? failure : normalizeFailure(exception);
            if (!session.isClosed() && (failure != null || !handle.isCancelled())) {
                session.markFailed();
                eventPublisher.publishError(context, failureCode(effectiveFailure, SysCode.ASSISTANT_STREAM_FAILED), failureMessage(effectiveFailure));
            }
            assistantTtsSessionManager.close(sessionId);
        } finally {
            activeTurnRegistry.complete(sessionId, handle);
        }
    }

    private void markFatalFailure(AtomicReference<Throwable> fatalFailure, Throwable throwable, Thread turnThread) {
        if (throwable == null) {
            return;
        }
        if (fatalFailure.compareAndSet(null, throwable)) {
            turnThread.interrupt();
        }
    }

    private void throwIfFatalFailure(AtomicReference<Throwable> fatalFailure) {
        Throwable throwable = fatalFailure.get();
        if (throwable == null) {
            return;
        }
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException(throwable);
    }

    public static String failureCode(Throwable throwable, SysCode defaultCode) {
        if (throwable instanceof BasicException basicException && basicException.getStatus() != null && !basicException.getStatus().isBlank()) {
            return basicException.getStatus();
        }
        return defaultCode.getCode();
    }

    public static List<TextSegment> toTtsSegments(List<TextSegment> segments, String language) {
        return segments.stream()
                .map(segment -> toTtsSegment(segment, language))
                .toList();
    }

    public static TextSegment toTtsSegment(TextSegment segment, String language) {
        return TtsTextNormalizer.normalize(segment, language);
    }

    private Throwable normalizeFailure(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return normalizeFailure(completionException.getCause());
        }
        if (throwable instanceof IllegalStateException illegalStateException && illegalStateException.getCause() != null) {
            return normalizeFailure(illegalStateException.getCause());
        }
        return throwable;
    }

    private String failureMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown failure";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    private void publishTiming(StreamingSessionContext context, String phase, long serverTimestampMs, long turnStartedAtNs) {
        long elapsedMs = "turn.start".equals(phase)
                ? 0L
                : Math.max(0L, (System.nanoTime() - turnStartedAtNs) / 1_000_000L);
        log.info("assistant-phase sessionId={} turnNo={} roundNo={} phase={} elapsedMs={}",
                context.sessionId(), context.turnNo(), context.roundNo(), phase, elapsedMs);
        if("dev".equals(activeProfiles)){
            eventPublisher.publishTiming(context, phase, serverTimestampMs, elapsedMs);
        }
    }
}



