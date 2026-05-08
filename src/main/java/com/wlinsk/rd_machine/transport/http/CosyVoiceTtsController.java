package com.wlinsk.rd_machine.transport.http;

import com.wlinsk.rd_machine.basic.model.dto.CosyVoiceTtsStreamRequest;
import com.wlinsk.rd_machine.core.tts.cosyvoice.CosyVoiceTtsStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/cosyvoice/tts")
@RequiredArgsConstructor
public class CosyVoiceTtsController {

    private final CosyVoiceTtsStreamService streamService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody CosyVoiceTtsStreamRequest request) {
        return streamService.stream(request);
    }
}
