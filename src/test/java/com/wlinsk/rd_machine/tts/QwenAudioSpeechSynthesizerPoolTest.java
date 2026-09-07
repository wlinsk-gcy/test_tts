package com.wlinsk.rd_machine.core.tts.qwenaudio;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.exception.BasicException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QwenAudioSpeechSynthesizerPoolTest {

    @Test
    void resolvesExistingFrontendPcmFormat() {
        assertEquals(
                SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT,
                QwenAudioAudioFormatResolver.resolve("pcm", 24_000)
        );
    }

    @Test
    void rejectsUnsupportedAudioFormat() {
        assertThrows(BasicException.class, () -> QwenAudioAudioFormatResolver.resolve("aac", 24_000));
    }

    @Test
    void configuresBoundedPoolWithoutOpeningAConnection() {
        AiTtsProperties properties = new AiTtsProperties();
        properties.getPool().setMaxTotal(7);
        properties.getPool().setBorrowTimeoutMs(1_234L);

        try (QwenAudioSpeechSynthesizerPool pool = new QwenAudioSpeechSynthesizerPool(properties)) {
            assertEquals(7, pool.maxTotal());
            assertEquals(7, pool.maxIdle());
            assertEquals(1_234L, pool.maxWaitMillis());
            assertEquals(0, pool.numIdle());
        }
    }
}
