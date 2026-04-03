package com.wlinsk.rd_machine.basic.model.bo;

import com.wlinsk.rd_machine.basic.enums.TurnDecision;

import java.time.Instant;

public record ReadingTurn(
        int turnNo,
        int roundNo,
        String teacherReplyFinal,
        String studentAnswerRaw,
        String studentAnswerNormalized,
        TurnDecision decision,
        Instant startedAt,
        Instant completedAt
) {
}