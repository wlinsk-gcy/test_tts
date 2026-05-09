package com.wlinsk.rd_machine.core.tts.cosyvoice;

import java.util.Locale;

public record CosyVoiceConnectionKey(String languageType, boolean ssml) {

    public static CosyVoiceConnectionKey from(String language, boolean ssml) {
        return new CosyVoiceConnectionKey(isEnglish(language) ? "en" : "zh", ssml);
    }

    public static boolean isEnglish(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
    }
}
