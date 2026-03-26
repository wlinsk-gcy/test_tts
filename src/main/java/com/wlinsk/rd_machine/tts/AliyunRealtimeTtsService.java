package com.wlinsk.rd_machine.tts;

import com.wlinsk.rd_machine.config.AiTtsProperties;
import org.springframework.stereotype.Service;

@Service
public class AliyunRealtimeTtsService {

    private final AiTtsProperties properties;
    private final AliyunRealtimeTtsClient client;

    public AliyunRealtimeTtsService(AiTtsProperties properties, AliyunRealtimeTtsClient client) {
        this.properties = properties;
        this.client = client;
    }

    public boolean isConfigured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.getWsUrl() != null && !properties.getWsUrl().isBlank()
                && properties.getModel() != null && !properties.getModel().isBlank();
    }

    public TtsStreamSession openSession(String articleLanguage, TtsAudioListener audioListener) {
        TtsSynthesisRequest request = new TtsSynthesisRequest(
                properties.getVoice(),
                properties.getMode(),
                properties.getResponseFormat(),
                properties.getSampleRate(),
                articleLanguage != null && articleLanguage.startsWith("en") ? "English" : "Chinese"
        );
        return client.openSession(request, audioListener);
    }

    public int sampleRate() {
        return properties.getSampleRate();
    }

    public String responseFormat() {
        return properties.getResponseFormat();
    }
}
