package com.wlinsk.rd_machine.transport.ws;

import com.wlinsk.rd_machine.logging.WebSocketAccessLogHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class SessionWebSocketHandler extends TextWebSocketHandler {

    private final SessionConnectionRegistry connectionRegistry;
    private final WebSocketAccessLogHelper accessLogHelper;

    public SessionWebSocketHandler(
            SessionConnectionRegistry connectionRegistry,
            WebSocketAccessLogHelper accessLogHelper
    ) {
        this.connectionRegistry = connectionRegistry;
        this.accessLogHelper = accessLogHelper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        accessLogHelper.logConnectionEstablished(session);
        connectionRegistry.register(resolveSessionId(session), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        accessLogHelper.logConnectionClosed(session, status);
        connectionRegistry.remove(resolveSessionId(session), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        accessLogHelper.logInboundText(session, message.getPayload());
        if ("ping".equalsIgnoreCase(message.getPayload())) {
            accessLogHelper.logDirectOutboundText(session, "pong", "ping");
            session.sendMessage(new TextMessage("pong"));
            return;
        }
        String noopPayload = "{\"type\":\"noop\",\"data\":{}}";
        accessLogHelper.logDirectOutboundText(session, noopPayload, "noop");
        session.sendMessage(new TextMessage(noopPayload));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        accessLogHelper.logTransportError(session, exception);
        connectionRegistry.remove(resolveSessionId(session), session);
    }

    private String resolveSessionId(WebSocketSession session) {
        return SessionConnectionRegistry.extractSessionId(session.getUri());
    }
}
