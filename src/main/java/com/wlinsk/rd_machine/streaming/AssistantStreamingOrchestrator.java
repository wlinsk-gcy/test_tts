package com.wlinsk.rd_machine.streaming;

import com.wlinsk.rd_machine.llm.BailianLlmClient;
import com.wlinsk.rd_machine.llm.LlmDeltaListener;
import com.wlinsk.rd_machine.llm.LlmMessage;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final ActiveAssistantTurnRegistry activeTurnRegistry;
    private final ExecutorService executorService;

    public AssistantStreamingOrchestrator(
            InMemorySessionStore sessionStore,
            RoundPlanner roundPlanner,
            PromptBuilder promptBuilder,
            BailianLlmClient llmClient,
            AliyunRealtimeTtsService ttsService,
            AssistantEventPublisher eventPublisher,
            SessionConnectionRegistry connectionRegistry,
            ActiveAssistantTurnRegistry activeTurnRegistry,
            ExecutorService executorService
    ) {
        this.sessionStore = sessionStore;
        this.roundPlanner = roundPlanner;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.ttsService = ttsService;
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
        ReadingSession session = sessionStore.getRequired(sessionId);
        if (handle.isCancelled() || session.isClosed()) {
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
        TtsStreamSession ttsStreamSession = null;
        AtomicBoolean sawFirstTextDelta = new AtomicBoolean();
        AtomicBoolean sawFirstAudioChunk = new AtomicBoolean();
        AtomicReference<Throwable> fatalFailure = new AtomicReference<>();

        try {
            if (handle.isCancelled() || session.isClosed()) {
                return;
            }
            if (ttsService.isConfigured()) {
                publishTiming(context, "tts.open.start", System.currentTimeMillis(), turnStartedAtNs);
                ttsStreamSession = ttsService.openSession(session.getArticle().language(), new TtsAudioListener() {
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
                        eventPublisher.publishError(context, "TTS_STREAM_FAILED", failureMessage(normalizedFailure));
                    }
                });
                handle.attachTtsStreamSession(ttsStreamSession);
                publishTiming(context, "tts.open.returned", System.currentTimeMillis(), turnStartedAtNs);
            }

            TtsStreamSession finalTtsStreamSession = ttsStreamSession;
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
                    List<TextSegment> segments = textSegmenter.append(delta);
                    if (finalTtsStreamSession != null) {
                        for (TextSegment segment : segments) {
                            throwIfFatalFailure(fatalFailure);
                            if (handle.isCancelled()) {
                                throw new CancellationException("Session closed");
                            }
                            finalTtsStreamSession.enqueue(segment);
                        }
                    }
                }

                @Override
                public void onComplete() {
                    throwIfFatalFailure(fatalFailure);
                    if (handle.isCancelled() || session.isClosed()) {
                        return;
                    }
                    publishTiming(context, "llm.stream.completed", System.currentTimeMillis(), turnStartedAtNs);
                    TextSegment remaining = textSegmenter.flushRemaining();
                    if (finalTtsStreamSession != null && remaining != null) {
                        throwIfFatalFailure(fatalFailure);
                        finalTtsStreamSession.enqueue(remaining);
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

            if (ttsStreamSession != null) {
                throwIfFatalFailure(fatalFailure);
                if (handle.isCancelled()) {
                    return;
                }
                ttsStreamSession.finish();
                throwIfFatalFailure(fatalFailure);
                if (handle.isCancelled()) {
                    return;
                }
                ttsStreamSession.awaitFinished(Duration.ofSeconds(60));
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
            eventPublisher.publishTurnDone(context, session.isAwaitingStudentAnswer());
        } catch (CancellationException exception) {
            Thread.interrupted();
            Throwable failure = fatalFailure.get();
            if (failure != null && !session.isClosed()) {
                session.markFailed();
                eventPublisher.publishError(context, "ASSISTANT_STREAM_FAILED", failureMessage(failure));
            }
        } catch (Exception exception) {
            Thread.interrupted();
            Throwable failure = fatalFailure.get();
            Throwable effectiveFailure = failure != null ? failure : normalizeFailure(exception);
            if (!session.isClosed() && (failure != null || !handle.isCancelled())) {
                session.markFailed();
                eventPublisher.publishError(context, "ASSISTANT_STREAM_FAILED", failureMessage(effectiveFailure));
            }
        } finally {
            closeQuietly(ttsStreamSession);
            activeTurnRegistry.complete(sessionId, handle);
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
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
        eventPublisher.publishTiming(context, phase, serverTimestampMs, elapsedMs);
    }
}
