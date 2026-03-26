package com.wlinsk.rd_machine.transport.http.dto;

public record CreateSessionResponse(
        String sessionId,
        String status,
        int currentRoundNo,
        int currentTurnNo,
        String wsUrl
) {
}
