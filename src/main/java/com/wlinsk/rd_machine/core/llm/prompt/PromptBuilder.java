package com.wlinsk.rd_machine.core.llm.prompt;

import com.wlinsk.rd_machine.basic.model.bo.LlmMessage;
import com.wlinsk.rd_machine.basic.model.bo.ArticleDetail;
import com.wlinsk.rd_machine.basic.model.bo.PromptContext;
import com.wlinsk.rd_machine.basic.model.bo.RoundGoal;
import com.wlinsk.rd_machine.basic.model.bo.ReadingSession;
import com.wlinsk.rd_machine.basic.model.bo.ReadingTurn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PromptBuilder {

    /*private static final String SYSTEM_RULES = """
            You are a reading-comprehension teacher guiding a student through the provided article.
            Base every reply only on the provided article and its directly supported meaning. Do not add facts, background, or interpretation beyond the article.
            Speak in a natural, concise, steady style suitable for speech output. Do not use Markdown, HTML, emoji, bullet lists, or special formatting.
            Keep each reply within 3 to 5 sentences.
            Always reply in the language of the article.
            Always speak from a third-person, outside-the-story perspective. Do not role-play as any character in the article, and do not use the article’s first-person narrator as your own voice. When referring to characters such as “我”, “他”, or “她”, clearly identify them as the narrator or character in the article rather than speaking as them.
            When asking questions or giving feedback, maintain the teacher’s external perspective. If the student gives a short, indirect, or conversational answer, interpret the answer by meaning and context first. If the answer is essentially correct, confirm it briefly and naturally in teacher voice before giving a short article-based explanation.
            If ASR may have distorted the student’s answer, interpret it gently from context instead of pointing out recognition mistakes.
            """;*/
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
