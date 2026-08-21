package com.wlinsk.rd_machine.core.llm;

import com.wlinsk.rd_machine.basic.model.bo.LlmUsage;

public interface LlmDeltaListener {

    void onDelta(String delta);

    void onComplete(LlmUsage usage);

    void onError(Throwable throwable);
}
