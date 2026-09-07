package com.wlinsk.rd_machine.streaming;

import com.wlinsk.rd_machine.basic.model.bo.TextSegment;
import com.wlinsk.rd_machine.core.streaming.AssistantStreamingOrchestrator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssistantStreamingOrchestratorTtsTextTest {

    @Test
    void convertsEveryZhLlmChunkToSimplifiedChineseImmediately() {
        TextSegment ttsChunk = AssistantStreamingOrchestrator.toTtsChunk("瞭解，今天怎麼樣？", 7, "zh-TW");

        assertEquals(new TextSegment(7, "了解，今天怎么样？"), ttsChunk);
    }

    @Test
    void keepsEveryNonZhLlmChunkUnchanged() {
        TextSegment ttsChunk = AssistantStreamingOrchestrator.toTtsChunk("hello world", 3, "en-US");

        assertEquals(new TextSegment(3, "hello world"), ttsChunk);
    }

    @Test
    void ignoresBlankLlmChunks() {
        assertNull(AssistantStreamingOrchestrator.toTtsChunk("  ", 1, "zh-CN"));
    }
}
