package com.wlinsk.rd_machine.core.tts.qwenaudio;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.wlinsk.rd_machine.basic.enums.SysCode;
import com.wlinsk.rd_machine.basic.exception.BasicException;

import java.util.Locale;

final class QwenAudioAudioFormatResolver {

    private QwenAudioAudioFormatResolver() {
    }

    static SpeechSynthesisAudioFormat resolve(String format, int sampleRate) {
        String normalizedFormat = format == null || format.isBlank()
                ? "pcm"
                : format.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        String enumName = switch (normalizedFormat) {
            case "pcm" -> "PCM_" + sampleRate + "HZ_MONO_16BIT";
            case "wav" -> "WAV_" + sampleRate + "HZ_MONO_16BIT";
            case "mp3" -> "MP3_" + sampleRate + "HZ_MONO_" + mp3BitRate(sampleRate);
            default -> throw unsupported(format, sampleRate);
        };
        try {
            return SpeechSynthesisAudioFormat.valueOf(enumName);
        } catch (IllegalArgumentException exception) {
            throw unsupported(format, sampleRate);
        }
    }

    private static String mp3BitRate(int sampleRate) {
        return sampleRate == 8_000 || sampleRate == 16_000 ? "128KBPS" : "256KBPS";
    }

    private static BasicException unsupported(String format, int sampleRate) {
        return new BasicException(
                SysCode.PARAMETER_ERROR.getCode(),
                "unsupported Qwen Audio TTS format: " + format + "/" + sampleRate
        );
    }
}
