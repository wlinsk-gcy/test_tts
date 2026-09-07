package com.wlinsk.rd_machine.tts;

import com.wlinsk.rd_machine.basic.model.bo.TextSegment;
import com.wlinsk.rd_machine.basic.model.bo.TtsUsage;
import com.wlinsk.rd_machine.core.tts.TtsAudioListener;
import com.wlinsk.rd_machine.core.tts.TtsRealtimeSession;
import com.wlinsk.rd_machine.core.tts.TtsUtterance;
import com.wlinsk.rd_machine.core.tts.qwenaudio.QwenAudioTtsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "runRealQwenAudioTtsTest", matches = "true")
class QwenAudioTtsSessionReuseIT {

    private static final Logger log = LoggerFactory.getLogger(QwenAudioTtsSessionReuseIT.class);

    @Autowired
    private QwenAudioTtsService ttsService;

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void recordsRawUsageForTwoTasksOnOneLogicalSession() {
        assertTrue(ttsService.isConfigured(), "Set RD_AI_TTS_API_KEY before running the real TTS probe");

        try (TtsRealtimeSession session = ttsService.openSession("zh-CN")) {
            TurnResult turn1 = synthesize(session, 1, List.of("第一轮第一段，", "第一轮第二段。"));
            TurnResult turn2 = synthesize(session, 2, List.of("第二轮第一段，", "第二轮第二段。"));

            log.info("qwen-audio-usage-probe turn1RawUsage={} turn2RawUsage={} turn1AudioBytes={} turn2AudioBytes={}",
                    turn1.rawUsage(), turn2.rawUsage(), turn1.audioBytes(), turn2.audioBytes());
            assertFalse(turn1.rawUsage().isEmpty(), "Turn1 should return TTS usage");
            assertFalse(turn2.rawUsage().isEmpty(), "Turn2 should return TTS usage");
            assertEquals(turn1.rawUsage(), turn2.rawUsage(),
                    "Equal-length tasks on one reused connection should restart usage for each turn");
            assertTrue(turn1.audioBytes() > 0, "Turn1 should return PCM audio");
            assertTrue(turn2.audioBytes() > 0, "Turn2 should return PCM audio");
        }
    }

    private TurnResult synthesize(TtsRealtimeSession session, int turnNo, List<String> chunks) {
        RecordingListener listener = new RecordingListener();
        TtsUtterance utterance = session.openUtterance(listener);
        for (int index = 0; index < chunks.size(); index++) {
            utterance.enqueue(new TextSegment(index + 1, chunks.get(index)));
        }
        utterance.finish();
        utterance.awaitFinished(Duration.ofSeconds(90));

        assertEquals(1, listener.completed.get(), "Turn " + turnNo + " should complete once");
        assertTrue(listener.errors.isEmpty(), "Turn " + turnNo + " should not fail: " + listener.errors);
        return new TurnResult(List.copyOf(listener.rawUsage), listener.audioBytes.get());
    }

    private record TurnResult(List<Long> rawUsage, long audioBytes) {
    }

    private static final class RecordingListener implements TtsAudioListener {

        private final List<Long> rawUsage = new CopyOnWriteArrayList<>();
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();
        private final AtomicLong audioBytes = new AtomicLong();
        private final AtomicInteger completed = new AtomicInteger();

        @Override
        public void onAudioChunk(int segmentSeq, byte[] audioBytes) {
            this.audioBytes.addAndGet(audioBytes == null ? 0 : audioBytes.length);
        }

        @Override
        public void onUsage(TtsUsage usage) {
            if (usage != null) {
                rawUsage.add(usage.characters());
            }
        }

        @Override
        public void onCompleted() {
            completed.incrementAndGet();
        }

        @Override
        public void onError(Throwable throwable) {
            errors.add(throwable);
        }
    }
}
