package com.wlinsk.rd_machine.basic.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public final class ReadingTtsLogHelper {

    private ReadingTtsLogHelper() {
    }

    public static void logPhase(
            String requestId,
            String sessionId,
            String language,
            String phase,
            Long elapsedMs,
            Map<String, Object> data
    ) {
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("event", "reading.tts");
        putIfHasText(logEntry, "requestId", requestId);
        putIfHasText(logEntry, "sessionId", sessionId);
        putIfHasText(logEntry, "language", language);
        putIfHasText(logEntry, "phase", phase);
        if (elapsedMs != null && elapsedMs >= 0L) {
            logEntry.put("elapsedMs", elapsedMs);
        }
        if (data != null && !data.isEmpty()) {
            logEntry.put("data", LogPayloadSanitizer.sanitizeValue(data));
        }
        log.info("{}",LogPayloadSanitizer.toJson(logEntry));
    }

    public static void logUpstreamEvent(
            String upstreamSessionId,
            String voice,
            String languageType,
            String phase,
            Map<String, Object> data
    ) {
        logUpstreamEvent(upstreamSessionId, voice, languageType, phase, data, "inbound");
    }

    public static void logUpstreamOutboundEvent(
            String upstreamSessionId,
            String voice,
            String languageType,
            String phase,
            Map<String, Object> data
    ) {
        logUpstreamEvent(upstreamSessionId, voice, languageType, phase, data, "outbound");
    }

    private static void logUpstreamEvent(
            String upstreamSessionId,
            String voice,
            String languageType,
            String phase,
            Map<String, Object> data,
            String direction
    ) {
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("event", "tts.upstream");
        putIfHasText(logEntry, "upstreamSessionId", upstreamSessionId);
        putIfHasText(logEntry, "voice", voice);
        putIfHasText(logEntry, "languageType", languageType);
        putIfHasText(logEntry, "phase", phase);
        putIfHasText(logEntry, "direction", direction);
        if (data != null && !data.isEmpty()) {
            logEntry.put("data", LogPayloadSanitizer.sanitizeValue(data));
        }
        log.info("{}", LogPayloadSanitizer.toJson(logEntry));
    }

    private static void putIfHasText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }
}
