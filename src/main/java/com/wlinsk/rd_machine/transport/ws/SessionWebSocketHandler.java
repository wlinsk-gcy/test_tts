package com.wlinsk.rd_machine.transport.ws;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class SessionWebSocketHandler extends TextWebSocketHandler {

    private final SessionConnectionRegistry connectionRegistry;

    public SessionWebSocketHandler(SessionConnectionRegistry connectionRegistry) {
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        connectionRegistry.register(resolveSessionId(session), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connectionRegistry.remove(resolveSessionId(session), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if ("ping".equalsIgnoreCase(message.getPayload())) {
            session.sendMessage(new TextMessage("pong"));
            return;
        }
        session.sendMessage(new TextMessage("{\"type\":\"noop\",\"data\":{}}"));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        connectionRegistry.remove(resolveSessionId(session), session);
    }

    private String resolveSessionId(WebSocketSession session) {
        return SessionConnectionRegistry.extractSessionId(session.getUri());
    }
}
