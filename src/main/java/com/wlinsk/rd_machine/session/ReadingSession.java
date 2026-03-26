package com.wlinsk.rd_machine.session;

import com.wlinsk.rd_machine.article.ArticleDetail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReadingSession {

    private final String sessionId;
    private final ArticleDetail article;
    private final Instant createdAt;
    private final List<ReadingTurn> turns = new ArrayList<>();
    private SessionStatus status;
    private int currentRoundNo;
    private int currentTurnNo;
    private boolean awaitingStudentAnswer;
    private String lastAssistantMessageText;
    private String summaryContext;
    private String pendingStudentAnswerRaw;
    private String pendingStudentAnswerNormalized;
    private Map<String, Object> pendingAsrMeta;
    private Integer lastClientSeq;
    private Instant updatedAt;

    public ReadingSession(String sessionId, ArticleDetail article) {
        this.sessionId = sessionId;
        this.article = article;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = SessionStatus.CREATED;
        this.currentRoundNo = 1;
        this.currentTurnNo = 1;
        this.awaitingStudentAnswer = false;
        this.summaryContext = "";
        this.pendingAsrMeta = Map.of();
    }

    public synchronized String getSessionId() {
        return sessionId;
    }

    public synchronized ArticleDetail getArticle() {
        return article;
    }

    public synchronized Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized SessionStatus getStatus() {
        return status;
    }

    public synchronized int getCurrentRoundNo() {
        return currentRoundNo;
    }

    public synchronized int getCurrentTurnNo() {
        return currentTurnNo;
    }

    public synchronized boolean isAwaitingStudentAnswer() {
        return awaitingStudentAnswer;
    }

    public synchronized String getLastAssistantMessageText() {
        return lastAssistantMessageText;
    }

    public synchronized String getSummaryContext() {
        return summaryContext;
    }

    public synchronized String getPendingStudentAnswerRaw() {
        return pendingStudentAnswerRaw;
    }

    public synchronized String getPendingStudentAnswerNormalized() {
        return pendingStudentAnswerNormalized;
    }

    public synchronized Map<String, Object> getPendingAsrMeta() {
        return pendingAsrMeta;
    }

    public synchronized Integer getLastClientSeq() {
        return lastClientSeq;
    }

    public synchronized Instant getUpdatedAt() {
        return updatedAt;
    }

    public synchronized List<ReadingTurn> getTurns() {
        return List.copyOf(turns);
    }

    public synchronized void markGenerating() {
        this.status = SessionStatus.GENERATING;
        this.awaitingStudentAnswer = false;
        touch();
    }

    public synchronized boolean acceptStudentAnswer(int clientSeq, String rawText, Map<String, Object> asrMeta) {
        if (lastClientSeq != null && lastClientSeq == clientSeq) {
            return false;
        }
        if (!awaitingStudentAnswer || status != SessionStatus.WAITING_STUDENT) {
            throw new IllegalStateException("Session is not waiting for student input");
        }
        String normalized = normalizeStudentAnswer(rawText);
        turns.add(new ReadingTurn(
                currentTurnNo,
                currentRoundNo,
                lastAssistantMessageText,
                rawText,
                normalized,
                asrMeta,
                TurnDecision.NEXT_ROUND,
                updatedAt,
                Instant.now()
        ));
        pendingStudentAnswerRaw = rawText;
        pendingStudentAnswerNormalized = normalized;
        pendingAsrMeta = asrMeta == null ? Map.of() : Map.copyOf(asrMeta);
        lastClientSeq = clientSeq;
        currentRoundNo = Math.min(currentRoundNo + 1, 5);
        currentTurnNo = currentTurnNo + 1;
        status = SessionStatus.GENERATING;
        awaitingStudentAnswer = false;
        summaryContext = buildRecentTurnsSummary();
        touch();
        return true;
    }

    public synchronized void markAssistantTurnCompleted(String assistantText) {
        this.lastAssistantMessageText = assistantText;
        this.pendingStudentAnswerRaw = null;
        this.pendingStudentAnswerNormalized = null;
        this.pendingAsrMeta = Map.of();
        if (currentRoundNo >= 5) {
            turns.add(new ReadingTurn(
                    currentTurnNo,
                    currentRoundNo,
                    assistantText,
                    null,
                    null,
                    Map.of(),
                    TurnDecision.FINISH,
                    updatedAt,
                    Instant.now()
            ));
            status = SessionStatus.COMPLETED;
            awaitingStudentAnswer = false;
        } else {
            status = SessionStatus.WAITING_STUDENT;
            awaitingStudentAnswer = true;
        }
        touch();
    }

    public synchronized void markFailed() {
        this.status = SessionStatus.FAILED;
        this.awaitingStudentAnswer = false;
        touch();
    }

    private String normalizeStudentAnswer(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText.trim().replaceAll("\\s+", " ");
    }

    private String buildRecentTurnsSummary() {
        if (turns.isEmpty()) {
            return "";
        }
        int start = Math.max(0, turns.size() - 3);
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < turns.size(); i++) {
            ReadingTurn turn = turns.get(i);
            builder.append("Round ")
                    .append(turn.roundNo())
                    .append(": Teacher=")
                    .append(nullToEmpty(turn.teacherReplyFinal()))
                    .append(" | Student=")
                    .append(nullToEmpty(turn.studentAnswerNormalized()))
                    .append('\n');
        }
        return builder.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
