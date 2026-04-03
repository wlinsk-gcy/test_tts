package com.wlinsk.rd_machine.basic.model.bo;

import com.wlinsk.rd_machine.core.tts.TtsRealtimeSession;

public record TtsSessionRef(
        String sessionId,
        String language,
        TtsRealtimeSession session
) {
}
