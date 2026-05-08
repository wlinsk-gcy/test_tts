package com.wlinsk.rd_machine.core.tts.cosyvoice;

public interface CosyVoiceTaskListener {

    void onUpstreamEvent(CosyVoiceUpstreamEvent event);

    void onAudioChunk(int chunkSeq, byte[] audioBytes);

    void onCompleted();

    void onFailed(Throwable throwable);
}
