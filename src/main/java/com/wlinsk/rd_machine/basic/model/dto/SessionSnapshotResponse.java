package com.wlinsk.rd_machine.basic.model.dto;

import java.time.Instant;
import java.util.List;

public record SessionSnapshotResponse(
        String sessionId,
        String title,
        String author,
        String language,
        String status,
        int currentRoundNo,
        int currentTurnNo,
        boolean awaitingStudentAnswer,
        String lastAssistantMessageText,
        Instant createdAt,
        Instant updatedAt,
        List<TurnSnapshot> turns
) {

    public record TurnSnapshot(
            int turnNo,
            int roundNo,
            String teacherReplyFinal,
            String studentAnswerRaw,
            String studentAnswerNormalized,
            String decision,
            Instant startedAt,
            Instant completedAt
    ) {
    }
}
