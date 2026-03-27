package com.wlinsk.rd_machine.prompt;

import com.wlinsk.rd_machine.article.ArticleDetail;
import com.wlinsk.rd_machine.llm.LlmMessage;
import com.wlinsk.rd_machine.session.ReadingSession;
import com.wlinsk.rd_machine.session.ReadingTurn;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PromptBuilder {

    private static final String SYSTEM_RULES = "You are a reading-comprehension teacher. Base every reply only on the provided article. Keep replies suitable for speech output: natural, concise, stable, with no Markdown, HTML, emoji, or bullet lists. Keep each reply within 3 to 5 sentences. If ASR may have distorted the student's answer, interpret it gently from context instead of calling out recognition mistakes. Reply in the article language. Do not end the session on your own. The conversation ends only when the client closes the session.";
    private static final String OPENING_CLOSING = "Ask exactly one clear opening question.";
    private static final String FOLLOW_UP_CLOSING = "Respond briefly to the student's latest answer, then ask exactly one clear next question. If the student is struggling, lower the difficulty or give a hint instead of repeating the same wording.";

    private final RoundPlanner roundPlanner;

    public PromptBuilder(RoundPlanner roundPlanner) {
        this.roundPlanner = roundPlanner;
    }

    public List<LlmMessage> buildMessages(PromptContext context) {
        ReadingSession session = context.session();
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("system", buildSystemPrompt(session.getArticle())));

        if (session.getTurns().isEmpty()) {
            messages.add(new LlmMessage("user", buildUserTurnMessage(null, context.roundGoal())));
            return List.copyOf(messages);
        }

        messages.add(new LlmMessage("user", buildUserTurnMessage(null, roundPlanner.goalForRound(1))));
        for (ReadingTurn turn : session.getTurns()) {
            if (hasText(turn.teacherReplyFinal())) {
                messages.add(new LlmMessage("assistant", turn.teacherReplyFinal().trim()));
            }
            if (hasText(turn.studentAnswerNormalized())) {
                int nextRoundNo = turn.roundNo() + 1;
                RoundGoal nextGoal = nextRoundNo == context.roundGoal().roundNo()
                        ? context.roundGoal()
                        : roundPlanner.goalForRound(nextRoundNo);
                messages.add(new LlmMessage("user", buildUserTurnMessage(turn.studentAnswerNormalized(), nextGoal)));
            }
        }
        return List.copyOf(messages);
    }

    private String buildSystemPrompt(ArticleDetail article) {
        return SYSTEM_RULES + "\n\n"
                + "<article>\n"
                + "  <title>" + escapeXml(article.title()) + "</title>\n"
                + "  <author>" + escapeXml(article.author()) + "</author>\n"
                + "  <language>" + escapeXml(article.language()) + "</language>\n"
                + "  <content>" + escapeXml(article.content()) + "</content>\n"
                + "</article>";
    }

    private String buildUserTurnMessage(String studentAnswer, RoundGoal goal) {
        StringBuilder builder = new StringBuilder();
        if (hasText(studentAnswer)) {
            builder.append("<studentAnswer>")
                    .append(escapeXml(studentAnswer.trim()))
                    .append("</studentAnswer>\n");
        }
        builder.append("<turnGoal>")
                .append(escapeXml(goal.instruction()))
                .append("</turnGoal>\n")
                .append(goal.roundNo() <= 1 ? OPENING_CLOSING : FOLLOW_UP_CLOSING);
        return builder.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
