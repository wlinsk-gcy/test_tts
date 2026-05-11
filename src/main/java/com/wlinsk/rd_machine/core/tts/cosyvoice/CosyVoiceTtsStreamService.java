package com.wlinsk.rd_machine.core.tts.cosyvoice;

import com.wlinsk.rd_machine.basic.config.CosyVoiceTtsProperties;
import com.wlinsk.rd_machine.basic.enums.SysCode;
import com.wlinsk.rd_machine.basic.exception.BasicException;
import com.wlinsk.rd_machine.basic.model.dto.CosyVoiceTtsStreamRequest;
import com.wlinsk.rd_machine.basic.model.dto.TtsChunkEvent;
import com.wlinsk.rd_machine.core.tts.TtsChunkEventSink;
import com.wlinsk.rd_machine.utils.snowflake.IdUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class CosyVoiceTtsStreamService {

    private final CosyVoiceTtsProperties properties;
    private final CosyVoiceConnectionPool connectionPool;
    private final Executor ttsStreamingExecutor;


    public SseEmitter stream(CosyVoiceTtsStreamRequest request) {
        long startedAtNs = System.nanoTime();
        String sessionId = resolveSessionId(request);
        SseEmitter emitter = new SseEmitter(properties.getStreamTimeoutMs());
        AtomicReference<CosyVoiceWebSocketClient> activeConnection = new AtomicReference<>();
        AtomicReference<String> terminalPhase = new AtomicReference<>("running");

        ttsStreamingExecutor.execute(() -> stream(
                request,
                new SseTtsChunkEventSink(emitter),
                activeConnection,
                sessionId,
                startedAtNs,
                terminalPhase
        ));

        emitter.onTimeout(() -> {
            if (markTerminalPhase(terminalPhase, "timeout")) {
                log.info(
                        "cosyvoice.stream.cancelled sessionId={} eventType=timeout elapsedMs={}",
                        sessionId,
                        elapsedMs(startedAtNs)
                );
                discardActiveConnection(activeConnection, "sse timeout");
            }
            emitter.complete();
        });
        emitter.onError(error -> {
            if (markTerminalPhase(terminalPhase, "error")) {
                log.error(
                        "cosyvoice.stream.error sessionId={} eventType=emitter-error elapsedMs={}",
                        sessionId,
                        elapsedMs(startedAtNs),
                        error
                );
                discardActiveConnection(activeConnection, "sse error");
            }
        });
        emitter.onCompletion(() -> {
            if (markTerminalPhase(terminalPhase, "cancelled")) {
                log.info(
                        "cosyvoice.stream.cancelled sessionId={} eventType=completion elapsedMs={}",
                        sessionId,
                        elapsedMs(startedAtNs)
                );
                discardActiveConnection(activeConnection, "sse completion");
            }
        });
        return emitter;
    }

    public void stream(
            CosyVoiceTtsStreamRequest request,
            TtsChunkEventSink sink,
            AtomicReference<CosyVoiceWebSocketClient> activeConnection,
            String requestId,
            long startedAtNs,
            AtomicReference<String> terminalPhase
    ) {
        String sessionId = resolveSessionId(request, requestId);
//        String taskId = IdUtils.build(null);
        String taskId = UUID.randomUUID().toString();
        String language = request != null ? request.language() : null;
        boolean ssml = request != null && Boolean.TRUE.equals(request.ssml());
        String voice = properties.resolveVoice(language);
        CosyVoiceConnectionKey connectionKey = CosyVoiceConnectionKey.from(language, ssml);
        log.info(
                "cosyvoice.stream.start sessionId={} taskId={} eventType=stream-start language={} voice={} ssml={} connectionKey={} textLength={} model={} sampleRate={} format={} elapsedMs=0",
                sessionId,
                taskId,
                language,
                voice,
                ssml,
                connectionKey,
                sentenceLength(request),
                properties.getModel(),
                properties.getSampleRate(),
                properties.getFormat()
        );

        if (request == null || request.sentence() == null || request.sentence().isBlank()) {
            markTerminalPhase(terminalPhase, "error");
            sink.emit(TtsChunkEvent.cosyVoiceAudioError(
                    sessionId,
                    taskId,
                    SysCode.PARAMETER_ERROR.getCode(),
                    "sentence must not be blank"
            ));
            sink.complete();
            return;
        }

        try {
            CosyVoiceWebSocketClient connection = connectionPool.acquire(connectionKey);
            activeConnection.set(connection);
            if (!isRunning(terminalPhase)) {
                log.info(
                        "cosyvoice.stream.cancelled sessionId={} taskId={} eventType=cancelled-before-task terminalPhase={} elapsedMs={}",
                        sessionId,
                        taskId,
                        terminalPhase.get(),
                        elapsedMs(startedAtNs)
                );
                releaseActiveConnection(activeConnection);
                return;
            }
            CosyVoiceTaskRequest taskRequest = new CosyVoiceTaskRequest(
                    sessionId,
                    taskId,
                    language,
                    voice,
                    request.sentence(),
                    ssml,
                    startedAtNs
            );
            CompletableFuture<Void> taskFuture = connection.synthesize(taskRequest, new CosyVoiceTaskListener() {
                @Override
                public void onUpstreamEvent(CosyVoiceUpstreamEvent event) {
                    // Upstream events are still logged in CosyVoiceWebSocketClient; the public SSE
                    // contract intentionally matches TtsSessionController.
                }

                @Override
                public void onAudioChunk(int chunkSeq, byte[] audioBytes) {
                    sink.emit(TtsChunkEvent.cosyVoiceAudioChunk(
                            sessionId,
                            taskId,
                            chunkSeq,
                            properties.getFormat(),
                            properties.getSampleRate(),
                            audioBytes
                    ));
                }

                @Override
                public void onCompleted() {
                    if (!markTerminalPhase(terminalPhase, "completed")) {
                        return;
                    }
                    boolean completedCleanly = false;
                    try {
                        sink.emit(TtsChunkEvent.cosyVoiceAudioDone(sessionId, taskId));
                        sink.complete();
                        completedCleanly = true;
                        log.info(
                                "cosyvoice.stream.completed sessionId={} taskId={} eventType=stream-completed elapsedMs={}",
                                sessionId,
                                taskId,
                                elapsedMs(startedAtNs)
                        );
                    } finally {
                        if (completedCleanly) {
                            releaseActiveConnection(activeConnection);
                        } else {
                            discardActiveConnection(activeConnection, "stream completion failed");
                        }
                    }
                }

                @Override
                public void onFailed(Throwable throwable) {
                    failStream(
                            sink,
                            activeConnection,
                            sessionId,
                            taskId,
                            terminalPhase,
                            startedAtNs,
                            throwable
                    );
                }
            });
            taskFuture.join();
        } catch (BasicException exception) {
            failStream(
                    sink,
                    activeConnection,
                    sessionId,
                    taskId,
                    terminalPhase,
                    startedAtNs,
                    exception
            );
        } catch (CompletionException exception) {
            failStream(
                    sink,
                    activeConnection,
                    sessionId,
                    taskId,
                    terminalPhase,
                    startedAtNs,
                    exception.getCause() != null ? exception.getCause() : exception
            );
        } catch (Exception exception) {
            failStream(
                    sink,
                    activeConnection,
                    sessionId,
                    taskId,
                    terminalPhase,
                    startedAtNs,
                    exception
            );
        }
    }

    private void failStream(
            TtsChunkEventSink sink,
            AtomicReference<CosyVoiceWebSocketClient> activeConnection,
            String sessionId,
            String taskId,
            AtomicReference<String> terminalPhase,
            long startedAtNs,
            Throwable throwable
    ) {
        if (!markTerminalPhase(terminalPhase, "error")) {
            return;
        }
        String code = throwable instanceof BasicException basicException
                ? basicException.getStatus()
                : SysCode.TTS_STREAM_FAILED.getCode();
        String message = throwable instanceof BasicException basicException
                ? basicException.getMessage()
                : messageOf(throwable);
        log.error(
                "cosyvoice.stream.error sessionId={} taskId={} eventType=stream-error code={} elapsedMs={}",
                sessionId,
                taskId,
                code,
                elapsedMs(startedAtNs),
                throwable
        );
        try {
            sink.emit(TtsChunkEvent.cosyVoiceAudioError(sessionId, taskId, code, message));
            sink.complete();
        } finally {
            discardActiveConnection(activeConnection, "stream failed");
        }
    }

    private String resolveSessionId(CosyVoiceTtsStreamRequest request) {
        return resolveSessionId(request, null);
    }

    private String resolveSessionId(CosyVoiceTtsStreamRequest request, String fallbackSessionId) {
        if (request != null && request.sessionId() != null && !request.sessionId().isBlank()) {
            return request.sessionId();
        }
        if (fallbackSessionId != null && !fallbackSessionId.isBlank()) {
            return fallbackSessionId;
        }
        return IdUtils.build(null);
    }

    private int sentenceLength(CosyVoiceTtsStreamRequest request) {
        return request != null && request.sentence() != null ? request.sentence().length() : 0;
    }

    private boolean markTerminalPhase(AtomicReference<String> terminalPhase, String nextPhase) {
        return terminalPhase.compareAndSet("running", nextPhase);
    }

    private boolean isRunning(AtomicReference<String> terminalPhase) {
        return "running".equals(terminalPhase.get());
    }

    private void releaseActiveConnection(AtomicReference<CosyVoiceWebSocketClient> activeConnection) {
        connectionPool.releaseReusable(activeConnection.getAndSet(null));
    }

    private void discardActiveConnection(AtomicReference<CosyVoiceWebSocketClient> activeConnection, String reason) {
        connectionPool.discard(activeConnection.getAndSet(null), reason);
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
                throw new IllegalStateException("Failed to write CosyVoice SSE event", exception);
            }
        }

        @Override
        public void complete() {
            emitter.complete();
        }
    }
}
