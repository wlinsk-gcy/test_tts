package com.wlinsk.rd_machine.tts;

public record TtsSynthesisRequest(
        String voice,
        String mode,
        String responseFormat,
        int sampleRate,
        String languageType
) {

    public boolean usesClientCommit() {
        return "commit".equalsIgnoreCase(mode);
    }
}
