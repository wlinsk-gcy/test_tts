package com.wlinsk.rd_machine.basic.model.dto;

import java.util.Base64;
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
        return audioChunk(sessionId, chunkSeq, audioFormat, sampleRate, audioBytes);
    }

    public static TtsChunkEvent cosyVoiceAudioDone(String sessionId, String taskId) {
        return audioDone(sessionId);
    }

    public static TtsChunkEvent cosyVoiceAudioError(String sessionId, String taskId, String code, String message) {
        return audioError(sessionId, code, message);
    }
}
