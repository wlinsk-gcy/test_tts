package com.wlinsk.rd_machine.streaming;

import com.wlinsk.rd_machine.tts.TtsStreamSession;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class ActiveAssistantTurnHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Future<?> future;
    private volatile TtsStreamSession ttsStreamSession;

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void attachFuture(Future<?> future) {
        this.future = future;
        if (isCancelled() && future != null) {
            future.cancel(true);
        }
    }

    public void attachTtsStreamSession(TtsStreamSession ttsStreamSession) {
        this.ttsStreamSession = ttsStreamSession;
        if (isCancelled() && ttsStreamSession != null) {
            closeQuietly(ttsStreamSession);
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
        TtsStreamSession runningTtsStreamSession = ttsStreamSession;
        if (runningTtsStreamSession != null) {
            closeQuietly(runningTtsStreamSession);
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
