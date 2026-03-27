package com.wlinsk.rd_machine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rd.ai.tts")
public class AiTtsProperties {

    private String apiKey;
    private String wsUrl;
    private String model;
    private String voice = "Cherry";
    private int sampleRate = 24000;
    private String mode = "server_commit";
    private String responseFormat = "pcm";
    private Commit commit = new Commit();

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public void setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
    }

    public Commit getCommit() {
        return commit;
    }

    public void setCommit(Commit commit) {
        this.commit = commit == null ? new Commit() : commit;
    }

    public String normalizedMode() {
        return "commit".equalsIgnoreCase(mode) ? "commit" : "server_commit";
    }

    public boolean usesClientCommit() {
        return "commit".equals(normalizedMode());
    }

    public static class Commit {

        private int minLength = 8;
        private int maxLength = 28;
        private long maxWaitMs = 250L;
        private String softPunctuation = ",\uFF0C";
        private String hardPunctuation = ".!?\u3002\uFF01\uFF1F";

        public int getMinLength() {
            return minLength;
        }

        public void setMinLength(int minLength) {
            this.minLength = minLength;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        public long getMaxWaitMs() {
            return maxWaitMs;
        }

        public void setMaxWaitMs(long maxWaitMs) {
            this.maxWaitMs = maxWaitMs;
        }

        public String getSoftPunctuation() {
            return softPunctuation;
        }

        public void setSoftPunctuation(String softPunctuation) {
            this.softPunctuation = softPunctuation;
        }

        public String getHardPunctuation() {
            return hardPunctuation;
        }

        public void setHardPunctuation(String hardPunctuation) {
            this.hardPunctuation = hardPunctuation;
        }
    }
}
