package com.wlinsk.rd_machine.core.tts;

public interface TtsRealtimeSession extends AutoCloseable {

    TtsUtterance openUtterance(TtsAudioListener audioListener);

    boolean isClosed();

    @Override
    void close();
}
