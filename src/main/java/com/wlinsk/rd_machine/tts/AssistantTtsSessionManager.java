package com.wlinsk.rd_machine.tts;

import com.wlinsk.rd_machine.config.AiTtsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
public class AssistantTtsSessionManager {

    private final TtsSessionRegistry sessionRegistry;

    @Autowired
    public AssistantTtsSessionManager(TtsSessionFactory sessionFactory, AiTtsProperties properties) {
        this(new TtsSessionRegistry(
                sessionFactory,
                Clock.systemUTC(),
                properties.getSession().getMaxActiveSessions(),
                Duration.ofMillis(properties.getSession().getIdleTimeoutMs())
        ));
    }

    AssistantTtsSessionManager(TtsSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    public TtsSessionRef getOrCreate(String sessionId, String language) {
        return sessionRegistry.getOrCreate(sessionId, language);
    }

    public void close(String sessionId) {
        sessionRegistry.close(sessionId);
    }
}
