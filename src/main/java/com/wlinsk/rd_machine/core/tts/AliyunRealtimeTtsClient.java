package com.wlinsk.rd_machine.core.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.enums.SysCode;
import com.wlinsk.rd_machine.basic.exception.BasicException;
import com.wlinsk.rd_machine.basic.logging.ReadingTtsLogHelper;
import com.wlinsk.rd_machine.basic.model.bo.TextSegment;
import com.wlinsk.rd_machine.basic.model.bo.TtsSynthesisRequest;
import com.wlinsk.rd_machine.basic.model.bo.TtsUsage;
import com.wlinsk.rd_machine.utils.snowflake.IdUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class AliyunRealtimeTtsClient {

    private final AiTtsProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final Set<String> UPSTREAM_DEBUG_EVENTS = Set.of(
            "session.updated",
            "response.created",
            "response.audio.done",
            "response.done",
            "error"
    );

    public AliyunRealtimeTtsClient(
            AiTtsProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("ttsStreamingExecutor") ExecutorService executorService
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executorService = executorService;
    }

    public TtsRealtimeSession openSession(TtsSynthesisRequest request) {
        return new RealtimeTtsSessionConnection(request);
    }

    public static BasicException segmentQueueFullException() {
        return new BasicException(SysCode.TTS_SEGMENT_QUEUE_FULL);
    }

    public BlockingQueue<TextSegment> newSegmentQueue() {
        return new ArrayBlockingQueue<>(properties.getSegmentQueueCapacity());
    }

    private final class RealtimeTtsSessionConnection implements TtsRealtimeSession, WebSocket.Listener {

        private final TtsSynthesisRequest request;
        private final CompletableFuture<Void> sessionReady = new CompletableFuture<>();
        private final CompletableFuture<WebSocket> webSocketFuture;
        private final AtomicReference<QueuedTtsUtterance> currentUtterance = new AtomicReference<>();
        private final AtomicInteger currentSegmentSeq = new AtomicInteger();
        private final StringBuilder textBuffer = new StringBuilder();
        private final AtomicBoolean utteranceActive = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final String upstreamSessionId = IdUtils.build(null);
        private volatile WebSocket webSocket;

        private RealtimeTtsSessionConnection(TtsSynthesisRequest request) {
            this.request = request;
            this.webSocketFuture = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .buildAsync(resolveUri(), this);
            this.webSocketFuture.whenComplete((openedWebSocket, throwable) -> {
                if (throwable != null) {
                    failSession(throwable);
                    return;
                }
                this.webSocket = openedWebSocket;
                sendSessionUpdate();
            });
        }

        @Override
        public TtsUtterance openUtterance(TtsAudioListener audioListener) {
            if (closed.get()) {
                throw new BasicException(SysCode.TTS_STREAM_FAILED);
            }
            if (!utteranceActive.compareAndSet(false, true)) {
                throw new BasicException(SysCode.TTS_SESSION_BUSY);
            }
            QueuedTtsUtterance utterance = new QueuedTtsUtterance(
                    audioListener,
                    newSegmentQueue(),
                    closed,
                    queuedUtterance -> executorService.submit(() -> drainUtterance(queuedUtterance))
            );
            currentUtterance.set(utterance);
            return utterance;
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            closeSession(new CancellationException("TTS session closed"));
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                handleServerEvent(textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failSession(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeSession(new CancellationException("TTS session closed by upstream"));
            return CompletableFuture.completedFuture(null);
        }

        private void handleServerEvent(String payload) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                String type = root.path("type").asText();
                logUpstreamEvent(type, root);
                switch (type) {
                    case "session.updated" -> sessionReady.complete(null);
                    case "response.created" -> markResponseCreated();
                    case "response.audio.delta" -> forwardAudioChunk(root.path("delta").asText());
                    case "response.audio.done" -> markAudioDone();
                    case "response.done" -> markResponseDone(root);
                    case "error" -> failSession(new IllegalStateException(payload));
                    default -> {
                    }
                }
            } catch (Exception exception) {
                failSession(exception);
            }
        }

        private void logUpstreamEvent(String type, JsonNode payload) {
            if (!properties.isDebugLogUpstreamEvents() || !UPSTREAM_DEBUG_EVENTS.contains(type)) {
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            if ("error".equals(type)) {
                data.put("payload", payload != null ? payload.toString() : null);
            }
            ReadingTtsLogHelper.logUpstreamEvent(
                    upstreamSessionId,
                    request.voice(),
                    request.languageType(),
                    type,
                    data
            );
        }

        private void forwardAudioChunk(String deltaBase64) {
            if (deltaBase64 == null || deltaBase64.isBlank()) {
                return;
            }
            QueuedTtsUtterance utterance = currentUtterance.get();
            if (utterance == null) {
                return;
            }
            utterance.audioListener().onAudioChunk(
                    currentSegmentSeq.get(),
                    Base64.getDecoder().decode(deltaBase64)
            );
        }

        private void markResponseCreated() {
            QueuedTtsUtterance utterance = currentUtterance.get();
            if (utterance != null) {
                currentSegmentSeq.set(utterance.markResponseCreated());
            }
        }

        private void markResponseDone(JsonNode root) {
            QueuedTtsUtterance utterance = currentUtterance.get();
            if (utterance == null) {
                return;
            }
            JsonNode charactersNode = root.path("response").path("usage").path("characters");
            long characters;
            if (charactersNode.isMissingNode() || charactersNode.isNull() || !charactersNode.isNumber()) {
                log.warn("TTS response.done missing usage.characters; defaulting to 0. upstreamSessionId={}, voice={}",
                        upstreamSessionId, request.voice());
                characters = 0L;
            } else {
                characters = charactersNode.asLong();
            }
            utterance.audioListener().onUsage(new TtsUsage(characters));
            utterance.markResponseDone();
        }

        private void markAudioDone() {
            QueuedTtsUtterance utterance = currentUtterance.get();
            if (utterance != null) {
                utterance.markAudioDone();
            }
        }

        private void sendSessionUpdate() {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("voice", request.voice());
            session.put("mode", request.mode());
            session.put("response_format", request.responseFormat());
            session.put("sample_rate", request.sampleRate());
            session.put("language_type", request.languageType());
            sendJson(Map.of(
                    "event_id", IdUtils.build(null),
                    "type", "session.update",
                    "session", session
            ));
        }

        private void sendAppend(String text) {
            sendJson(Map.of(
                    "event_id", IdUtils.build(null),
                    "type", "input_text_buffer.append",
                    "text", text
            ));
        }

        private void sendCommit() {
            sendJson(Map.of(
                    "event_id", IdUtils.build(null),
                    "type", "input_text_buffer.commit"
            ));
        }

        private void sendSessionFinish() {
            sendJson(Map.of(
                    "event_id", IdUtils.build(null),
                    "type", "session.finish"
            ));
        }

        private void sendJson(Map<String, Object> payload) {
            try {
                logOutboundEvent(payload);
                webSocket.sendText(objectMapper.writeValueAsString(payload), true).join();
            } catch (Exception exception) {
                failSession(exception);
            }
        }

        private void drainUtterance(QueuedTtsUtterance utterance) {
            try {
                sessionReady.orTimeout(10, TimeUnit.SECONDS).join();
                utterance.audioListener().onSessionReady();
                while (true) {
                    TextSegment segment = utterance.queue().take();
                    if (segment.segmentSeq() < 0) {
                        break;
                    }
                    utterance.markSegmentAppended(segment.segmentSeq());
                    sendAppend(segment.text());
                }
                if (closed.get() || utterance.isTerminal()) {
                    return;
                }
                if (utterance.hasAnyInputAppended()) {
                    utterance.markInputCommitted();
                    sendCommit();
                    utterance.awaitResponseCompletion(Duration.ofSeconds(30));
                }
                utterance.completeSuccessfully();
            } catch (Exception exception) {
                failSession(exception);
            } finally {
                currentUtterance.compareAndSet(utterance, null);
                utteranceActive.set(false);
            }
        }

        private void closeSession(Throwable throwable) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            sessionReady.completeExceptionally(throwable);
            QueuedTtsUtterance utterance = currentUtterance.getAndSet(null);
            if (utterance != null) {
                utterance.fail(throwable);
            }
            utteranceActive.set(false);
            webSocketFuture.thenAccept(socket -> {
                try {
                    if (this.webSocket != null) {
                        sendSessionFinish();
                    }
                } catch (Exception ignored) {
                }
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }).exceptionally(ignored -> null);
        }

        private void failSession(Throwable throwable) {
            closeSession(throwable);
        }

        private void logOutboundEvent(Map<String, Object> payload) {
            if (!properties.isDebugLogUpstreamEvents() || payload == null) {
                return;
            }
            String type = payload.get("type") instanceof String text ? text : null;
            if (type == null) {
                return;
            }
            Map<String, Object> data = switch (type) {
                case "input_text_buffer.append" -> Map.of(
                        "textLength",
                        payload.get("text") instanceof String text ? text.length() : 0
                );
                default -> Map.of();
            };
            ReadingTtsLogHelper.logUpstreamOutboundEvent(
                    upstreamSessionId,
                    request.voice(),
                    request.languageType(),
                    type,
                    data
            );
        }

        private URI resolveUri() {
            String base = properties.getWsUrl();
            if (base.contains("model=")) {
                return URI.create(base);
            }
            String delimiter = base.contains("?") ? "&" : "?";
            return URI.create(base + delimiter + "model=" + properties.getModel());
        }
    }
}
