package com.wlinsk.rd_machine.transport.http.dto;

public record TtsSentenceStreamRequest(
        String sessionId,
        String language,
        String sentence
) {
}
