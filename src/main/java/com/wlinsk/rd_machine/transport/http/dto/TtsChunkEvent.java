package com.wlinsk.rd_machine.transport.http.dto;

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
}
