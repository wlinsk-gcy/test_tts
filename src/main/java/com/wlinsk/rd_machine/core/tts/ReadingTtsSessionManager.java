package com.wlinsk.rd_machine.core.tts;

import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.model.bo.TtsSessionRef;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
public class ReadingTtsSessionManager {

    private final TtsSessionRegistry sessionRegistry;

    @Autowired
    public ReadingTtsSessionManager(TtsSessionFactory sessionFactory, AiTtsProperties properties) {
        this(new TtsSessionRegistry(
                sessionFactory,
                Clock.systemUTC(),
                properties.getSession().getMaxActiveSessions(),
                Duration.ofMillis(properties.getSession().getIdleTimeoutMs())
        ));
    }

    public ReadingTtsSessionManager(TtsSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    public TtsSessionRef getOrCreate(String requestedSessionId, String language) {
        return sessionRegistry.getOrCreate(requestedSessionId, language);
    }

    public TtsSessionRef getRequired(String sessionId, String language) {
        return sessionRegistry.getRequired(sessionId, language);
    }

    public void close(String sessionId) {
        sessionRegistry.close(sessionId);
    }
}
