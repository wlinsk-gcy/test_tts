package com.wlinsk.rd_machine.core.tts;

import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.enums.SysCode;
import com.wlinsk.rd_machine.basic.exception.BasicException;
import com.wlinsk.rd_machine.basic.logging.ReadingTtsLogHelper;
import com.wlinsk.rd_machine.basic.model.bo.TextSegment;
import com.wlinsk.rd_machine.basic.model.bo.TtsSessionRef;
import com.wlinsk.rd_machine.basic.model.dto.TtsChunkEvent;
import com.wlinsk.rd_machine.basic.model.dto.TtsSentenceStreamRequest;
import com.wlinsk.rd_machine.core.streaming.TextSegmenter;
import com.wlinsk.rd_machine.utils.snowflake.IdUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ReadingTtsStreamService {

    private static final Duration UTTERANCE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    private final ReadingTtsSessionManager sessionManager;
    private final TtsTextChunker textChunker;
    private final AiTtsProperties properties;
    private final ExecutorService executorService;

    @Autowired
    public ReadingTtsStreamService(
            ReadingTtsSessionManager sessionManager,
            AiTtsProperties properties,
            @Qualifier("ttsStreamingExecutor") ExecutorService executorService
    ) {
        this(
                sessionManager,
                new TtsTextChunker(new TextSegmenter.Settings(
                        properties.getCommit().getMinLength(),
                        properties.getCommit().getMaxLength(),
                        properties.getCommit().getMaxWaitMs(),
                        properties.getCommit().getSoftPunctuation(),
                        properties.getCommit().getHardPunctuation()
                )),
                properties,
                executorService
        );
    }

    public ReadingTtsStreamService(
            ReadingTtsSessionManager sessionManager,
            TtsTextChunker textChunker,
            AiTtsProperties properties,
            ExecutorService executorService
    ) {
        this.sessionManager = sessionManager;
        this.textChunker = textChunker;
        this.properties = properties;
        this.executorService = executorService;
    }

    public SseEmitter streamSentence(TtsSentenceStreamRequest request) {
        String requestId = IdUtils.build(null);
        long startedAtNs = System.nanoTime();
        String language = request != null ? request.language() : null;
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        AtomicReference<String> activeSessionId = new AtomicReference<>();
        AtomicReference<Future<?>> streamTaskRef = new AtomicReference<>();
        AtomicReference<String> terminalPhase = new AtomicReference<>("running");
        Future<?> streamTask = executorService.submit(
                () -> streamSentence(request, new SseTtsChunkEventSink(emitter), activeSessionId, requestId, startedAtNs, terminalPhase)
        );
        streamTaskRef.set(streamTask);

        emitter.onTimeout(() -> {
            if (markTerminalPhase(terminalPhase, "timeout")) {
                ReadingTtsLogHelper.logPhase(
                        requestId,
                        activeSessionId.get(),
                        language,
                        "stream.timeout",
                        elapsedMs(startedAtNs),
                        Map.of("timeoutMs", STREAM_TIMEOUT.toMillis())
                );
            }
            cancelTask(streamTaskRef.get());
            closeActiveSession(activeSessionId.get());
            emitter.complete();
        });
        emitter.onError(error -> {
            if (markTerminalPhase(terminalPhase, "error")) {
                ReadingTtsLogHelper.logPhase(
                        requestId,
                        activeSessionId.get(),
                        language,
                        "stream.error",
                        elapsedMs(startedAtNs),
                        Map.of("error", messageOf(error))
                );
            }
            cancelTask(streamTaskRef.get());
            closeActiveSession(activeSessionId.get());
        });
        emitter.onCompletion(() -> {
            if (markTerminalPhase(terminalPhase, "cancelled")) {
                ReadingTtsLogHelper.logPhase(
                        requestId,
                        activeSessionId.get(),
                        language,
                        "stream.cancelled",
                        elapsedMs(startedAtNs),
                        Map.of()
                );
            }
            cancelTask(streamTaskRef.get());
        });
        return emitter;
    }

    public void closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        ReadingTtsLogHelper.logPhase(null, sessionId, null, "session.close.requested", null, Map.of());
        sessionManager.close(sessionId);
    }

    public void streamSentence(
            TtsSentenceStreamRequest request,
            TtsChunkEventSink sink,
            AtomicReference<String> activeSessionId,
            String requestId,
            long startedAtNs,
            AtomicReference<String> terminalPhase
    ) {
        AtomicBoolean streamCompleted = new AtomicBoolean();
        AtomicBoolean sawFirstAudio = new AtomicBoolean();
        String requestedSessionId = request != null ? normalizeSessionId(request.sessionId()) : null;
        ReadingTtsLogHelper.logPhase(
                requestId,
                requestedSessionId,
                request != null ? request.language() : null,
                "stream.start",
                0L,
                Map.of(
                        "sentenceLength", sentenceLength(request),
                        "hasExistingSession", requestedSessionId != null
                )
        );
        if (request == null || request.sentence() == null || request.sentence().isBlank()) {
            markTerminalPhase(terminalPhase, "error");
            ReadingTtsLogHelper.logPhase(
                    requestId,
                    null,
                    request != null ? request.language() : null,
                    "stream.error",
                    elapsedMs(startedAtNs),
                    Map.of("error", "sentence must not be blank", "code", SysCode.PARAMETER_ERROR.getCode())
            );
            emitAndComplete(sink, streamCompleted, TtsChunkEvent.audioError(null, SysCode.PARAMETER_ERROR.getCode(), "sentence must not be blank"));
            return;
        }
        TtsSessionRef sessionRef;
        try {
            sessionRef = request.sessionId() == null || request.sessionId().isBlank()
                    ? sessionManager.getOrCreate(null, request.language())
                    : sessionManager.getRequired(request.sessionId(), request.language());
        } catch (BasicException exception) {
            markTerminalPhase(terminalPhase, "error");
            ReadingTtsLogHelper.logPhase(
                    requestId,
                    request.sessionId(),
                    request.language(),
                    "stream.error",
                    elapsedMs(startedAtNs),
                    Map.of("error", exception.getMessage(), "code", exception.getStatus())
            );
            emitAndComplete(sink, streamCompleted, TtsChunkEvent.audioError(request.sessionId(), exception.getStatus(), exception.getMessage()));
            return;
        }
        activeSessionId.set(sessionRef.sessionId());

        try {
            List<TextSegment> segments = TtsTextNormalizer.normalize(textChunker.chunk(request.sentence()), request.language());
            ReadingTtsLogHelper.logPhase(
                    requestId,
                    sessionRef.sessionId(),
                    request.language(),
                    "text.chunked",
                    elapsedMs(startedAtNs),
                    Map.of(
                            "segmentCount", segments.size(),
                            "normalizedLength", totalSegmentLength(segments)
                    )
            );
            TtsUtterance utterance = sessionRef.session().openUtterance(new TtsAudioListener() {
                @Override
                public void onSessionReady() {
                    ReadingTtsLogHelper.logPhase(
                            requestId,
                            sessionRef.sessionId(),
                            request.language(),
                            "tts.session.ready",
                            elapsedMs(startedAtNs),
                            Map.of()
                    );
                }

                @Override
                public void onAudioChunk(int segmentSeq, byte[] audioBytes) {
                    if (sawFirstAudio.compareAndSet(false, true)) {
                        ReadingTtsLogHelper.logPhase(
                                requestId,
                                sessionRef.sessionId(),
                                request.language(),
                                "tts.first.audio",
                                elapsedMs(startedAtNs),
                                Map.of(
                                        "segmentSeq", segmentSeq,
                                        "audioBytes", audioBytes != null ? audioBytes.length : 0
                                )
                        );
                    }
                    sink.emit(TtsChunkEvent.audioChunk(
                            sessionRef.sessionId(),
                            segmentSeq,
                            properties.getResponseFormat(),
                            properties.getSampleRate(),
                            audioBytes
                    ));
                }

                @Override
                public void onCompleted() {
                    ReadingTtsLogHelper.logPhase(
                            requestId,
                            sessionRef.sessionId(),
                            request.language(),
                            "tts.audio.done",
                            elapsedMs(startedAtNs),
                            Map.of()
                    );
                    if (markTerminalPhase(terminalPhase, "completed")) {
                        ReadingTtsLogHelper.logPhase(
                                requestId,
                                sessionRef.sessionId(),
                                request.language(),
                                "stream.completed",
                                elapsedMs(startedAtNs),
                                Map.of()
                        );
                    }
                    emitAndComplete(sink, streamCompleted, TtsChunkEvent.audioDone(sessionRef.sessionId()));
                }

                @Override
                public void onError(Throwable throwable) {
                    if (markTerminalPhase(terminalPhase, "error")) {
                        ReadingTtsLogHelper.logPhase(
                                requestId,
                                sessionRef.sessionId(),
                                request.language(),
                                "stream.error",
                                elapsedMs(startedAtNs),
                                Map.of(
                                        "error", messageOf(throwable),
                                        "code", SysCode.TTS_STREAM_FAILED.getCode()
                                )
                        );
                    }
                    emitAndComplete(
                            sink,
                            streamCompleted,
                            TtsChunkEvent.audioError(
                                    sessionRef.sessionId(),
                                    SysCode.TTS_STREAM_FAILED.getCode(),
                                    messageOf(throwable)
                            )
                    );
                    sessionManager.close(sessionRef.sessionId());
                }
            });
            ReadingTtsLogHelper.logPhase(
                    requestId,
                    sessionRef.sessionId(),
                    request.language(),
                    "utterance.opened",
                    elapsedMs(startedAtNs),
                    Map.of()
            );
            for (TextSegment segment : segments) {
                utterance.enqueue(segment);
            }
            utterance.finish();
            utterance.awaitFinished(UTTERANCE_TIMEOUT);
        } catch (BasicException exception) {
            if (markTerminalPhase(terminalPhase, "error")) {
                ReadingTtsLogHelper.logPhase(
                        requestId,
                        sessionRef.sessionId(),
                        request.language(),
                        "stream.error",
                        elapsedMs(startedAtNs),
                        Map.of("error", exception.getMessage(), "code", exception.getStatus())
                );
            }
            emitAndComplete(sink, streamCompleted, TtsChunkEvent.audioError(sessionRef.sessionId(), exception.getStatus(), exception.getMessage()));
        } catch (Exception exception) {
            if (markTerminalPhase(terminalPhase, "error")) {
                ReadingTtsLogHelper.logPhase(
                        requestId,
                        sessionRef.sessionId(),
                        request.language(),
                        "stream.error",
                        elapsedMs(startedAtNs),
                        Map.of("error", messageOf(exception), "code", SysCode.TTS_STREAM_FAILED.getCode())
                );
            }
            emitAndComplete(
                    sink,
                    streamCompleted,
                    TtsChunkEvent.audioError(sessionRef.sessionId(), SysCode.TTS_STREAM_FAILED.getCode(), messageOf(exception))
            );
            sessionManager.close(sessionRef.sessionId());
        }
    }

    private void emitAndComplete(TtsChunkEventSink sink, AtomicBoolean streamCompleted, TtsChunkEvent event) {
        if (!streamCompleted.compareAndSet(false, true)) {
            return;
        }
        sink.emit(event);
        sink.complete();
    }

    private String messageOf(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return SysCode.TTS_STREAM_FAILED.getMessage();
        }
        return throwable.getMessage();
    }

    private long elapsedMs(long startedAtNs) {
        return Math.max(0L, (System.nanoTime() - startedAtNs) / 1_000_000L);
    }

    private boolean markTerminalPhase(AtomicReference<String> terminalPhase, String nextPhase) {
        return terminalPhase.compareAndSet("running", nextPhase);
    }

    private int sentenceLength(TtsSentenceStreamRequest request) {
        if (request == null || request.sentence() == null) {
            return 0;
        }
        return request.sentence().length();
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? null : sessionId;
    }

    private int totalSegmentLength(List<TextSegment> segments) {
        int total = 0;
        for (TextSegment segment : segments) {
            if (segment != null && segment.text() != null) {
                total += segment.text().length();
            }
        }
        return total;
    }

    private void cancelTask(Future<?> task) {
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }

    private void closeActiveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionManager.close(sessionId);
    }

    private static final class SseTtsChunkEventSink implements TtsChunkEventSink {

        private final SseEmitter emitter;

        private SseTtsChunkEventSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void emit(TtsChunkEvent event) {
            try {
                emitter.send(
                        SseEmitter.event()
                                .name(event.type())
                                .data(event, MediaType.APPLICATION_JSON)
                );
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write SSE event", exception);
            }
        }

        @Override
        public void complete() {
            emitter.complete();
        }
    }
}
