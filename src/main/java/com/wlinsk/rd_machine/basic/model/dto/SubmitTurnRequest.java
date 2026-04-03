package com.wlinsk.rd_machine.basic.model.dto;

public record SubmitTurnRequest(
        Long clientSeq,
        String text
) {
}