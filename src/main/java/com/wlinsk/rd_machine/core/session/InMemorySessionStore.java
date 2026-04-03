package com.wlinsk.rd_machine.core.session;

import com.wlinsk.rd_machine.basic.model.bo.ReadingSession;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySessionStore {

    private final Map<String, ReadingSession> sessions = new ConcurrentHashMap<>();

    public ReadingSession save(ReadingSession session) {
        sessions.put(session.getSessionId(), session);
        return session;
    }

    public Optional<ReadingSession> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public ReadingSession getRequired(String sessionId) {
        return findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sessionId: " + sessionId));
    }

    public Collection<ReadingSession> findAll() {
        return sessions.values();
    }
}
