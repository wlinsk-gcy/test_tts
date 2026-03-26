package com.wlinsk.rd_machine.prompt;

import com.wlinsk.rd_machine.session.ReadingSession;

public record PromptContext(
        ReadingSession session,
        RoundGoal roundGoal
) {
}
