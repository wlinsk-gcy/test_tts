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
    private boolean audioCompleted;

    public synchronized void markSegmentAppended(int segmentSeq) {
        pendingSegmentSeqs.addLast(segmentSeq);
        anyInputAppended = true;
    }

    public synchronized int markResponseCreated() {
        activeResponses += 1;
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
    }

    public synchronized void markInputCommitted() {
        inputCommitted = true;
        completeIfFinished();
    }

    public synchronized void markAudioDone() {
        audioCompleted = true;
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
        if (inputCommitted && (!anyInputAppended || audioCompleted)) {
            completion.complete(null);
        }
    }
}
