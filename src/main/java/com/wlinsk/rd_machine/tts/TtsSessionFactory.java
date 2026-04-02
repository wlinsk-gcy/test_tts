package com.wlinsk.rd_machine.tts;

@FunctionalInterface
public interface TtsSessionFactory {

    TtsRealtimeSession openSession(String language);
}
