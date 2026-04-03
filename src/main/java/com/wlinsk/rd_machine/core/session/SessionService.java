package com.wlinsk.rd_machine.core.session;

import com.wlinsk.rd_machine.basic.model.bo.ArticleDetail;
import com.wlinsk.rd_machine.basic.model.bo.ReadingSession;
import com.wlinsk.rd_machine.core.streaming.ActiveAssistantTurnRegistry;
import com.wlinsk.rd_machine.core.streaming.AssistantStreamingOrchestrator;
import com.wlinsk.rd_machine.basic.model.dto.SessionSnapshotResponse;
import com.wlinsk.rd_machine.basic.model.dto.SubmitTurnRequest;
import com.wlinsk.rd_machine.core.tts.AssistantTtsSessionManager;
import com.wlinsk.rd_machine.utils.snowflake.IdUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class SessionService {

    private final InMemorySessionStore sessionStore;
    private final ActiveAssistantTurnRegistry activeTurnRegistry;
    private final ObjectProvider<AssistantStreamingOrchestrator> orchestratorProvider;
    private final AssistantTtsSessionManager assistantTtsSessionManager;

    public SessionService(
            InMemorySessionStore sessionStore,
            ActiveAssistantTurnRegistry activeTurnRegistry,
            ObjectProvider<AssistantStreamingOrchestrator> orchestratorProvider,
            AssistantTtsSessionManager assistantTtsSessionManager
    ) {
        this.sessionStore = sessionStore;
        this.activeTurnRegistry = activeTurnRegistry;
        this.orchestratorProvider = orchestratorProvider;
        this.assistantTtsSessionManager = assistantTtsSessionManager;
    }

    public ReadingSession createSession(String title, String author, String language, String content) {
        ArticleDetail article = new ArticleDetail(title, author, language, content);
        ReadingSession session = new ReadingSession(IdUtils.build(null), article);
        session.markGenerating();
        sessionStore.save(session);
        triggerAssistantTurn(session.getSessionId());
        return session;
    }

    public boolean submitStudentTurn(String sessionId, SubmitTurnRequest request) {
        ReadingSession session = getRequiredSession(sessionId);
        if (session.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is closed");
        }
        long clientSeq = request.clientSeq() == null ? session.getCurrentTurnNo() : request.clientSeq();
        boolean accepted;
        try {
            accepted = session.acceptStudentAnswer(clientSeq, request.text());
        } catch (IllegalStateException exception) {
            log.error("acceptStudentAnswer error: ", exception);
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
        if (accepted) {
            triggerAssistantTurn(sessionId);
        }
        return accepted;
    }

    public ReadingSession getRequiredSession(String sessionId) {
        try {
            return sessionStore.getRequired(sessionId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    public SessionSnapshotResponse closeSession(String sessionId) {
        ReadingSession session = getRequiredSession(sessionId);
        session.close();
        activeTurnRegistry.cancel(sessionId);
        assistantTtsSessionManager.close(sessionId);
        return getSnapshot(sessionId);
    }

    public SessionSnapshotResponse getSnapshot(String sessionId) {
        ReadingSession session = getRequiredSession(sessionId);
        return new SessionSnapshotResponse(
                session.getSessionId(),
                session.getArticle().title(),
                session.getArticle().author(),
                session.getArticle().language(),
                session.getStatus().name(),
                session.getCurrentRoundNo(),
                session.getCurrentTurnNo(),
                session.isAwaitingStudentAnswer(),
                session.getLastAssistantMessageText(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getTurns().stream()
                        .map(turn -> new SessionSnapshotResponse.TurnSnapshot(
                                turn.turnNo(),
                                turn.roundNo(),
                                turn.teacherReplyFinal(),
                                turn.studentAnswerRaw(),
                                turn.studentAnswerNormalized(),
                                turn.decision().name(),
                                turn.startedAt(),
                                turn.completedAt()
                        ))
                        .toList()
        );
    }

    private void triggerAssistantTurn(String sessionId) {
        orchestratorProvider.getObject().startAssistantTurn(sessionId);
    }
}
