package com.wlinsk.rd_machine.tts;

import com.wlinsk.rd_machine.RdMachineApplication;
import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.model.dto.TtsChunkEvent;
import com.wlinsk.rd_machine.basic.model.dto.TtsSentenceStreamRequest;
import com.wlinsk.rd_machine.core.tts.ReadingTtsStreamService;
import com.wlinsk.rd_machine.core.tts.TtsChunkEventSink;
import com.wlinsk.rd_machine.utils.snowflake.IdUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(
        classes = RdMachineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rd.ai.tts.debug-log-upstream-events=true",
                "rd.log.path=./logs/test",
                "rd.log.name=tts_reuse_integration"
        }
)
@Tag("integration")
class AliyunRealtimeTtsSessionReuseIT {

    private static final Logger TEST_LOG = LoggerFactory.getLogger(AliyunRealtimeTtsSessionReuseIT.class);
    private static final DateTimeFormatter LOG_FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter LOG_LINE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.systemDefault());
    private static final String CYCLE_COUNT_PROPERTY = "tts.reuse.probe.cycles";
    private static final List<String> SENTENCES = List.of(
            "Banana.",
            "Carrot.",
            "Teacher."
    );

    @Autowired
    private ReadingTtsStreamService readingTtsStreamService;

    @Autowired
    private AiTtsProperties ttsProperties;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void reusesAliyunRealtimeSessionForMultipleSentences() throws Exception {
        String sessionId = null;
        try (ProbeLog probeLog = ProbeLog.open()) {
            probeLog.info("probeLog=" + probeLog.path());
            probeLog.info("appLog=" + Path.of("logs", "test", "tts_reuse_integration.log").toAbsolutePath().normalize());
            probeLog.info("debugLogUpstreamEvents=" + ttsProperties.isDebugLogUpstreamEvents()
                    + " hasApiKey=" + hasText(ttsProperties.getApiKey())
                    + " wsUrl=" + ttsProperties.getWsUrl()
                    + " model=" + ttsProperties.getModel());
            assumeTrue(
                    hasText(ttsProperties.getApiKey()),
                    () -> "Set RD_AI_TTS_API_KEY to run the real Aliyun realtime TTS reuse probe. probeLog="
                            + probeLog.path()
            );
            int cycles = cycleCount();
            probeLog.info("cycles=" + cycles + " sentencesPerCycle=" + SENTENCES.size());

            try {
                for (int cycle = 0; cycle < cycles; cycle++) {
                    for (int index = 0; index < SENTENCES.size(); index++) {
                        int sequence = cycle * SENTENCES.size() + index + 1;
                        String observedSessionId = streamSentence(sequence, sessionId, SENTENCES.get(index), probeLog);
                        if (sessionId == null) {
                            sessionId = observedSessionId;
                        } else {
                            assertEquals(
                                    sessionId,
                                    observedSessionId,
                                    () -> "Expected the backend to reuse the same TTS session. probeLog=" + probeLog.path()
                            );
                        }
                    }
                }
            } finally {
                if (hasText(sessionId)) {
                    probeLog.info("CLOSE sessionId=" + sessionId);
                    readingTtsStreamService.closeSession(sessionId);
                }
            }
        }
    }

    private String streamSentence(int sequence, String requestedSessionId, String sentence, ProbeLog probeLog) {
        String requestId = "local-tts-reuse-" + sequence + "-" + IdUtils.build(null);
        AtomicReference<String> activeSessionId = new AtomicReference<>();
        AtomicReference<String> terminalPhase = new AtomicReference<>("running");
        RecordingSink sink = new RecordingSink(probeLog);
        long startedAtNs = System.nanoTime();

        probeLog.info("REQUEST seq=" + sequence
                + " requestId=" + requestId
                + " requestedSessionId=" + nullToDash(requestedSessionId)
                + " sentence=\"" + sentence + "\"");
        readingTtsStreamService.streamSentence(
                new TtsSentenceStreamRequest(requestedSessionId, "en-US", sentence),
                sink,
                activeSessionId,
                requestId,
                startedAtNs,
                terminalPhase
        );

        long elapsedMs = Math.max(0L, (System.nanoTime() - startedAtNs) / 1_000_000L);
        List<TtsChunkEvent> events = sink.events();
        String observedSessionId = firstText(sink.sessionId(), activeSessionId.get());
        probeLog.info("RESULT seq=" + sequence
                + " requestId=" + requestId
                + " terminalPhase=" + terminalPhase.get()
                + " activeSessionId=" + nullToDash(activeSessionId.get())
                + " observedSessionId=" + nullToDash(observedSessionId)
                + " elapsedMs=" + elapsedMs
                + " events=" + eventTypes(events));

        assertFalse(events.isEmpty(), () -> "No TTS events were emitted. probeLog=" + probeLog.path());
        Optional<TtsChunkEvent> errorEvent = firstEvent(events, "audio.error");
        if (errorEvent.isPresent()) {
            probeLog.error("AUDIO_ERROR seq=" + sequence
                    + " requestId=" + requestId
                    + " sessionId=" + nullToDash(errorEvent.get().sessionId())
                    + " data=" + summarizeData(errorEvent.get().data()));
        }
        assertTrue(
                errorEvent.isEmpty(),
                () -> "TTS emitted audio.error on seq=" + sequence
                        + " sessionId=" + nullToDash(observedSessionId)
                        + " data=" + summarizeData(errorEvent.orElseThrow().data())
                        + " probeLog=" + probeLog.path()
        );
        assertTrue(
                hasEvent(events, "audio.done"),
                () -> "Expected audio.done for seq=" + sequence + " but saw " + eventTypes(events)
                        + ". probeLog=" + probeLog.path()
        );
        assertNotNull(observedSessionId, () -> "Expected a sessionId in emitted events. probeLog=" + probeLog.path());
        return observedSessionId;
    }

    private static int cycleCount() {
        String rawValue = System.getProperty(CYCLE_COUNT_PROPERTY, "1");
        try {
            int value = Integer.parseInt(rawValue);
            if (value < 1) {
                throw new IllegalArgumentException(CYCLE_COUNT_PROPERTY + " must be >= 1: " + rawValue);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(CYCLE_COUNT_PROPERTY + " must be an integer: " + rawValue, exception);
        }
    }

    private static Optional<TtsChunkEvent> firstEvent(List<TtsChunkEvent> events, String type) {
        return events.stream()
                .filter(event -> type.equals(event.type()))
                .findFirst();
    }

    private static boolean hasEvent(List<TtsChunkEvent> events, String type) {
        return firstEvent(events, type).isPresent();
    }

    private static String eventTypes(List<TtsChunkEvent> events) {
        return events.stream()
                .map(TtsChunkEvent::type)
                .toList()
                .toString();
    }

    private static Map<String, Object> summarizeData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>(data);
        Object chunkBase64 = summary.remove("chunkBase64");
        if (chunkBase64 instanceof String text) {
            summary.put("chunkBase64Chars", text.length());
        }
        return summary;
    }

    private static String firstText(String first, String second) {
        if (hasText(first)) {
            return first;
        }
        return hasText(second) ? second : null;
    }

    private static String nullToDash(String value) {
        return hasText(value) ? value : "-";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final class RecordingSink implements TtsChunkEventSink {

        private final ProbeLog probeLog;
        private final List<TtsChunkEvent> events = new CopyOnWriteArrayList<>();
        private String sessionId;

        private RecordingSink(ProbeLog probeLog) {
            this.probeLog = probeLog;
        }

        @Override
        public void emit(TtsChunkEvent event) {
            events.add(event);
            sessionId = firstText(sessionId, event.sessionId());
            probeLog.info("EVENT type=" + event.type()
                    + " sessionId=" + nullToDash(event.sessionId())
                    + " data=" + summarizeData(event.data()));
        }

        @Override
        public void complete() {
            probeLog.info("SINK_COMPLETE eventCount=" + events.size() + " events=" + eventTypes(events));
        }

        private List<TtsChunkEvent> events() {
            return List.copyOf(events);
        }

        private String sessionId() {
            return sessionId;
        }
    }

    private static final class ProbeLog implements AutoCloseable {

        private final Path path;
        private final BufferedWriter writer;

        private ProbeLog(Path path, BufferedWriter writer) {
            this.path = path;
            this.writer = writer;
        }

        private static ProbeLog open() throws IOException {
            Path logDir = Path.of("logs", "test").toAbsolutePath().normalize();
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("tts_reuse_probe_" + LOG_FILE_TIME.format(Instant.now()) + ".log");
            BufferedWriter writer = Files.newBufferedWriter(
                    logFile,
                    StandardCharsets.UTF_8
            );
            return new ProbeLog(logFile, writer);
        }

        private Path path() {
            return path;
        }

        private void info(String message) {
            write("INFO", message);
            TEST_LOG.info("{}", message);
        }

        private void error(String message) {
            write("ERROR", message);
            TEST_LOG.error("{}", message);
        }

        private synchronized void write(String level, String message) {
            try {
                writer.write(LOG_LINE_TIME.format(Instant.now()));
                writer.write(' ');
                writer.write(level);
                writer.write(' ');
                writer.write(message);
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Override
        public synchronized void close() throws IOException {
            writer.close();
        }
    }
}
