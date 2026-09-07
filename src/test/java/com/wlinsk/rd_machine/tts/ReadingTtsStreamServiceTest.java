package com.wlinsk.rd_machine.tts;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.logging.ReadingTtsLogHelper;
import com.wlinsk.rd_machine.basic.model.bo.TextSegment;
import com.wlinsk.rd_machine.basic.model.dto.TtsChunkEvent;
import com.wlinsk.rd_machine.basic.model.dto.TtsSentenceStreamRequest;
import com.wlinsk.rd_machine.core.tts.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingTtsStreamServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC);
    private static final String READING_TTS_LOGGER_NAME = ReadingTtsLogHelper.class.getName();
    private static final String STREAM_SERVICE_LOGGER_NAME = ReadingTtsStreamService.class.getName();

    @Test
    void streamsAudioChunkAndDoneEventsWithFrontendCompatiblePayloadFields() {
        RecordingTtsSessionFactory sessionFactory = new RecordingTtsSessionFactory();
        ReadingTtsSessionManager sessionManager = new ReadingTtsSessionManager(
                new TtsSessionRegistry(sessionFactory, FIXED_CLOCK, 4, Duration.ofMinutes(10))
        );
        ExecutorService executor = new ImmediateExecutorService();
        try {
            ReadingTtsStreamService service = new ReadingTtsStreamService(
                    sessionManager,
                    ttsProperties(),
                    executor
            );
            CollectingTtsChunkEventSink sink = new CollectingTtsChunkEventSink();

            service.streamSentence(
                    new TtsSentenceStreamRequest(null, "en-US", "hello world."),
                    sink,
                    new AtomicReference<>(),
                    "request-1",
                    System.nanoTime(),
                    new AtomicReference<>("running")
            );
            List<TtsChunkEvent> events = sink.events();

            assertNotNull(events);
            assertEquals(List.of("audio.chunk", "audio.done"), events.stream().map(TtsChunkEvent::type).toList());
            assertNotNull(events.getFirst().sessionId());
            assertEquals(events.getFirst().sessionId(), events.get(1).sessionId());
            assertEquals(1, events.getFirst().data().get("segmentSeq"));
            assertEquals("pcm", events.getFirst().data().get("audioFormat"));
            assertEquals(24000, events.getFirst().data().get("sampleRate"));
            assertNotNull(events.getFirst().data().get("chunkBase64"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sendsWholeZhSentenceAfterOpenCcNormalizationWithoutLocalChunking() {
        RecordingTtsSessionFactory sessionFactory = new RecordingTtsSessionFactory();
        ReadingTtsSessionManager sessionManager = new ReadingTtsSessionManager(
                new TtsSessionRegistry(sessionFactory, FIXED_CLOCK, 4, Duration.ofMinutes(10))
        );
        ExecutorService executor = new ImmediateExecutorService();
        try {
            ReadingTtsStreamService service = new ReadingTtsStreamService(
                    sessionManager,
                    ttsProperties(),
                    executor
            );
            String sentence = "瞭解這是一段很長很長而且超過二十八個字的繁體中文文字內容用來確認不再切段";

            service.streamSentence(
                    new TtsSentenceStreamRequest(null, "zh-TW", sentence),
                    new CollectingTtsChunkEventSink(),
                    new AtomicReference<>(),
                    "request-whole-sentence",
                    System.nanoTime(),
                    new AtomicReference<>("running")
            );

            assertEquals(
                    List.of(new TextSegment(1, "了解这是一段很长很长而且超过二十八个字的繁体中文文字内容用来确认不再切段")),
                    sessionFactory.session().queuedSegments()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void returnsAudioErrorEventWhenSessionIdIsUnknown() {
        ReadingTtsSessionManager sessionManager = new ReadingTtsSessionManager(
                new TtsSessionRegistry(language -> new RecordingTtsRealtimeSession(), FIXED_CLOCK, 4, Duration.ofMinutes(10))
        );
        ExecutorService executor = new ImmediateExecutorService();
        try {
            ReadingTtsStreamService service = new ReadingTtsStreamService(
                    sessionManager,
                    ttsProperties(),
                    executor
            );
            CollectingTtsChunkEventSink sink = new CollectingTtsChunkEventSink();

            service.streamSentence(
                    new TtsSentenceStreamRequest("missing-session", "en-US", "hello"),
                    sink,
                    new AtomicReference<>(),
                    "request-2",
                    System.nanoTime(),
                    new AtomicReference<>("running")
            );
            List<TtsChunkEvent> events = sink.events();

            assertNotNull(events);
            assertEquals(1, events.size());
            assertEquals("audio.error", events.getFirst().type());
            assertEquals("9987", events.getFirst().data().get("code"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void logsReadingTtsStreamPhasesForSuccessfulRequest() {
        Logger logger = (Logger) LoggerFactory.getLogger(READING_TTS_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        RecordingTtsSessionFactory sessionFactory = new RecordingTtsSessionFactory();
        ReadingTtsSessionManager sessionManager = new ReadingTtsSessionManager(
                new TtsSessionRegistry(sessionFactory, FIXED_CLOCK, 4, Duration.ofMinutes(10))
        );
        ExecutorService executor = new ImmediateExecutorService();
        try {
            ReadingTtsStreamService service = new ReadingTtsStreamService(
                    sessionManager,
                    ttsProperties(),
                    executor
            );

            CollectingTtsChunkEventSink sink = new CollectingTtsChunkEventSink();
            service.streamSentence(
                    new TtsSentenceStreamRequest(null, "zh-CN", "hello."),
                    sink,
                    new AtomicReference<>(),
                    "request-3",
                    System.nanoTime(),
                    new AtomicReference<>("running")
            );

            assertFalse(appender.list.isEmpty());
            String joinedMessages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(joinedMessages.contains("\"phase\":\"stream.start\""));
            assertTrue(joinedMessages.contains("\"phase\":\"text.normalized\""));
            assertTrue(joinedMessages.contains("\"phase\":\"utterance.opened\""));
            assertTrue(joinedMessages.contains("\"phase\":\"tts.first.audio\""));
            assertTrue(joinedMessages.contains("\"phase\":\"stream.completed\""));
        } finally {
            logger.detachAppender(appender);
            executor.shutdownNow();
        }
    }

    @Test
    void logsExceptionWithStackTraceWhenStreamingFails() {
        Logger logger = (Logger) LoggerFactory.getLogger(STREAM_SERVICE_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        ReadingTtsSessionManager sessionManager = new ReadingTtsSessionManager(
                new TtsSessionRegistry(language -> new FailingTtsRealtimeSession(), FIXED_CLOCK, 4, Duration.ofMinutes(10))
        );
        ExecutorService executor = new ImmediateExecutorService();
        try {
            ReadingTtsStreamService service = new ReadingTtsStreamService(
                    sessionManager,
                    ttsProperties(),
                    executor
            );

            CollectingTtsChunkEventSink sink = new CollectingTtsChunkEventSink();
            service.streamSentence(
                    new TtsSentenceStreamRequest(null, "zh-CN", "timeout please."),
                    sink,
                    new AtomicReference<>(),
                    "request-timeout",
                    System.nanoTime(),
                    new AtomicReference<>("running")
            );

            ILoggingEvent errorEvent = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .findFirst()
                    .orElseThrow();
            assertTrue(errorEvent.getFormattedMessage().contains("request-timeout"));
            assertTrue(errorEvent.getFormattedMessage().contains("reading tts stream failed"));
            IThrowableProxy throwableProxy = errorEvent.getThrowableProxy();
            assertNotNull(throwableProxy);
            assertEquals(IllegalStateException.class.getName(), throwableProxy.getClassName());
            assertEquals("streaming boom", throwableProxy.getMessage());
            assertTrue(Arrays.stream(throwableProxy.getStackTraceElementProxyArray())
                    .anyMatch(frame -> frame.getSTEAsString().contains("ReadingTtsStreamServiceTest.buildStreamingFailure")));
        } finally {
            logger.detachAppender(appender);
            executor.shutdownNow();
        }
    }

    private static AiTtsProperties ttsProperties() {
        AiTtsProperties properties = new AiTtsProperties();
        properties.setResponseFormat("pcm");
        properties.setSampleRate(24000);
        return properties;
    }

    private static final class RecordingTtsSessionFactory implements TtsSessionFactory {

        private final RecordingTtsRealtimeSession session = new RecordingTtsRealtimeSession();

        @Override
        public TtsRealtimeSession openSession(String language) {
            return session;
        }

        private RecordingTtsRealtimeSession session() {
            return session;
        }
    }

    private static final class RecordingTtsRealtimeSession implements TtsRealtimeSession {

        private boolean closed;
        private final List<TextSegment> queuedSegments = new ArrayList<>();

        @Override
        public TtsUtterance openUtterance(TtsAudioListener audioListener) {
            return new TtsUtterance() {
                @Override
                public void enqueue(TextSegment textSegment) {
                    queuedSegments.add(textSegment);
                }

                @Override
                public void finish() {
                    audioListener.onSessionReady();
                    for (TextSegment queuedSegment : queuedSegments) {
                        audioListener.onAudioChunk(
                                queuedSegment.segmentSeq(),
                                ("pcm-" + queuedSegment.segmentSeq()).getBytes(StandardCharsets.UTF_8)
                        );
                    }
                    audioListener.onCompleted();
                }

                @Override
                public void awaitFinished(Duration timeout) {
                }
            };
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }

        private List<TextSegment> queuedSegments() {
            return List.copyOf(queuedSegments);
        }
    }

    private static final class FailingTtsRealtimeSession implements TtsRealtimeSession {

        private boolean closed;

        @Override
        public TtsUtterance openUtterance(TtsAudioListener audioListener) {
            return new TtsUtterance() {
                @Override
                public void enqueue(TextSegment textSegment) {
                }

                @Override
                public void finish() {
                }

                @Override
                public void awaitFinished(Duration timeout) {
                    throw buildStreamingFailure();
                }
            };
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class ImmediateExecutorService extends AbstractExecutorService {

        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class CollectingTtsChunkEventSink implements TtsChunkEventSink {

        private final List<TtsChunkEvent> events = new ArrayList<>();

        @Override
        public void emit(TtsChunkEvent event) {
            events.add(event);
        }

        @Override
        public void complete() {
        }

        private List<TtsChunkEvent> events() {
            return events;
        }
    }

    private static RuntimeException buildStreamingFailure() {
        return new IllegalStateException("streaming boom");
    }
}
