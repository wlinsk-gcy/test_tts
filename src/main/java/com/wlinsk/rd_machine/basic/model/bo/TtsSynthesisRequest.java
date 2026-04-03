package com.wlinsk.rd_machine.basic.model.bo;

public record TtsSynthesisRequest(
        String voice,
        String mode,
        String responseFormat,
        int sampleRate,
        String languageType
) {
}
