package com.wlinsk.rd_machine.tts;

public interface TtsRealtimeSession extends AutoCloseable {

    TtsUtterance openUtterance(TtsAudioListener audioListener);

    boolean isClosed();

    @Override
    void close();
}
