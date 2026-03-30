package com.wlinsk.rd_machine.transport.http.dto;

public record SubmitTurnRequest(
        Long clientSeq,
        String text
) {
}