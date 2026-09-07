package com.wlinsk.rd_machine.core.tts;

import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.enums.SysCode;
import com.wlinsk.rd_machine.basic.exception.BasicException;
import com.wlinsk.rd_machine.basic.logging.ReadingTtsLogHelper;
import com.wlinsk.rd_machine.basic.model.bo.TtsSessionRef;
import com.wlinsk.rd_machine.utils.snowflake.IdUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TtsSessionRegistry {

    private final TtsSessionFactory sessionFactory;
    private final Clock clock;
    private final int maxActiveSessions;
    private final Duration idleTimeout;
    private final Map<String, RegisteredTtsSession> sessions = new ConcurrentHashMap<>();

    public TtsSessionRegistry(TtsSessionFactory sessionFactory, AiTtsProperties properties) {
        this(
                sessionFactory,
                Clock.systemUTC(),
                properties.getSession().getMaxActiveSessions(),
                Duration.ofMillis(properties.getSession().getIdleTimeoutMs())
        );
    }

    public TtsSessionRegistry(TtsSessionFactory sessionFactory, Clock clock, int maxActiveSessions, Duration idleTimeout) {
        this.sessionFactory = sessionFactory;
        this.clock = clock;
        this.maxActiveSessions = maxActiveSessions;
        this.idleTimeout = idleTimeout == null ? Duration.ofMinutes(5) : idleTimeout;
    }

    public synchronized TtsSessionRef getOrCreate(String requestedSessionId, String language) {
        // 清掉空闲超时(idleTimeoutMs=300s) 或上游已关闭(isClose())的session
        evictExpiredSessions();
        String sessionId = requestedSessionId == null || requestedSessionId.isBlank()
                ? IdUtils.build(null)
                : requestedSessionId;
        RegisteredTtsSession existing = sessions.get(sessionId);
        if (existing != null) {
            existing.assertLanguage(language);
            // 刷新lastAccessAt
            existing.touch(clock.instant());
            ReadingTtsLogHelper.logPhase(null, sessionId, language, "session.reused", null, Map.of());
            return existing.sessionRef(); // 复用逻辑会话；每轮任务从全局池借用暖连接
        }
        // max=128
        if (sessions.size() >= maxActiveSessions) {
            throw new BasicException(SysCode.TTS_SESSION_LIMIT_REACHED);
        }
        TtsRealtimeSession realtimeSession = sessionFactory.openSession(language);
        RegisteredTtsSession created = new RegisteredTtsSession(
                new TtsSessionRef(sessionId, language, realtimeSession),
                clock.instant()
        );
        sessions.put(sessionId, created);
        ReadingTtsLogHelper.logPhase(null, sessionId, language, "session.created", null, Map.of());
        return created.sessionRef();
    }

    public synchronized TtsSessionRef getRequired(String sessionId, String language) {
        evictExpiredSessions();
        RegisteredTtsSession existing = sessions.get(sessionId);
        if (existing == null) {
            throw new BasicException(SysCode.TTS_SESSION_NOT_FOUND);
        }
        existing.assertLanguage(language);
        existing.touch(clock.instant());
        ReadingTtsLogHelper.logPhase(null, sessionId, language, "session.reused", null, Map.of());
        return existing.sessionRef();
    }

    public synchronized void close(String sessionId) {
        RegisteredTtsSession removed = sessions.remove(sessionId);
        if (removed != null) {
            ReadingTtsLogHelper.logPhase(null, sessionId, removed.sessionRef().language(), "session.closed", null, Map.of());
            removed.close();
        }
    }

    private void evictExpiredSessions() {
        Instant now = clock.instant();
        Iterator<Map.Entry<String, RegisteredTtsSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, RegisteredTtsSession> entry = iterator.next();
            RegisteredTtsSession registeredSession = entry.getValue();
            boolean expired = registeredSession.isExpired(now, idleTimeout);
            boolean sessionClosed = registeredSession.sessionRef().session().isClosed();
            if (expired || sessionClosed) {
                sessions.remove(entry.getKey(), registeredSession);
                ReadingTtsLogHelper.logPhase(
                        null,
                        registeredSession.sessionRef().sessionId(),
                        registeredSession.sessionRef().language(),
                        "session.evicted",
                        null,
                        Map.of("reason", expired ? "idle-timeout" : "upstream-closed")
                );
                registeredSession.close();
            }
        }
    }

    private static final class RegisteredTtsSession {

        private final TtsSessionRef sessionRef;
        private Instant lastAccessAt;

        private RegisteredTtsSession(TtsSessionRef sessionRef, Instant lastAccessAt) {
            this.sessionRef = sessionRef;
            this.lastAccessAt = lastAccessAt;
        }

        private TtsSessionRef sessionRef() {
            return sessionRef;
        }

        private void touch(Instant accessAt) {
            this.lastAccessAt = accessAt;
        }

        private void assertLanguage(String language) {
            if (language == null || language.equals(sessionRef.language())) {
                return;
            }
            throw new BasicException(SysCode.TTS_SESSION_LANGUAGE_MISMATCH);
        }

        private boolean isExpired(Instant now, Duration idleTimeout) {
            return idleTimeout != null && !idleTimeout.isNegative() && lastAccessAt.plus(idleTimeout).isBefore(now);
        }

        private void close() {
            sessionRef.session().close();
        }
    }
}
