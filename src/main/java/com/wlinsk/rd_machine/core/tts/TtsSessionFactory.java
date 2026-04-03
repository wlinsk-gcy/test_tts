package com.wlinsk.rd_machine.core.tts;

@FunctionalInterface
public interface TtsSessionFactory {

    TtsRealtimeSession openSession(String language);
}
