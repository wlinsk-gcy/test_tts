package com.wlinsk.rd_machine.tts;

import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QwenAudioTtsPropertiesTest {

    @Test
    void defaultsToQwenAudioFlashVoicesAndPcm() {
        AiTtsProperties properties = new AiTtsProperties();

        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/inference", properties.getWsUrl());
        assertEquals("qwen-audio-3.0-tts-flash", properties.getModel());
        assertEquals("longanhuan_v3.6", properties.getZhVoice());
        assertEquals("loongmary", properties.getEnVoice());
        assertEquals("pcm", properties.getResponseFormat());
        assertEquals(24000, properties.getSampleRate());
        assertEquals(16, properties.getPool().getMaxTotal());
        assertEquals(3_000L, properties.getPool().getBorrowTimeoutMs());
        assertEquals(60_000L, properties.getTaskTimeoutMs());
    }
}
