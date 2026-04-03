package com.wlinsk.rd_machine.core.tts;

import com.wlinsk.rd_machine.basic.model.bo.TextSegment;

import java.time.Duration;

public interface TtsUtterance {

    void enqueue(TextSegment textSegment);

    void finish();

    void awaitFinished(Duration timeout);
}
