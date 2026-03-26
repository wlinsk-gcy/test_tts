package com.wlinsk.rd_machine.streaming;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

public class TextSegmenter {

    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 28;
    private static final long MAX_WAIT_MILLIS = 250L;
    private static final String PUNCTUATION = "。！？!?；;，,";

    private final Clock clock;
    private final StringBuilder buffer = new StringBuilder();
    private int nextSequence = 1;
    private long lastFlushAt;

    public TextSegmenter() {
        this(Clock.systemUTC());
    }

    TextSegmenter(Clock clock) {
        this.clock = clock;
        this.lastFlushAt = clock.millis();
    }

    public List<TextSegment> append(String delta) {
        List<TextSegment> segments = new ArrayList<>();
        if (delta == null || delta.isBlank()) {
            return segments;
        }
        buffer.append(delta);
        while (true) {
            int splitIndex = findSplitIndex();
            if (splitIndex <= 0) {
                break;
            }
            segments.add(flush(splitIndex));
        }
        if (buffer.length() >= MAX_LENGTH || (buffer.length() >= MIN_LENGTH && clock.millis() - lastFlushAt >= MAX_WAIT_MILLIS)) {
            segments.add(flush(buffer.length()));
        }
        return segments;
    }

    public TextSegment flushRemaining() {
        if (buffer.isEmpty()) {
            return null;
        }
        return flush(buffer.length());
    }

    private int findSplitIndex() {
        if (buffer.length() < MIN_LENGTH) {
            return -1;
        }
        for (int index = buffer.length() - 1; index >= 0; index--) {
            if (PUNCTUATION.indexOf(buffer.charAt(index)) >= 0) {
                return index + 1;
            }
        }
        return -1;
    }

    private TextSegment flush(int length) {
        String text = buffer.substring(0, length).trim();
        buffer.delete(0, length);
        lastFlushAt = clock.millis();
        return new TextSegment(nextSequence++, text);
    }
}
