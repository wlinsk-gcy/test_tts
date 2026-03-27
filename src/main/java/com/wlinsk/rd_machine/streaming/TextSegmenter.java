package com.wlinsk.rd_machine.streaming;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

public class TextSegmenter {

    private final Clock clock;
    private final Settings settings;
    private final StringBuilder buffer = new StringBuilder();
    private int nextSequence = 1;
    private long lastFlushAt;

    public TextSegmenter() {
        this(Clock.systemUTC(), Settings.legacy());
    }

    public TextSegmenter(Settings settings) {
        this(Clock.systemUTC(), settings);
    }

    TextSegmenter(Clock clock) {
        this(clock, Settings.legacy());
    }

    TextSegmenter(Clock clock, Settings settings) {
        this.clock = clock;
        this.settings = settings;
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
        if (buffer.length() >= settings.maxLength()
                || (buffer.length() >= settings.minLength() && clock.millis() - lastFlushAt >= settings.maxWaitMillis())) {
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
        int hardSplitIndex = findSplitIndex(settings.hardPunctuation());
        if (hardSplitIndex > 0) {
            return hardSplitIndex;
        }
        return findSplitIndex(settings.softPunctuation());
    }

    private int findSplitIndex(String punctuation) {
        if (buffer.length() < settings.minLength() || punctuation == null || punctuation.isBlank()) {
            return -1;
        }
        for (int index = buffer.length() - 1; index >= 0; index--) {
            if (punctuation.indexOf(buffer.charAt(index)) >= 0 && index + 1 >= settings.minLength()) {
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

    public record Settings(
            int minLength,
            int maxLength,
            long maxWaitMillis,
            String softPunctuation,
            String hardPunctuation
    ) {

        public Settings {
            if (minLength < 1) {
                throw new IllegalArgumentException("minLength must be positive");
            }
            if (maxLength < minLength) {
                throw new IllegalArgumentException("maxLength must be >= minLength");
            }
            if (maxWaitMillis < 0) {
                throw new IllegalArgumentException("maxWaitMillis must be >= 0");
            }
        }

        public static Settings legacy() {
            return new Settings(
                    12,
                    28,
                    250L,
                    ",\uFF0C;\uFF1B",
                    ".!?\u3002\uFF01\uFF1F"
            );
        }
    }
}
