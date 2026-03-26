package com.wlinsk.rd_machine.session;

import java.time.Instant;
import java.util.Map;

public record ReadingTurn(
        int turnNo,
        int roundNo,
        String teacherReplyFinal,
        String studentAnswerRaw,
        String studentAnswerNormalized,
        Map<String, Object> asrMeta,
        TurnDecision decision,
        Instant startedAt,
        Instant completedAt
) {

    public ReadingTurn {
        asrMeta = asrMeta == null ? Map.of() : Map.copyOf(asrMeta);
    }
}
