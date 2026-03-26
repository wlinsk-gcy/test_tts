package com.wlinsk.rd_machine.tts;

import com.wlinsk.rd_machine.streaming.TextSegment;
import java.time.Duration;

public interface TtsStreamSession extends AutoCloseable {

    void enqueue(TextSegment textSegment);

    void finish();

    void awaitFinished(Duration timeout);

    @Override
    void close();
}
