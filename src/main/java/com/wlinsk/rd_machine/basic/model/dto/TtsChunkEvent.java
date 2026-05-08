package com.wlinsk.rd_machine.basic.model.dto;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public record TtsChunkEvent(
        String type,
        String sessionId,
        Map<String, Object> data
) {

    public static TtsChunkEvent audioChunk(String sessionId, int segmentSeq, String audioFormat, int sampleRate, byte[] audioBytes) {
        return new TtsChunkEvent(
                "audio.chunk",
                sessionId,
                Map.of(
                        "segmentSeq", segmentSeq,
                        "audioFormat", audioFormat,
                        "sampleRate", sampleRate,
                        "chunkBase64", Base64.getEncoder().encodeToString(audioBytes)
                )
        );
    }

    public static TtsChunkEvent audioDone(String sessionId) {
        return new TtsChunkEvent("audio.done", sessionId, Map.of());
    }

    public static TtsChunkEvent audioError(String sessionId, String code, String message) {
        return new TtsChunkEvent(
                "audio.error",
                sessionId,
                Map.of(
                        "code", code,
                        "message", message
                )
        );
    }

    public static TtsChunkEvent cosyVoiceAudioChunk(
            String sessionId,
            String taskId,
            int chunkSeq,
            String audioFormat,
            int sampleRate,
            byte[] audioBytes
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("chunkSeq", chunkSeq);
        data.put("audioFormat", audioFormat);
        data.put("sampleRate", sampleRate);
        data.put("chunkBase64", Base64.getEncoder().encodeToString(audioBytes));
        return new TtsChunkEvent("audio.chunk", sessionId, data);
    }

    public static TtsChunkEvent cosyVoiceEvent(
            String sessionId,
            String taskId,
            String eventType,
            String requestId,
            Object usage,
            Object payload
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("eventType", eventType);
        data.put("requestId", requestId);
        data.put("usage", usage);
        data.put("payload", payload);
        return new TtsChunkEvent("cosyvoice.event", sessionId, data);
    }

    public static TtsChunkEvent cosyVoiceAudioDone(String sessionId, String taskId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        return new TtsChunkEvent("audio.done", sessionId, data);
    }

    public static TtsChunkEvent cosyVoiceAudioError(String sessionId, String taskId, String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("code", code);
        data.put("message", message);
        return new TtsChunkEvent("audio.error", sessionId, data);
    }
}
