package com.wlinsk.rd_machine.prompt;

import org.springframework.stereotype.Service;

@Service
public class RoundPlanner {

    private static final String OPENING_INSTRUCTION = "Start the conversation with one clear reading-comprehension question grounded in the article. Keep the first question easy enough for the student to answer in one short response.";
    private static final String FOLLOW_UP_INSTRUCTION = "Use the student's latest answer and the earlier dialogue to decide the next most helpful question. First respond briefly to the student's answer, then either deepen understanding, correct gently, or lower the difficulty by giving a hint. Do not repeat the same wording when the student is stuck, and do not end the session on your own.";

    public RoundGoal goalForRound(int roundNo) {
        if (roundNo <= 1) {
            return new RoundGoal(1, "opening", OPENING_INSTRUCTION);
        }
        return new RoundGoal(roundNo, "follow-up", FOLLOW_UP_INSTRUCTION);
    }
}
