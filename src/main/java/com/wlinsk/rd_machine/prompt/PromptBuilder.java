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

    private static final String SYSTEM_RULES = "你是一名语言学习老师。你只能基于给定文章内容提问、评价和引导。"
            + "输出必须适合语音播报，保持自然、简洁、稳定，不要使用 Markdown、HTML、emoji 或列表。"
            + "每次输出控制在 3 到 5 句话以内。"
            + "如果学生回答可能受到 ASR 误识别影响，请结合上下文做温和理解，不要直接指出识别错误。";
    private static final String FOLLOW_UP_CLOSING = "请先做简短反馈，再用一个明确问题收尾。";
    private static final String FINAL_CLOSING = "请直接做总结并结束，不要再向学生提问。";

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
                .append(goal.finalRound() ? FINAL_CLOSING : FOLLOW_UP_CLOSING);
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
