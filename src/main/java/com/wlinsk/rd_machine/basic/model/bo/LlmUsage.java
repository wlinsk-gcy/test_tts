package com.wlinsk.rd_machine.basic.model.bo;

public record LlmUsage(long promptTokens, long completionTokens, long totalTokens, long cachedTokens) {
}
