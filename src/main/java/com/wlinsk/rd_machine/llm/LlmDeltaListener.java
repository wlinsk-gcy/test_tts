package com.wlinsk.rd_machine.llm;

public interface LlmDeltaListener {

    void onDelta(String delta);

    void onComplete();

    void onError(Throwable throwable);
}
