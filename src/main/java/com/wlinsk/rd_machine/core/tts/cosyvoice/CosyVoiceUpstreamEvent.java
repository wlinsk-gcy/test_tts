package com.wlinsk.rd_machine.core.tts.cosyvoice;

public record CosyVoiceUpstreamEvent(
        String taskId,
        String eventType,
        String requestId,
        Object usage,
        Object payload
) {
}
