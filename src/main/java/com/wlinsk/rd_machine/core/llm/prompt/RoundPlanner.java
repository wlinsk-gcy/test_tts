package com.wlinsk.rd_machine.core.llm.prompt;

import com.wlinsk.rd_machine.basic.model.bo.RoundGoal;
import org.springframework.stereotype.Service;

@Service
public class RoundPlanner {

    private static final String OPENING_INSTRUCTION = "Start the conversation with one clear reading-comprehension question grounded in the article. Keep the first question easy enough for the student to answer in one short response.";
    private static final String FOLLOW_UP_INSTRUCTION = "Use the student's latest answer and the earlier dialogue to decide the next most helpful question. First respond briefly to the student's answer, then either deepen understanding, correct gently, or lower the difficulty by giving a hint. Do not repeat the same wording when the student is stuck, and do not end the session on your own.";
    private static final String WRAP_UP_INSTRUCTION = "This is the final turn of the conversation. First respond briefly to the student's latest answer, then give short and warm encouragement and praise for the student's effort. Finally, tell the student that the conversation is finished and invite them to click the button below to end the conversation. Do not ask any new question.";

    public RoundGoal goalForRound(int roundNo) {
        if (roundNo <= 1) {
            return new RoundGoal(1, "opening", OPENING_INSTRUCTION);
        }
        return new RoundGoal(roundNo, "follow-up", FOLLOW_UP_INSTRUCTION);
    }

    public RoundGoal wrapUpGoalForRound(int roundNo) {
        return new RoundGoal(roundNo, "closing", WRAP_UP_INSTRUCTION);
    }
}
