package com.wlinsk.rd_machine.transport.ws;

import java.util.Map;

public record AssistantEvent(
        String type,
        String sessionId,
        int turnNo,
        int roundNo,
        Map<String, Object> data
) {
}
