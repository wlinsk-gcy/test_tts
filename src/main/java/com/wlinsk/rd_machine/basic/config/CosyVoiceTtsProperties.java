package com.wlinsk.rd_machine.basic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

@Data
@ConfigurationProperties(prefix = "rd.ai.cosyvoice")
public class CosyVoiceTtsProperties {

    private String apiKey;
    private String wsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";
    private String model = "cosyvoice-v3-flash";
    private int sampleRate = 22050;
    private String format = "pcm";
    private String zhVoice = "zh_voice";
    private String enVoice = "en_voice";
    private long connectTimeoutMs = 30_000L;
    private long taskTimeoutMs = 60_000L;
    private long streamTimeoutMs = 300_000L;
    private Pool pool = new Pool();

    public void setPool(Pool pool) {
        this.pool = pool == null ? new Pool() : pool;
    }

    public String resolveVoice(String language) {
        if (language != null && language.toLowerCase(Locale.ROOT).startsWith("en")) {
            return enVoice;
        }
        return zhVoice;
    }

    @Data
    public static class Pool {

        private int maxSize = 16;
        private long acquireTimeoutMs = 3_000L;
        private long idleTtlMs = 55_000L;
    }
}
