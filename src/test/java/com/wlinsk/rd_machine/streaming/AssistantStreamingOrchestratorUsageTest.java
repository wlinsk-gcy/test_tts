package com.wlinsk.rd_machine.streaming;

import com.wlinsk.rd_machine.basic.model.bo.TtsUsage;
import com.wlinsk.rd_machine.core.streaming.AssistantStreamingOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantStreamingOrchestratorUsageTest {

    @Test
    void keepsLatestRawUsageFromCurrentTtsTaskInsteadOfAddingSnapshots() {
        AtomicLong characters = new AtomicLong();

        AssistantStreamingOrchestrator.recordTtsUsage(characters, new TtsUsage(15));
        AssistantStreamingOrchestrator.recordTtsUsage(characters, new TtsUsage(7));

        assertEquals(7L, characters.get());
    }

    @Test
    void ignoresMissingTtsUsage() {
        AtomicLong characters = new AtomicLong(9L);

        AssistantStreamingOrchestrator.recordTtsUsage(characters, null);

        assertEquals(9L, characters.get());
    }
}
