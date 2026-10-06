package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.models.Sticker;

import java.util.Collections;
import java.util.List;

public class WbCrossBorderStickerResponse {
    private List<CrossBorderSticker> stickers;

    public List<CrossBorderSticker> getStickers() {
        return stickers == null ? Collections.emptyList() : stickers;
    }

    public static class CrossBorderSticker extends Sticker {
        private String status;

        public String getStatus() {
            return status;
        }

        public boolean isReady() {
            return "ready".equalsIgnoreCase(status);
        }

        public boolean isAwaitingTrackNumber() {
            return "awaitingTrackNumber".equalsIgnoreCase(status);
        }
    }
}
