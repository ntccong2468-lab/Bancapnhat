package com.tuandev.fbsbarcode.dto;

import com.tuandev.fbsbarcode.models.Sticker;

import java.util.List;

public class StickerResponse {
    private List<Sticker> stickers;

    public StickerResponse() {
    }

    public StickerResponse(List<Sticker> stickers) {
        this.stickers = stickers;
    }

    public List<Sticker> getStickers() {
        return stickers;
    }

    public void setStickers(List<Sticker> stickers) {
        this.stickers = stickers;
    }
}
