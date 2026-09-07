package com.wlinsk.rd_machine.core.tts.qwenaudio;

import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.core.tts.TtsRealtimeSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QwenAudioTtsServiceTest {

    @Test
    void selectsConfiguredChineseAndEnglishVoices() {
        AiTtsProperties properties = new AiTtsProperties();
        QwenAudioRealtimeTtsClient client = mock(QwenAudioRealtimeTtsClient.class);
        TtsRealtimeSession englishSession = mock(TtsRealtimeSession.class);
        TtsRealtimeSession chineseSession = mock(TtsRealtimeSession.class);
        when(client.openSession("loongmary", "en")).thenReturn(englishSession);
        when(client.openSession("longanhuan_v3.6", "zh")).thenReturn(chineseSession);
        QwenAudioTtsService service = new QwenAudioTtsService(properties, client);

        assertSame(englishSession, service.openSession("en-US"));
        assertSame(chineseSession, service.openSession("zh-CN"));
        verify(client).openSession("loongmary", "en");
        verify(client).openSession("longanhuan_v3.6", "zh");
    }
}
