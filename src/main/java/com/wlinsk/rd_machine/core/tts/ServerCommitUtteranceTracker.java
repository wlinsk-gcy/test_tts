package com.wlinsk.rd_machine.core.tts;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

public final class ServerCommitUtteranceTracker {

    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final Deque<Integer> pendingSegmentSeqs = new ArrayDeque<>();
    private int activeResponses;
    private int lastAssignedSegmentSeq;
    private boolean inputCommitted;
    private boolean anyInputAppended;
    private boolean sawResponseCreated;

    public synchronized void markSegmentAppended(int segmentSeq) {
        pendingSegmentSeqs.addLast(segmentSeq);
        anyInputAppended = true;
    }

    public synchronized int markResponseCreated() {
        activeResponses += 1;
        sawResponseCreated = true;
        Integer assignedSegmentSeq = pendingSegmentSeqs.pollFirst();
        pendingSegmentSeqs.clear();
        if (assignedSegmentSeq != null) {
            lastAssignedSegmentSeq = assignedSegmentSeq;
        }
        return lastAssignedSegmentSeq;
    }

    public synchronized void markResponseDone() {
        if (activeResponses > 0) {
            activeResponses -= 1;
        }
        completeIfFinished();
    }

    public synchronized void markInputCommitted() {
        inputCommitted = true;
        completeIfFinished();
    }

    public synchronized void markAudioDone() {
        completeIfFinished();
    }

    public synchronized boolean hasAnyInputAppended() {
        return anyInputAppended;
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public void fail(Throwable throwable) {
        completion.completeExceptionally(throwable);
    }

    private void completeIfFinished() {
        // Complete only once every created response has reported response.done. response.done carries the
        // billing usage and always follows response.audio.done, so gating on it (rather than audio.done)
        // guarantees the final segment's usage is captured before the utterance is torn down.
        if (inputCommitted && (!anyInputAppended || (sawResponseCreated && activeResponses == 0))) {
            completion.complete(null);
        }
    }
}
