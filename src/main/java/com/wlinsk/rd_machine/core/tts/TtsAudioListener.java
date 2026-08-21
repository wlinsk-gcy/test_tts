package com.wlinsk.rd_machine.core.tts;

import com.wlinsk.rd_machine.basic.model.bo.TtsUsage;

public interface TtsAudioListener {

    default void onSessionReady() {
    }

    void onAudioChunk(int segmentSeq, byte[] audioBytes);

    default void onUsage(TtsUsage usage) {
    }

    void onCompleted();

    void onError(Throwable throwable);
}
