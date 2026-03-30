package com.wlinsk.rd_machine.transport.http.dto;

public record CreateSessionRequest(
        String title,
        String author,
        String language,
        String content
) {
}
