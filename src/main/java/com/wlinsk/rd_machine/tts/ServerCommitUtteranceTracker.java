package com.wlinsk.rd_machine.tts;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

final class ServerCommitUtteranceTracker {

    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final Deque<Integer> pendingSegmentSeqs = new ArrayDeque<>();
    private int activeResponses;
    private int lastAssignedSegmentSeq;
    private boolean inputCommitted;
    private boolean anyInputAppended;
    private boolean audioCompleted;

    synchronized void markSegmentAppended(int segmentSeq) {
        pendingSegmentSeqs.addLast(segmentSeq);
        anyInputAppended = true;
    }

    synchronized int markResponseCreated() {
        activeResponses += 1;
        Integer assignedSegmentSeq = pendingSegmentSeqs.pollFirst();
        pendingSegmentSeqs.clear();
        if (assignedSegmentSeq != null) {
            lastAssignedSegmentSeq = assignedSegmentSeq;
        }
        return lastAssignedSegmentSeq;
    }

    synchronized void markResponseDone() {
        if (activeResponses > 0) {
            activeResponses -= 1;
        }
    }

    synchronized void markInputCommitted() {
        inputCommitted = true;
        completeIfFinished();
    }

    synchronized void markAudioDone() {
        audioCompleted = true;
        completeIfFinished();
    }

    synchronized boolean hasAnyInputAppended() {
        return anyInputAppended;
    }

    CompletableFuture<Void> completion() {
        return completion;
    }

    void fail(Throwable throwable) {
        completion.completeExceptionally(throwable);
    }

    private void completeIfFinished() {
        if (inputCommitted && (!anyInputAppended || audioCompleted)) {
            completion.complete(null);
        }
    }
}
