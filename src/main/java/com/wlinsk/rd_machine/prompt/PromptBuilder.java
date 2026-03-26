package com.wlinsk.rd_machine.prompt;

import com.wlinsk.rd_machine.article.ArticleDetail;
import com.wlinsk.rd_machine.session.ReadingSession;
import org.springframework.stereotype.Service;

@Service
public class PromptBuilder {

    public String buildSystemPrompt(PromptContext context) {
        return "你是一名语言学习老师。你只能基于给定文章内容提问、评价和引导。"
                + "输出必须适合语音播报，保持自然、简洁、稳定，不要使用 Markdown、HTML、emoji 或列表。"
                + "每次输出控制在 3 到 5 句话以内。"
                + "如果学生回答可能受到 ASR 误识别影响，请结合上下文做温和理解，不要直接指出识别错误。";
    }

    public String buildUserPrompt(PromptContext context) {
        ReadingSession session = context.session();
        ArticleDetail article = session.getArticle();
        StringBuilder builder = new StringBuilder();
        builder.append("文章标题：").append(article.title()).append('\n');
        builder.append("文章作者：").append(article.author()).append('\n');
        builder.append("文章语言：").append(article.language()).append('\n');
        builder.append("当前轮次：").append(session.getCurrentRoundNo()).append('\n');
        builder.append("本轮目标：").append(context.roundGoal().instruction()).append('\n');
        builder.append("文章内容：").append(article.content()).append('\n');
        if (session.getSummaryContext() != null && !session.getSummaryContext().isBlank()) {
            builder.append("最近几轮摘要：\n").append(session.getSummaryContext()).append('\n');
        }
        if (session.getPendingStudentAnswerNormalized() != null && !session.getPendingStudentAnswerNormalized().isBlank()) {
            builder.append("学生上一轮回答：").append(session.getPendingStudentAnswerNormalized()).append('\n');
        }
        if (session.getLastAssistantMessageText() != null && !session.getLastAssistantMessageText().isBlank()) {
            builder.append("老师上一轮话术：").append(session.getLastAssistantMessageText()).append('\n');
        }
        builder.append("请输出本轮老师现在应该说的话。"
                + "如果当前是最后一轮，请直接做总结并结束，不要再向学生提问。"
                + "如果不是最后一轮，请以简短反馈加一个明确问题收尾。");
        return builder.toString();
    }
}
