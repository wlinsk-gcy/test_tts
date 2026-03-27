package com.wlinsk.rd_machine.transport.http.dto;

import java.util.Map;

public record SubmitTurnRequest(
        Integer turnNo,
        Long clientSeq,
        String text,
        Map<String, Object> asrMeta
) {
}
