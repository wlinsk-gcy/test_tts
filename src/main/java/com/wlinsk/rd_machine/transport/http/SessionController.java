package com.wlinsk.rd_machine.transport.http;

import com.wlinsk.rd_machine.session.ReadingSession;
import com.wlinsk.rd_machine.session.SessionService;
import com.wlinsk.rd_machine.transport.http.dto.CreateSessionRequest;
import com.wlinsk.rd_machine.transport.http.dto.CreateSessionResponse;
import com.wlinsk.rd_machine.transport.http.dto.SessionSnapshotResponse;
import com.wlinsk.rd_machine.transport.http.dto.SubmitTurnRequest;
import com.wlinsk.rd_machine.transport.http.dto.SubmitTurnResponse;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "http://localhost:5173")
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public CreateSessionResponse createSession(@RequestBody CreateSessionRequest request) {
        ReadingSession session = sessionService.createSession(request.articleId());
        return new CreateSessionResponse(
                session.getSessionId(),
                session.getStatus().name(),
                session.getCurrentRoundNo(),
                session.getCurrentTurnNo(),
                "/ws/sessions/" + session.getSessionId()
        );
    }

    @PostMapping("/{sessionId}/turns")
    public SubmitTurnResponse submitTurn(@PathVariable String sessionId, @RequestBody SubmitTurnRequest request) {
        boolean accepted = sessionService.submitStudentTurn(sessionId, request);
        ReadingSession session = sessionService.getRequiredSession(sessionId);
        return new SubmitTurnResponse(
                accepted,
                session.getSessionId(),
                session.getStatus().name(),
                session.getCurrentRoundNo(),
                session.getCurrentTurnNo()
        );
    }

    @GetMapping("/{sessionId}")
    public SessionSnapshotResponse getSnapshot(@PathVariable String sessionId) {
        return sessionService.getSnapshot(sessionId);
    }
}