package com.wlinsk.rd_machine.basic.model.dto;

public record SubmitTurnResponse(
        boolean accepted,
        String sessionId,
        String status,
        int currentRoundNo,
        int currentTurnNo
) {
}
