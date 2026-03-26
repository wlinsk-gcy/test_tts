package com.wlinsk.rd_machine.tts;

public interface TtsAudioListener {

    default void onSessionReady() {
    }

    void onAudioChunk(int segmentSeq, byte[] audioBytes);

    void onCompleted();

    void onError(Throwable throwable);
}
