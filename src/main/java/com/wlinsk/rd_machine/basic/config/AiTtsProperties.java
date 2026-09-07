package com.wlinsk.rd_machine.basic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rd.ai.tts")
public class AiTtsProperties {

    private String apiKey;
    private String wsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    private String model = "qwen-audio-3.0-tts-flash";
    private String zhVoice = "longanhuan_v3.6";
    private String enVoice = "loongmary";
    private int sampleRate = 24000;
    private String responseFormat = "pcm";
    private int streamQueueCapacity = 32;
    private long taskTimeoutMs = 60_000L;
    private Pool pool = new Pool();
    private Session session = new Session();

    public void setSession(Session session) {
        this.session = session == null ? new Session() : session;
    }

    public void setPool(Pool pool) {
        this.pool = pool == null ? new Pool() : pool;
    }

    @Data
    public static class Session {

        private int maxActiveSessions = 128;
        private long idleTimeoutMs = 300_000L;
    }

    @Data
    public static class Pool {

        private int maxTotal = 16;
        private long borrowTimeoutMs = 3_000L;
    }
}
