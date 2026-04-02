package com.wlinsk.rd_machine.streaming;

import com.wlinsk.rd_machine.tts.TtsRealtimeSession;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class ActiveAssistantTurnHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Future<?> future;
    private volatile TtsRealtimeSession ttsRealtimeSession;

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void attachFuture(Future<?> future) {
        this.future = future;
        if (isCancelled() && future != null) {
            future.cancel(true);
        }
    }

    public void attachTtsRealtimeSession(TtsRealtimeSession ttsRealtimeSession) {
        this.ttsRealtimeSession = ttsRealtimeSession;
        if (isCancelled() && ttsRealtimeSession != null) {
            closeQuietly(ttsRealtimeSession);
        }
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Future<?> runningFuture = future;
        if (runningFuture != null) {
            runningFuture.cancel(true);
        }
        TtsRealtimeSession runningTtsRealtimeSession = ttsRealtimeSession;
        if (runningTtsRealtimeSession != null) {
            closeQuietly(runningTtsRealtimeSession);
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
