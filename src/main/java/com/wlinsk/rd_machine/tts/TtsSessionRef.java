package com.wlinsk.rd_machine.tts;

public record TtsSessionRef(
        String sessionId,
        String language,
        TtsRealtimeSession session
) {
}
