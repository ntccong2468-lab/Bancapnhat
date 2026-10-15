package com.vncode.app.features.fbo;

public record FboPrintPage(FboProductSku product, String kizCode, int pairNumber, Kind kind, boolean categoryStock) {
    public FboPrintPage(FboProductSku product,String kizCode,int pairNumber,Kind kind){this(product,kizCode,pairNumber,kind,false);}
    public static FboPrintPage barcode(FboProductSku product,int pairNumber,boolean categoryStock){return new FboPrintPage(product,null,pairNumber,Kind.BARCODE,categoryStock);}
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
