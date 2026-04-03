package com.wlinsk.rd_machine.core.tts;

import com.wlinsk.rd_machine.basic.model.bo.TextSegment;
import com.wlinsk.rd_machine.utils.zh_convert.CustomZhConvertUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public final class TtsTextNormalizer {

    private TtsTextNormalizer() {
    }

    public static TextSegment normalize(TextSegment segment, String language) {
        if (segment == null) {
            return null;
        }
        if (language == null || !language.startsWith("zh")) {
            return segment;
        }
        long start = System.nanoTime();
        String simple = CustomZhConvertUtil.toSimple(segment.text());
        log.info("raw text: {}, after convert: {}, convert time: {}ns", segment.text(), simple, (System.nanoTime() - start));
        return new TextSegment(segment.segmentSeq(), simple);
    }

    public static List<TextSegment> normalize(List<TextSegment> segments, String language) {
        return segments.stream()
                .map(segment -> normalize(segment, language))
                .toList();
    }
}
