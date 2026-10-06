package com.tuandev.fbsbarcode.features.fbo;

public record FboPrintPage(FboProductSku product, String kizCode, int pairNumber, Kind kind) {
    public enum Kind {
        BARCODE,
        KIZ
    }

    public static FboPrintPage barcode(FboProductSku product, int pairNumber) {
        return new FboPrintPage(product, null, pairNumber, Kind.BARCODE);
    }

    public static FboPrintPage kiz(FboProductSku product, String kizCode, int pairNumber) {
        return new FboPrintPage(product, kizCode, pairNumber, Kind.KIZ);
    }
}
