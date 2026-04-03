package com.wlinsk.rd_machine.basic.model.dto;

public record CreateSessionRequest(
        String title,
        String author,
        String language,
        String content
) {
}
