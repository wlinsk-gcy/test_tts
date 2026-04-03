package com.wlinsk.rd_machine.basic.model.dto;

public record TtsSentenceStreamRequest(
        String sessionId,
        String language,
        String sentence
) {
}
