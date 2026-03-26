package com.wlinsk.rd_machine.transport.ws;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
public class SessionConnectionRegistry {

    private final Map<String, CopyOnWriteArraySet<WebSocketSession>> sessionsByConversation = new ConcurrentHashMap<>();

    public void register(String sessionId, WebSocketSession webSocketSession) {
        sessionsByConversation
                .computeIfAbsent(sessionId, ignored -> new CopyOnWriteArraySet<>())
                .add(webSocketSession);
    }

    public void remove(String sessionId, WebSocketSession webSocketSession) {
        Optional.ofNullable(sessionsByConversation.get(sessionId)).ifPresent(set -> {
            set.remove(webSocketSession);
            if (set.isEmpty()) {
                sessionsByConversation.remove(sessionId);
            }
        });
    }

    public List<WebSocketSession> getConnections(String sessionId) {
        return List.copyOf(sessionsByConversation.getOrDefault(sessionId, new CopyOnWriteArraySet<>()));
    }

    public boolean awaitAtLeastOneConnection(String sessionId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!getConnections(sessionId).isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !getConnections(sessionId).isEmpty();
    }

    public static String extractSessionId(URI uri) {
        String path = uri.getPath();
        int index = path.lastIndexOf('/');
        if (index < 0 || index == path.length() - 1) {
            throw new IllegalArgumentException("Cannot resolve sessionId from path: " + path);
        }
        return path.substring(index + 1);
    }
}
