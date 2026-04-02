package com.wlinsk.rd_machine.logging;

import com.wlinsk.rd_machine.transport.ws.AssistantEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class WebSocketAccessLogHelper {


    public void logConnectionEstablished(WebSocketSession session) {
        Map<String, Object> logEntry = baseSessionEntry("connection.established", session);
        log.info("{}", LogPayloadSanitizer.toJson(logEntry));
    }

    public void logConnectionClosed(WebSocketSession session, CloseStatus status) {
        Map<String, Object> logEntry = baseSessionEntry("connection.closed", session);
        logEntry.put("closeCode", status != null ? status.getCode() : null);
        logEntry.put("closeReason", status != null ? status.getReason() : null);
        log.info("{}", LogPayloadSanitizer.toJson(logEntry));
    }

    public void logTransportError(WebSocketSession session, Throwable exception) {
        Map<String, Object> logEntry = baseSessionEntry("transport.error", session);
        logEntry.put("error", exception != null ? exception.getMessage() : null);
        log.info("{}", LogPayloadSanitizer.toJson(logEntry));
    }

    public void logInboundText(WebSocketSession session, String payload) {
        Map<String, Object> logEntry = baseSessionEntry("message", session);
        logEntry.put("direction", "inbound");
        logEntry.put("payload", LogPayloadSanitizer.sanitizePayload(payload));
        log.info("{}", LogPayloadSanitizer.toJson(logEntry));
    }

    public void logDirectOutboundText(WebSocketSession session, String payload, String replyType) {
        Map<String, Object> logEntry = baseSessionEntry("message", session);
        logEntry.put("direction", "outbound");
        logEntry.put("replyType", replyType);
        logEntry.put("payload", LogPayloadSanitizer.sanitizePayload(payload));
        log.info("{}", LogPayloadSanitizer.toJson(logEntry));
    }

    public void logAssistantOutbound(AssistantEvent event, int recipientCount) {
        Map<String, Object> logEntry = new LinkedHashMap<>();
//        logEntry.put("category", "ws");
        logEntry.put("event", "message");
        logEntry.put("direction", "outbound");
        logEntry.put("type", event.type());
        logEntry.put("sessionId", event.sessionId());
        logEntry.put("turnNo", event.turnNo());
        logEntry.put("roundNo", event.roundNo());
        logEntry.put("recipientCount", recipientCount);
        logEntry.put("data", LogPayloadSanitizer.sanitizeValue(event.data()));
        log.info("{}", LogPayloadSanitizer.toJson(logEntry));
    }

    private Map<String, Object> baseSessionEntry(String eventName, WebSocketSession session) {
        Map<String, Object> logEntry = new LinkedHashMap<>();
//        logEntry.put("category", "ws");
        logEntry.put("event", eventName);
        logEntry.put("connectionId", session != null ? session.getId() : null);
        logEntry.put("path", session != null ? asPath(session.getUri()) : null);
        logEntry.put("sessionId", resolveSessionId(session));
        return logEntry;
    }

    private String resolveSessionId(WebSocketSession session) {
        if (session == null || session.getUri() == null) {
            return null;
        }
        try {
            return com.wlinsk.rd_machine.transport.ws.SessionConnectionRegistry.extractSessionId(session.getUri());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String asPath(URI uri) {
        return uri != null ? uri.getPath() : null;
    }
}
