package com.tuandev.fbsbarcode.features.print;

import java.util.Arrays;

public final class StickerText {
    private StickerText() {
    }

    public static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static String firstPart(String sticker) {
        String[] parts = parts(sticker);
        return parts.length == 0 ? "" : parts[0];
    }

    public static String secondPartOrFirst(String sticker) {
        String[] parts = parts(sticker);
        if (parts.length == 0) {
            return "";
        }
        return parts.length > 1 ? parts[1] : parts[0];
    }

    public static String[] parts(String sticker) {
        String safeSticker = safe(sticker);
        if (safeSticker.isEmpty()) {
            return new String[0];
        }

        return Arrays.stream(safeSticker.split("\\s+"))
                .filter(part -> !part.isBlank())
                .toArray(String[]::new);
    }
}
