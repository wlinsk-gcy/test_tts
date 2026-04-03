package com.wlinsk.rd_machine.basic.model.dto;

public record CreateSessionResponse(
        String sessionId,
        String status,
        int currentRoundNo,
        int currentTurnNo,
        String wsUrl
) {
}
