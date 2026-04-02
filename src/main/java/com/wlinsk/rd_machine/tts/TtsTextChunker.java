package com.wlinsk.rd_machine.tts;

import com.wlinsk.rd_machine.streaming.TextSegment;
import com.wlinsk.rd_machine.streaming.TextSegmenter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.ArrayList;

@Slf4j
public class TtsTextChunker {

    private final TextSegmenter.Settings settings;

    public TtsTextChunker(TextSegmenter.Settings settings) {
        this.settings = settings;
    }

    public List<TextSegment> chunk(String text) {
        String source = text == null ? "" : text.trim();
        if (source.isBlank()) {
            return List.of();
        }

        List<TextSegment> segments = new ArrayList<>();
        String remaining = source;
        int nextSequence = 1;

        while (!remaining.isBlank()) {
            int splitIndex = findSplitIndex(remaining);
            if (splitIndex <= 0 || splitIndex >= remaining.length()) {
                segments.add(new TextSegment(nextSequence++, remaining));
                break;
            }
            String chunk = remaining.substring(0, splitIndex).trim();
            if (!chunk.isBlank()) {
                segments.add(new TextSegment(nextSequence++, chunk));
            }
            remaining = remaining.substring(splitIndex).trim();
        }

        List<String> texts = segments.stream().map(TextSegment::text).toList();
        log.info("raw text: \"{}\", after chunk: \"{}\"", text, texts);
        return segments;
    }

    private int findSplitIndex(String text) {
        int length = text.length();
        int preferredLength = preferredLength();

        if (length <= preferredLength) {
            return -1;
        }

        int balancedSplitIndex = findBalancedSplitIndex(text, preferredLength);
        if (balancedSplitIndex > 0) {
            return balancedSplitIndex;
        }

        if (length <= settings.maxLength()) {
            return -1;
        }

        int boundedSplitIndex = findBoundedSplitIndex(text, settings.maxLength());
        if (boundedSplitIndex > 0) {
            return boundedSplitIndex;
        }
        return settings.maxLength();
    }

    private int preferredLength() {
        return Math.min(settings.maxLength(), Math.max(settings.minLength(), settings.maxLength() / 2));
    }

    private int findBalancedSplitIndex(String text, int preferredLength) {
        int maxCandidate = Math.min(settings.maxLength(), text.length() - settings.minLength());
        if (maxCandidate < settings.minLength()) {
            return -1;
        }

        int softIndex = findClosestPunctuationIndex(text, settings.softPunctuation(), settings.minLength(), maxCandidate, preferredLength);
        if (softIndex > 0) {
            return softIndex;
        }
        return findClosestPunctuationIndex(text, settings.hardPunctuation(), settings.minLength(), maxCandidate, preferredLength);
    }

    private int findBoundedSplitIndex(String text, int maxCandidate) {
        int softIndex = findLastPunctuationIndex(text, settings.softPunctuation(), settings.minLength(), maxCandidate);
        if (softIndex > 0) {
            return softIndex;
        }
        int hardIndex = findLastPunctuationIndex(text, settings.hardPunctuation(), settings.minLength(), maxCandidate);
        if (hardIndex > 0) {
            return hardIndex;
        }
        return -1;
    }

    private int findClosestPunctuationIndex(String text, String punctuation, int minCandidate, int maxCandidate, int preferredLength) {
        if (punctuation == null || punctuation.isBlank()) {
            return -1;
        }
        int bestSplitIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = minCandidate - 1; index < maxCandidate; index++) {
            if (punctuation.indexOf(text.charAt(index)) < 0) {
                continue;
            }
            int splitIndex = index + 1;
            int remainingLength = text.length() - splitIndex;
            if (remainingLength < settings.minLength()) {
                continue;
            }
            int distance = Math.abs(splitIndex - preferredLength);
            if (distance < bestDistance || (distance == bestDistance && splitIndex < bestSplitIndex)) {
                bestDistance = distance;
                bestSplitIndex = splitIndex;
            }
        }
        return bestSplitIndex;
    }

    private int findLastPunctuationIndex(String text, String punctuation, int minCandidate, int maxCandidate) {
        if (punctuation == null || punctuation.isBlank()) {
            return -1;
        }
        for (int index = Math.min(maxCandidate, text.length()) - 1; index >= minCandidate - 1; index--) {
            if (punctuation.indexOf(text.charAt(index)) >= 0) {
                return index + 1;
            }
        }
        return -1;
    }
}
