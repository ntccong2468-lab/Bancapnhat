package com.vncode.app.shared;

/** Edge whitespace is accidental; spaces and separators inside a model code are meaningful. */
public final class SellerArticle {
    private SellerArticle() {}
    public static String trim(String value) {
        if (value == null) return "";
        int start = 0, end = value.length();
        while (start < end && space(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && space(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
    private static boolean space(int code) { return Character.isWhitespace(code) || Character.isSpaceChar(code); }
}
