package com.wlinsk.rd_machine.core.tts;

import com.wlinsk.rd_machine.basic.enums.SysCode;
import com.wlinsk.rd_machine.basic.exception.BasicException;
import com.wlinsk.rd_machine.basic.model.bo.TextSegment;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// 任务层的 Utterance，一次 StreamTurn（一轮对话）的合成任务，每轮会新建一个，用完即弃
public final class QueuedTtsUtterance implements TtsUtterance {

    static final TextSegment FINISH_SENTINEL = new TextSegment(-1, "");

    private final TtsAudioListener audioListener;
    private final BlockingQueue<TextSegment> queue;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final ServerCommitUtteranceTracker utteranceTracker = new ServerCommitUtteranceTracker();
    private final AtomicBoolean sessionClosed;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();

    public QueuedTtsUtterance(
            TtsAudioListener audioListener,
            BlockingQueue<TextSegment> queue,
            AtomicBoolean sessionClosed,
            Consumer<QueuedTtsUtterance> startDrainAction
    ) {
        this.audioListener = audioListener;
        this.queue = queue;
        this.sessionClosed = sessionClosed;
        startDrainAction.accept(this);
    }

    @Override
    public void enqueue(TextSegment textSegment) {
        if (sessionClosed.get() || finished.get() || textSegment == null || textSegment.text().isBlank()) {
            return;
        }
        offerOrThrow(textSegment, "Interrupted while enqueueing TTS segment");
    }

    @Override
    public void finish() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        offerOrThrow(FINISH_SENTINEL, "Interrupted while finishing TTS utterance");
    }

    @Override
    public void awaitFinished(Duration timeout) {
        completion.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    public void fail(Throwable throwable) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        finished.set(true);
        audioListener.onError(throwable);
        completion.completeExceptionally(throwable);
        utteranceTracker.fail(throwable);
        queue.offer(FINISH_SENTINEL);
    }

    void completeSuccessfully() {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        audioListener.onCompleted();
        completion.complete(null);
    }

    TtsAudioListener audioListener() {
        return audioListener;
    }

    BlockingQueue<TextSegment> queue() {
        return queue;
    }

    boolean isTerminal() {
        return terminal.get();
    }

    void markSegmentAppended(int segmentSeq) {
        utteranceTracker.markSegmentAppended(segmentSeq);
    }

    int markResponseCreated() {
        return utteranceTracker.markResponseCreated();
    }

    void markResponseDone() {
        utteranceTracker.markResponseDone();
    }

    void markAudioDone() {
        utteranceTracker.markAudioDone();
    }

    boolean hasAnyInputAppended() {
        return utteranceTracker.hasAnyInputAppended();
    }

    void markInputCommitted() {
        utteranceTracker.markInputCommitted();
    }

    void awaitResponseCompletion(Duration timeout) {
        utteranceTracker.completion().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    private void offerOrThrow(TextSegment textSegment, String interruptionMessage) {
        try {
            if (!queue.offer(textSegment, 250L, TimeUnit.MILLISECONDS)) {
                throw segmentQueueFullException();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException(interruptionMessage);
        }
    }

    private BasicException segmentQueueFullException() {
        return new BasicException(SysCode.TTS_SEGMENT_QUEUE_FULL);
    }
}
