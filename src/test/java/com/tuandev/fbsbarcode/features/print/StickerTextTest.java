package com.tuandev.fbsbarcode.features.print;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StickerTextTest {
    @Test
    void splitsStickerByWhitespace() {
        assertArrayEquals(new String[]{"AAA", "1234"}, StickerText.parts("  AAA   1234 "));
    }

    @Test
    void fallsBackToFirstPartWhenSecondMissing() {
        assertEquals("9876", StickerText.secondPartOrFirst("9876"));
        assertEquals("", StickerText.firstPart(null));
    }
}
