package com.wlinsk.rd_machine.transport.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlinsk.rd_machine.basic.logging.WebSocketAccessLogHelper;
import com.wlinsk.rd_machine.basic.model.bo.LlmUsage;
import com.wlinsk.rd_machine.basic.model.bo.StreamingSessionContext;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
public class AssistantEventPublisher {

    private final ObjectMapper objectMapper;
    private final SessionConnectionRegistry connectionRegistry;
    private final WebSocketAccessLogHelper accessLogHelper;
    private final WebSocketTextMessageSender messageSender;

    public AssistantEventPublisher(
            ObjectMapper objectMapper,
            SessionConnectionRegistry connectionRegistry,
            WebSocketAccessLogHelper accessLogHelper
    ) {
        this(objectMapper, connectionRegistry, accessLogHelper, new WebSocketTextMessageSender());
    }

    @Autowired
    public AssistantEventPublisher(
            ObjectMapper objectMapper,
            SessionConnectionRegistry connectionRegistry,
            WebSocketAccessLogHelper accessLogHelper,
            WebSocketTextMessageSender messageSender
    ) {
        this.objectMapper = objectMapper;
        this.connectionRegistry = connectionRegistry;
        this.accessLogHelper = accessLogHelper;
        this.messageSender = messageSender;
    }

    public void publishTextDelta(StreamingSessionContext context, String delta) {
        publish(new AssistantEvent("assistant.text.delta", context.sessionId(), context.turnNo(), context.roundNo(), Map.of("delta", delta)));
    }

    public void publishTextDone(StreamingSessionContext context, String text) {
        publish(new AssistantEvent("assistant.text.done", context.sessionId(), context.turnNo(), context.roundNo(), Map.of("text", text)));
    }

    public void publishAudioChunk(StreamingSessionContext context, int segmentSeq, String audioFormat, int sampleRate, byte[] audioBytes) {
        publish(new AssistantEvent(
                "assistant.audio.chunk",
                context.sessionId(),
                context.turnNo(),
                context.roundNo(),
                Map.of(
                        "segmentSeq", segmentSeq,
                        "audioFormat", audioFormat,
                        "sampleRate", sampleRate,
                        "chunkBase64", Base64.getEncoder().encodeToString(audioBytes)
                )
        ));
    }

    public void publishAudioDone(StreamingSessionContext context) {
        publish(new AssistantEvent("assistant.audio.done", context.sessionId(), context.turnNo(), context.roundNo(), Map.of()));
    }

    public void publishTiming(StreamingSessionContext context, String phase, long serverTimestampMs, long elapsedMs) {
        publish(new AssistantEvent(
                "assistant.debug.timing",
                context.sessionId(),
                context.turnNo(),
                context.roundNo(),
                Map.of(
                        "phase", phase,
                        "serverTimestampMs", serverTimestampMs,
                        "elapsedMs", elapsedMs
                )
        ));
    }

    public void publishTurnDone(StreamingSessionContext context, boolean awaitingStudentAnswer) {
        publish(new AssistantEvent(
                "assistant.turn.done",
                context.sessionId(),
                context.turnNo(),
                context.roundNo(),
                Map.of("awaitingStudentAnswer", awaitingStudentAnswer)
        ));
    }

    public void publishUsage(StreamingSessionContext context, LlmUsage llmUsage, long ttsCharacters) {
        Map<String, Object> llm = llmUsage == null
                ? Map.of()
                : Map.of(
                        "promptTokens", llmUsage.promptTokens(),
                        "completionTokens", llmUsage.completionTokens(),
                        "totalTokens", llmUsage.totalTokens(),
                        "promptTokensDetails", Map.of("cachedTokens", llmUsage.cachedTokens())
                );
        publish(new AssistantEvent(
                "assistant.usage",
                context.sessionId(),
                context.turnNo(),
                context.roundNo(),
                Map.of(
                        "llm", llm,
                        "tts", Map.of("characters", ttsCharacters)
                )
        ));
    }

    public void publishError(StreamingSessionContext context, String code, String message) {
        publish(new AssistantEvent(
                "assistant.error",
                context.sessionId(),
                context.turnNo(),
                context.roundNo(),
                Map.of("code", code, "message", message)
        ));
    }

    private void publish(AssistantEvent event) {
        String payload = serialize(event);
        List<WebSocketSession> connections = connectionRegistry.getConnections(event.sessionId());
        accessLogHelper.logAssistantOutbound(event, connections.size());
        for (WebSocketSession connection : connections) {
            if (!connection.isOpen()) {
                continue;
            }
            try {
                messageSender.send(connection, payload);
            } catch (Exception ignored) {
                // Best-effort push for transient frontend connections.
            }
        }
    }

    private String serialize(AssistantEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize assistant event", exception);
        }
    }
}
