package com.tuandev.fbsbarcode.shared;

import java.util.Locale;

public enum AppLanguage {
    RU("ru", "Русский"),
    EN("en", "English"),
    ZH("zh", "中文"),
    VI("vi", "Tiếng Việt");

    private final String code;
    private final String displayName;

    AppLanguage(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(code);
    }

    public static AppLanguage fromCode(String code) {
        if (code == null || code.isBlank()) {
            return RU;
        }
        for (AppLanguage language : values()) {
            if (language.code.equalsIgnoreCase(code)) {
                return language;
            }
        }
        return RU;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
