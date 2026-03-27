package com.wlinsk.rd_machine.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlinsk.rd_machine.config.AiTtsProperties;
import com.wlinsk.rd_machine.streaming.TextSegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class AliyunRealtimeTtsClient {

    private static final TextSegment FINISH_SENTINEL = new TextSegment(-1, "");

    private final AiTtsProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public AliyunRealtimeTtsClient(AiTtsProperties properties, ObjectMapper objectMapper, ExecutorService executorService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executorService = executorService;
    }

    public TtsStreamSession openSession(TtsSynthesisRequest request, TtsAudioListener audioListener) {
        return new RealtimeTtsStreamSession(request, audioListener);
    }

    private final class RealtimeTtsStreamSession implements TtsStreamSession, WebSocket.Listener {

        private final TtsSynthesisRequest request;
        private final TtsAudioListener audioListener;
        private final LinkedBlockingQueue<TextSegment> queue = new LinkedBlockingQueue<>();
        private final ConcurrentLinkedQueue<Integer> pendingServerCommitSegmentSeqs = new ConcurrentLinkedQueue<>();
        private final AtomicInteger currentSegmentSeq = new AtomicInteger();
        private final AtomicReference<CompletableFuture<Void>> currentResponseDone = new AtomicReference<>(new CompletableFuture<>());
        private final CompletableFuture<Void> sessionReady = new CompletableFuture<>();
        private final CompletableFuture<Void> sessionFinished = new CompletableFuture<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private volatile WebSocket webSocket;
        private volatile boolean finished;

        private RealtimeTtsStreamSession(TtsSynthesisRequest request, TtsAudioListener audioListener) {
            this.request = request;
            this.audioListener = audioListener;
            this.webSocket = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .buildAsync(resolveUri(), this)
                    .join();
            sendSessionUpdate();
            executorService.submit(this::drainSegments);
        }

        @Override
        public void enqueue(TextSegment textSegment) {
            if (finished || textSegment == null || textSegment.text().isBlank()) {
                return;
            }
            queue.offer(textSegment);
        }

        @Override
        public void finish() {
            finished = true;
            queue.offer(FINISH_SENTINEL);
        }

        @Override
        public void awaitFinished(Duration timeout) {
            sessionFinished.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
        }

        @Override
        public void close() {
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
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
            fail(error);
        }

        private void drainSegments() {
            try {
                sessionReady.join();
                while (true) {
                    TextSegment segment = queue.take();
                    if (segment.segmentSeq() < 0) {
                        break;
                    }
                    if (request.usesClientCommit()) {
                        currentSegmentSeq.set(segment.segmentSeq());
                        CompletableFuture<Void> responseDone = new CompletableFuture<>();
                        currentResponseDone.set(responseDone);
                        sendAppend(segment.text());
                        sendCommit();
                        responseDone.join();
                    } else {
                        pendingServerCommitSegmentSeqs.offer(segment.segmentSeq());
                        sendAppend(segment.text());
                    }
                }
                sendSessionFinish();
            } catch (Exception exception) {
                fail(exception);
            }
        }

        private void handleServerEvent(String payload) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                String type = root.path("type").asText();
                switch (type) {
                    case "session.updated" -> {
                        audioListener.onSessionReady();
                        sessionReady.complete(null);
                    }
                    case "response.created" -> assignServerCommitSegmentSeq();
                    case "response.audio.delta" -> {
                        String delta = root.path("delta").asText();
                        if (!delta.isBlank()) {
                            audioListener.onAudioChunk(currentSegmentSeq.get(), Base64.getDecoder().decode(delta));
                        }
                    }
                    case "response.done" -> currentResponseDone.get().complete(null);
                    case "session.finished" -> {
                        audioListener.onCompleted();
                        sessionFinished.complete(null);
                    }
                    case "error" -> fail(new IllegalStateException(payload));
                    default -> {
                    }
                }
            } catch (Exception exception) {
                fail(exception);
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
                    "event_id", UUID.randomUUID().toString(),
                    "type", "session.update",
                    "session", session
            ));
        }

        private void sendAppend(String text) {
            sendJson(Map.of(
                    "event_id", UUID.randomUUID().toString(),
                    "type", "input_text_buffer.append",
                    "text", text
            ));
        }

        private void sendCommit() {
            sendJson(Map.of(
                    "event_id", UUID.randomUUID().toString(),
                    "type", "input_text_buffer.commit"
            ));
        }

        private void sendSessionFinish() {
            sendJson(Map.of(
                    "event_id", UUID.randomUUID().toString(),
                    "type", "session.finish"
            ));
        }

        private void assignServerCommitSegmentSeq() {
            if (request.usesClientCommit()) {
                return;
            }
            Integer segmentSeq = pendingServerCommitSegmentSeqs.poll();
            if (segmentSeq != null) {
                currentSegmentSeq.set(segmentSeq);
            }
            pendingServerCommitSegmentSeqs.clear();
        }

        private void sendJson(Map<String, Object> payload) {
            try {
                webSocket.sendText(objectMapper.writeValueAsString(payload), true).join();
            } catch (Exception exception) {
                fail(exception);
            }
        }

        private URI resolveUri() {
            String base = properties.getWsUrl();
            if (base.contains("model=")) {
                return URI.create(base);
            }
            String delimiter = base.contains("?") ? "&" : "?";
            return URI.create(base + delimiter + "model=" + properties.getModel());
        }

        private void fail(Throwable throwable) {
            audioListener.onError(throwable);
            sessionReady.completeExceptionally(throwable);
            currentResponseDone.get().completeExceptionally(throwable);
            sessionFinished.completeExceptionally(throwable);
        }
    }
}
