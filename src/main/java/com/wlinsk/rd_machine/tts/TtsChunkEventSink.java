package com.wlinsk.rd_machine.tts;

import com.wlinsk.rd_machine.transport.http.dto.TtsChunkEvent;

interface TtsChunkEventSink {

    void emit(TtsChunkEvent event);

    void complete();
}
