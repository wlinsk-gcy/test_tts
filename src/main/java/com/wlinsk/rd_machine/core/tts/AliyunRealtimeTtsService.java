package com.wlinsk.rd_machine.core.tts;

import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.model.bo.TtsSynthesisRequest;
import com.wlinsk.rd_machine.core.streaming.TextSegmenter;
import org.springframework.stereotype.Service;

@Service
public class AliyunRealtimeTtsService implements TtsSessionFactory {

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

    @Override
    public TtsRealtimeSession openSession(String articleLanguage) {
        TtsSynthesisRequest request = new TtsSynthesisRequest(
                articleLanguage != null && articleLanguage.startsWith("en") ? properties.getEnVoice() : properties.getZhVoice(),
                properties.normalizedMode(),
                properties.getResponseFormat(),
                properties.getSampleRate(),
                articleLanguage != null && articleLanguage.startsWith("en") ? "English" : "Chinese"
        );
        return client.openSession(request);
    }

    public int sampleRate() {
        return properties.getSampleRate();
    }

    public String responseFormat() {
        return properties.getResponseFormat();
    }

    public TextSegmenter createTextSegmenter() {
        AiTtsProperties.Commit commit = properties.getCommit();
        return new TextSegmenter(new TextSegmenter.Settings(
                commit.getMinLength(),
                commit.getMaxLength(),
                commit.getMaxWaitMs(),
                commit.getSoftPunctuation(),
                commit.getHardPunctuation()
        ));
    }
}
