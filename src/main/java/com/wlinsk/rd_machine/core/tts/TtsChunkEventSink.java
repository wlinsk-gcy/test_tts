package com.wlinsk.rd_machine.core.tts;

import com.wlinsk.rd_machine.basic.model.dto.TtsChunkEvent;

public interface TtsChunkEventSink {

    void emit(TtsChunkEvent event);

    void complete();
}
