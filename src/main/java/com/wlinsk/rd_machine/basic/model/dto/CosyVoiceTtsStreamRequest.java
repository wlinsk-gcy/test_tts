package com.wlinsk.rd_machine.basic.model.dto;

public record CosyVoiceTtsStreamRequest(
        String sessionId,
        String language,
        String sentence,
        Boolean ssml
) {
}
