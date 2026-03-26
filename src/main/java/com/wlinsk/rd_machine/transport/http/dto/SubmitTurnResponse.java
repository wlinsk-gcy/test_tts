package com.wlinsk.rd_machine.transport.http.dto;

public record SubmitTurnResponse(
        boolean accepted,
        String sessionId,
        String status,
        int currentRoundNo,
        int currentTurnNo
) {
}
