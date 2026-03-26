package com.wlinsk.rd_machine.prompt;

public record RoundGoal(
        int roundNo,
        String title,
        String instruction,
        boolean finalRound
) {
}
