package com.wlinsk.rd_machine.core.tts.qwenaudio;

import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.core.tts.TtsRealtimeSession;
import com.wlinsk.rd_machine.core.tts.TtsSessionFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

@Primary
@Service
public class QwenAudioTtsService implements TtsSessionFactory {

    private final AiTtsProperties properties;
    private final QwenAudioRealtimeTtsClient client;

    public QwenAudioTtsService(AiTtsProperties properties, QwenAudioRealtimeTtsClient client) {
        this.properties = properties;
        this.client = client;
    }

    public boolean isConfigured() {
        return hasText(resolveApiKey()) && hasText(properties.getWsUrl()) && hasText(properties.getModel());
    }

    @Override
    public TtsRealtimeSession openSession(String articleLanguage) {
        boolean english = articleLanguage != null
                && articleLanguage.toLowerCase(Locale.ROOT).startsWith("en");
        return client.openSession(
                english ? properties.getEnVoice() : properties.getZhVoice(),
                english ? "en" : "zh"
        );
    }

    public int sampleRate() {
        return properties.getSampleRate();
    }

    public String responseFormat() {
        return properties.getResponseFormat();
    }

    public Duration taskTimeout() {
        return Duration.ofMillis(properties.getTaskTimeoutMs());
    }

    private String resolveApiKey() {
        if (hasText(properties.getApiKey())) {
            return properties.getApiKey();
        }
        return System.getenv("DASHSCOPE_API_KEY");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
