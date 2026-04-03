package com.wlinsk.rd_machine.basic.model.bo;

public record StreamingSessionContext(
        String sessionId,
        int turnNo,
        int roundNo
) {
}
