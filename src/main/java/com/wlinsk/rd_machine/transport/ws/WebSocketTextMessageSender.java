package com.wlinsk.rd_machine.transport.ws;

import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class WebSocketTextMessageSender {

    public void send(WebSocketSession session, String payload) throws IOException {
        synchronized (session) {
            session.sendMessage(new TextMessage(payload));
        }
    }
}
