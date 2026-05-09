package com.wlinsk.rd_machine.core.tts.cosyvoice;

public record CosyVoiceTaskRequest(
        String sessionId,
        String taskId,
        String language,
        String voice,
        String sentence,
        boolean ssml,
        long startedAtNs
) {
}
