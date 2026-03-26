package com.wlinsk.rd_machine.streaming;

public record StreamingSessionContext(
        String sessionId,
        int turnNo,
        int roundNo
) {
}
