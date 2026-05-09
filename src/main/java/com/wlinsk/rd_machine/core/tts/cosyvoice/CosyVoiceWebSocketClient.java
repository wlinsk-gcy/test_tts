package com.wlinsk.rd_machine.core.tts.cosyvoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlinsk.rd_machine.basic.config.CosyVoiceTtsProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class CosyVoiceWebSocketClient implements WebSocket.Listener {

    public interface Connector {

        CompletableFuture<WebSocket> connect(URI uri, String apiKey, WebSocket.Listener listener);
    }

    public static final class JdkConnector implements Connector {

        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final Duration connectTimeout;

        public JdkConnector(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        @Override
        public CompletableFuture<WebSocket> connect(URI uri, String apiKey, WebSocket.Listener listener) {
            return httpClient.newWebSocketBuilder()
                    .connectTimeout(connectTimeout)
                    .header("Authorization", "bearer " + apiKey)
                    .buildAsync(uri, listener);
        }
    }

    private final String connectionId;
    private final CosyVoiceTtsProperties properties;
    private final ObjectMapper objectMapper;
    private final Connector connector;
    private final StringBuilder textBuffer = new StringBuilder();
    private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
    private final Object taskLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile WebSocket webSocket;
    private volatile ActiveTask activeTask;
    private volatile long lastUsedAtNs = System.nanoTime();

    public CosyVoiceWebSocketClient(
            String connectionId,
            CosyVoiceTtsProperties properties,
            ObjectMapper objectMapper,
            Connector connector
    ) {
        this.connectionId = connectionId;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.connector = connector;
    }

    public CompletableFuture<Void> connect() {
        long startedAtNs = System.nanoTime();
        log.info("cosyvoice.connection.open.start connectionId={} elapsedMs=0", connectionId);
        return connector.connect(URI.create(properties.getWsUrl()), properties.getApiKey(), this)
                .thenAccept(openedWebSocket -> {
                    this.webSocket = openedWebSocket;
                    this.lastUsedAtNs = System.nanoTime();
                    log.info(
                            "cosyvoice.connection.opened connectionId={} connectMs={}",
                            connectionId,
                            elapsedMs(startedAtNs)
                    );
                });
    }

    public CompletableFuture<Void> synthesize(CosyVoiceTaskRequest request, CosyVoiceTaskListener listener) {
        if (!isOpen()) {
            return failedFuture(new IllegalStateException("CosyVoice WebSocket is not open"));
        }
        if (!busy.compareAndSet(false, true)) {
            return failedFuture(new IllegalStateException("CosyVoice WebSocket is busy"));
        }

        ActiveTask task = new ActiveTask(request, listener);
        synchronized (taskLock) {
            activeTask = task;
        }
        CompletableFuture.delayedExecutor(properties.getTaskTimeoutMs(), TimeUnit.MILLISECONDS)
                .execute(() -> failTask(task, new TimeoutException("CosyVoice task timed out"), true));

        try {
            sendJson("run-task", runTaskPayload(request));
        } catch (Exception exception) {
            failTask(task, exception, true);
        }
        return task.completion;
    }

    public String connectionId() {
        return connectionId;
    }

    public boolean isOpen() {
        return !closed.get()
                && webSocket != null
                && !webSocket.isInputClosed()
                && !webSocket.isOutputClosed();
    }

    public long lastUsedAtNs() {
        return lastUsedAtNs;
    }

    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("cosyvoice.connection.closed connectionId={} reason={}", connectionId, reason);
        failTask(new IllegalStateException("CosyVoice WebSocket closed locally: " + reason), false);
        WebSocket socket = webSocket;
        if (socket != null && !socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, reason);
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
            String payload = textBuffer.toString();
            textBuffer.setLength(0);
            handleText(payload);
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] bytes = copyBytes(data);
        binaryBuffer.writeBytes(bytes);
        if (last) {
            byte[] audioBytes = binaryBuffer.toByteArray();
            binaryBuffer.reset();
            handleAudio(audioBytes);
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        failTask(error, true);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed.set(true);
        failTask(new IllegalStateException("CosyVoice WebSocket closed: " + statusCode + " " + reason), false);
        return CompletableFuture.completedFuture(null);
    }

    private void handleText(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode header = root.path("header");
            String eventType = header.path("event").asText(null);
            String taskId = header.path("task_id").asText(currentTaskId());
            String requestId = header.path("request_id").asText(null);
            Object usage = root.path("payload").has("usage")
                    ? objectMapper.convertValue(root.path("payload").path("usage"), Object.class)
                    : null;
            Integer usageCharacters = root.path("payload").path("usage").has("characters")
                    ? root.path("payload").path("usage").path("characters").asInt()
                    : null;
            String payloadOutput = payloadOutput(root);
            CosyVoiceUpstreamEvent event = new CosyVoiceUpstreamEvent(
                    taskId,
                    eventType,
                    requestId,
                    usage,
                    objectMapper.convertValue(root, Object.class)
            );
            ActiveTask task = currentTaskMatching(taskId);
            if (task != null) {
                task.listener.onUpstreamEvent(event);
            }
            log.info(
                    "cosyvoice.inbound.event sessionId={} taskId={} connectionId={} eventType={} requestId={} usageCharacters={} payloadOutput={} elapsedMs={}",
                    task != null ? task.request.sessionId() : null,
                    taskId,
                    connectionId,
                    eventType,
                    requestId,
                    usageCharacters,
                    payloadOutput,
                    task != null ? elapsedMs(task.request.startedAtNs()) : null
            );
            if ("task-started".equals(eventType)) {
                if (task != null) {
                    sendJson("continue-task", continueTaskPayload(task.request));
                    sendJson("finish-task", finishTaskPayload(task.request));
                }
            } else if ("task-finished".equals(eventType)) {
                completeTask(task);
            } else if ("task-failed".equals(eventType)) {
                failTask(task, new IllegalStateException(errorMessage(root)), true);
            }
        } catch (Exception exception) {
            failTask(exception, true);
        }
    }

    private void handleAudio(byte[] audioBytes) {
        ActiveTask task = activeTask;
        if (task == null || audioBytes.length == 0) {
            return;
        }
        int chunkSeq = task.chunkSeq.incrementAndGet();
        task.audioBytes += audioBytes.length;
        log.info(
                "cosyvoice.inbound.audio sessionId={} taskId={} connectionId={} eventType=binary chunkSeq={} audioBytes={} elapsedMs={}",
                task.request.sessionId(),
                task.request.taskId(),
                connectionId,
                chunkSeq,
                audioBytes.length,
                elapsedMs(task.request.startedAtNs())
        );
        task.listener.onAudioChunk(chunkSeq, audioBytes);
    }

    private void completeTask(ActiveTask task) {
        if (!detachTask(task)) {
            return;
        }
        try {
            task.listener.onCompleted();
            task.completion.complete(null);
            log.info(
                    "cosyvoice.task.finished sessionId={} taskId={} connectionId={} eventType=task-finished chunkCount={} audioBytes={} elapsedMs={}",
                    task.request.sessionId(),
                    task.request.taskId(),
                    connectionId,
                    task.chunkSeq.get(),
                    task.audioBytes,
                    elapsedMs(task.request.startedAtNs())
            );
        } catch (Exception exception) {
            task.completion.completeExceptionally(exception);
            log.error(
                    "cosyvoice.task.completion-listener-failed sessionId={} taskId={} connectionId={} eventType=task-finished elapsedMs={}",
                    task.request.sessionId(),
                    task.request.taskId(),
                    connectionId,
                    elapsedMs(task.request.startedAtNs()),
                    exception
            );
            close("completion listener failed");
        }
    }

    private void failTask(Throwable throwable, boolean closeConnection) {
        ActiveTask task = activeTask;
        if (task == null) {
            if (closeConnection) {
                close("task failed");
            }
            return;
        }
        failTask(task, throwable, closeConnection);
    }

    private void failTask(ActiveTask task, Throwable throwable, boolean closeConnection) {
        if (!detachTask(task)) {
            return;
        }
        try {
            task.listener.onFailed(throwable);
        } catch (Exception exception) {
            log.error(
                    "cosyvoice.task.failure-listener-failed sessionId={} taskId={} connectionId={} eventType=task-failed elapsedMs={}",
                    task.request.sessionId(),
                    task.request.taskId(),
                    connectionId,
                    elapsedMs(task.request.startedAtNs()),
                    exception
            );
        } finally {
            task.completion.completeExceptionally(throwable);
            log.error(
                    "cosyvoice.task.failed sessionId={} taskId={} connectionId={} eventType=task-failed elapsedMs={}",
                    task.request.sessionId(),
                    task.request.taskId(),
                    connectionId,
                    elapsedMs(task.request.startedAtNs()),
                    throwable
            );
            if (closeConnection) {
                close("task failed");
            }
        }
    }

    private boolean detachTask(ActiveTask task) {
        if (task == null || task.completion.isDone()) {
            return false;
        }
        synchronized (taskLock) {
            if (activeTask != task || task.completion.isDone()) {
                return false;
            }
            activeTask = null;
            busy.set(false);
            lastUsedAtNs = System.nanoTime();
            return true;
        }
    }

    private void sendJson(String action, Map<String, Object> payload) throws Exception {
        ActiveTask task = activeTask;
        String taskId = task != null ? task.request.taskId() : null;
        int textLength = 0;
        if ("continue-task".equals(action) && task != null && task.request.sentence() != null) {
            textLength = task.request.sentence().length();
        }
        log.info(
                "cosyvoice.outbound sessionId={} taskId={} connectionId={} eventType={} ssml={} textLength={} elapsedMs={}",
                task != null ? task.request.sessionId() : null,
                taskId,
                connectionId,
                action,
                task != null ? task.request.ssml() : null,
                textLength,
                task != null ? elapsedMs(task.request.startedAtNs()) : null
        );
        webSocket.sendText(objectMapper.writeValueAsString(payload), true).join();
    }

    private Map<String, Object> runTaskPayload(CosyVoiceTaskRequest request) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("text_type", "PlainText");
        parameters.put("voice", request.voice());
        parameters.put("format", properties.getFormat());
        parameters.put("sample_rate", properties.getSampleRate());
        parameters.put("enable_ssml", request.ssml());
        //官方：但当前版本仅处理第一个元素，因此建议只传入一个值。
        parameters.put("language_hints", List.of(CosyVoiceConnectionKey.isEnglish(request.language()) ? "en" : "zh"));
        // cosyvoice-v3-flash的 instruct需要根据不同音色调整。多个音色无法共用同一个instruct
        // parameters.put("instruction", "你说话的角色是温和客服，你说话的情感是neutral。");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_group", "audio");
        payload.put("task", "tts");
        payload.put("function", "SpeechSynthesizer");
        payload.put("model", properties.getModel());
        payload.put("parameters", parameters);
        payload.put("input", Map.of());

        return commandPayload("run-task", request.taskId(), payload);
    }

    private Map<String, Object> continueTaskPayload(CosyVoiceTaskRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", request.sentence());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", input);
        return commandPayload("continue-task", request.taskId(), payload);
    }

    private Map<String, Object> finishTaskPayload(CosyVoiceTaskRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", Map.of());
        return commandPayload("finish-task", request.taskId(), payload);
    }

    private Map<String, Object> commandPayload(String action, String taskId, Map<String, Object> payload) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("action", action);
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("header", header);
        root.put("payload", payload);
        return root;
    }

    private String currentTaskId() {
        ActiveTask task = activeTask;
        return task != null ? task.request.taskId() : null;
    }

    private ActiveTask currentTaskMatching(String taskId) {
        ActiveTask task = activeTask;
        if (task == null || taskId == null || taskId.equals(task.request.taskId())) {
            return task;
        }
        log.info(
                "cosyvoice.inbound.event.ignored taskId={} activeTaskId={} connectionId={} eventType=task-mismatch",
                taskId,
                task.request.taskId(),
                connectionId
        );
        return null;
    }

    private String payloadOutput(JsonNode root) {
        JsonNode output = root.path("payload").path("output");
        if (output.isMissingNode() || output.isNull()) {
            return null;
        }
        String value = output.toString();
        if (value.length() <= 2_000) {
            return value;
        }
        return value.substring(0, 2_000) + "...[truncated]";
    }

    private String errorMessage(JsonNode root) {
        String message = root.path("payload").path("message").asText(null);
        if (message == null || message.isBlank()) {
            message = root.path("payload").path("error").asText(null);
        }
        if (message == null || message.isBlank()) {
            message = root.toString();
        }
        return message;
    }

    private byte[] copyBytes(ByteBuffer data) {
        ByteBuffer copy = data.slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private long elapsedMs(long startedAtNs) {
        return Math.max(0L, (System.nanoTime() - startedAtNs) / 1_000_000L);
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    private static final class ActiveTask {

        private final CosyVoiceTaskRequest request;
        private final CosyVoiceTaskListener listener;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicInteger chunkSeq = new AtomicInteger();
        private int audioBytes;

        private ActiveTask(CosyVoiceTaskRequest request, CosyVoiceTaskListener listener) {
            this.request = request;
            this.listener = listener;
        }
    }
}
