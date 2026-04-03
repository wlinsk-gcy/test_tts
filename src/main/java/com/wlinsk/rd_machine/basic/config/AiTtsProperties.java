package com.wlinsk.rd_machine.basic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rd.ai.tts")
public class AiTtsProperties {

    private String apiKey;
    private String wsUrl;
    private String model;
    private String zhVoice = "Ethan";
    private String enVoice = "Aiden";
    private int sampleRate = 24000;
    private String mode = "server_commit";
    private String responseFormat = "pcm";
    private int segmentQueueCapacity = 32;
    private boolean debugLogUpstreamEvents;
    private Commit commit = new Commit();
    private Session session = new Session();

    public void setCommit(Commit commit) {
        this.commit = commit == null ? new Commit() : commit;
    }

    public void setSession(Session session) {
        this.session = session == null ? new Session() : session;
    }

    public String normalizedMode() {
        return "server_commit";
    }

    @Data
    public static class Commit {

        private int minLength = 8;
        private int maxLength = 28;
        private long maxWaitMs = 250L;
        private String softPunctuation = ",\uFF0C";
        private String hardPunctuation = ".!?\u3002\uFF01\uFF1F";

    }

    @Data
    public static class Session {

        private int maxActiveSessions = 128;
        private long idleTimeoutMs = 300_000L;
    }
}
