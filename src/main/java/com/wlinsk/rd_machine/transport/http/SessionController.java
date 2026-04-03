package com.wlinsk.rd_machine.transport.http;

import com.wlinsk.rd_machine.basic.model.Result;
import com.wlinsk.rd_machine.basic.model.dto.*;
import com.wlinsk.rd_machine.basic.model.bo.ReadingSession;
import com.wlinsk.rd_machine.core.session.SessionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public Result<CreateSessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        ReadingSession session = sessionService.createSession(
                request.title(),
                request.author(),
                request.language(),
                request.content()
        );
        CreateSessionResponse response = new CreateSessionResponse(
                session.getSessionId(),
                session.getStatus().name(),
                session.getCurrentRoundNo(),
                session.getCurrentTurnNo(),
                "/ws/sessions/" + session.getSessionId()
        );
        return Result.ok(response);
    }

    @PostMapping("/{sessionId}/turns")
    public Result<SubmitTurnResponse> submitTurn(@PathVariable String sessionId, @RequestBody SubmitTurnRequest request) {
        boolean accepted = sessionService.submitStudentTurn(sessionId, request);
        ReadingSession session = sessionService.getRequiredSession(sessionId);
        SubmitTurnResponse response = new SubmitTurnResponse(
                accepted,
                session.getSessionId(),
                session.getStatus().name(),
                session.getCurrentRoundNo(),
                session.getCurrentTurnNo()
        );

        return Result.ok(response);
    }

    @PostMapping("/{sessionId}/close")
    public Result<SessionSnapshotResponse> closeSession(@PathVariable String sessionId) {
        SessionSnapshotResponse response = sessionService.closeSession(sessionId);
        return Result.ok(response);
    }

    @GetMapping("/{sessionId}")
    public Result<SessionSnapshotResponse> getSnapshot(@PathVariable String sessionId) {
        SessionSnapshotResponse response = sessionService.getSnapshot(sessionId);
        return Result.ok(response);
    }
}
