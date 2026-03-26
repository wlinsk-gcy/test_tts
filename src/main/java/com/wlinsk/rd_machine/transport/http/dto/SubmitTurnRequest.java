package com.wlinsk.rd_machine.transport.http.dto;

import java.util.Map;

public record SubmitTurnRequest(
        Integer turnNo,
        Integer clientSeq,
        String text,
        Map<String, Object> asrMeta
) {
}
