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
    private Long lastClientSeq;
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

    public synchronized Long getLastClientSeq() {
        return lastClientSeq;
    }

    public synchronized Instant getUpdatedAt() {
        return updatedAt;
    }

    public synchronized List<ReadingTurn> getTurns() {
        return List.copyOf(turns);
    }

    public synchronized void markGenerating() {
        if (status == SessionStatus.CLOSED) {
            return;
        }
        this.status = SessionStatus.GENERATING;
        this.awaitingStudentAnswer = false;
        touch();
    }

    public synchronized boolean acceptStudentAnswer(long clientSeq, String rawText, Map<String, Object> asrMeta) {
        if (status == SessionStatus.CLOSED) {
            throw new IllegalStateException("Session is closed");
        }
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
        lastClientSeq = clientSeq;
        currentRoundNo = currentRoundNo + 1;
        currentTurnNo = currentTurnNo + 1;
        status = SessionStatus.GENERATING;
        awaitingStudentAnswer = false;
        touch();
        return true;
    }

    public synchronized void markAssistantTurnCompleted(String assistantText) {
        if (status == SessionStatus.CLOSED) {
            return;
        }
        this.lastAssistantMessageText = assistantText;
        status = SessionStatus.WAITING_STUDENT;
        awaitingStudentAnswer = true;
        touch();
    }

    public synchronized void markFailed() {
        if (status == SessionStatus.CLOSED) {
            return;
        }
        this.status = SessionStatus.FAILED;
        this.awaitingStudentAnswer = false;
        touch();
    }

    public synchronized boolean isClosed() {
        return status == SessionStatus.CLOSED;
    }

    public synchronized void close() {
        this.status = SessionStatus.CLOSED;
        this.awaitingStudentAnswer = false;
        touch();
    }

    private String normalizeStudentAnswer(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText.trim().replaceAll("\\s+", " ");
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
