package com.wlinsk.rd_machine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rd.ai.tts")
public class AiTtsProperties {

    private String apiKey;
    private String wsUrl;
    private String model;
    private String zhVoice = "Ethan";
    private String enVoice = "Ryan";
    private int sampleRate = 24000;
    private String mode = "server_commit";
    private String responseFormat = "pcm";
    private int segmentQueueCapacity = 32;
    private Commit commit = new Commit();

    public void setCommit(Commit commit) {
        this.commit = commit == null ? new Commit() : commit;
    }

    public String normalizedMode() {
        return "commit".equalsIgnoreCase(mode) ? "commit" : "server_commit";
    }

    public boolean usesClientCommit() {
        return "commit".equals(normalizedMode());
    }

    @Data
    public static class Commit {

        private int minLength = 8;
        private int maxLength = 28;
        private long maxWaitMs = 250L;
        private String softPunctuation = ",\uFF0C";
        private String hardPunctuation = ".!?\u3002\uFF01\uFF1F";

    }
}
