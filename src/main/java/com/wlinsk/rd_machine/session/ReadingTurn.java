package com.wlinsk.rd_machine.session;

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