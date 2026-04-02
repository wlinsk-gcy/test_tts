package com.wlinsk.rd_machine.transport.http;

import com.wlinsk.rd_machine.model.Result;
import com.wlinsk.rd_machine.transport.http.dto.TtsCloseSessionRequest;
import com.wlinsk.rd_machine.transport.http.dto.TtsSentenceStreamRequest;
import com.wlinsk.rd_machine.tts.ReadingTtsStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tts/sessions")
public class TtsSessionController {

    private final ReadingTtsStreamService readingTtsStreamService;

    public TtsSessionController(ReadingTtsStreamService readingTtsStreamService) {
        this.readingTtsStreamService = readingTtsStreamService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSentence(@RequestBody TtsSentenceStreamRequest request) {
        return readingTtsStreamService.streamSentence(request);
    }

    @PostMapping("/close")
    public Result<Void> closeSession(@RequestBody TtsCloseSessionRequest request) {
        readingTtsStreamService.closeSession(request.sessionId());
        return Result.ok();
    }
}
