package com.vncode.app.features.fbo;

public record FboProductSku(
        long nmId,
        String vendorCode,
        String subjectName,
        String brand,
        String title,
        String color,
        String size,
        String ruSize,
        String sku,
        String imageUrl,
        boolean requiresKiz,
        String catalogSku,
        String variantId,
        String verifiedGtin,
        String productId
) {
    public FboProductSku {
        catalogSku = catalogSku == null || catalogSku.isBlank() ? sku : catalogSku.trim();
        variantId = variantId == null ? "" : variantId;
        verifiedGtin = verifiedGtin == null ? "" : verifiedGtin;
        productId = productId == null || productId.isBlank() ? Long.toString(nmId) : productId;
    }
    public FboProductSku(long nmId,String vendorCode,String subjectName,String brand,String title,String color,String size,String ruSize,String sku,String imageUrl,boolean requiresKiz,String catalogSku,String variantId,String verifiedGtin) {
        this(nmId,vendorCode,subjectName,brand,title,color,size,ruSize,sku,imageUrl,requiresKiz,catalogSku,variantId,verifiedGtin,Long.toString(nmId));
    }
    public FboProductSku(long nmId,String vendorCode,String subjectName,String brand,String title,String color,String size,String ruSize,String sku,String imageUrl,boolean requiresKiz,String catalogSku) {
        this(nmId,vendorCode,subjectName,brand,title,color,size,ruSize,sku,imageUrl,requiresKiz,catalogSku,"","");
    }
    public FboProductSku withVerifiedGtin(String gtin){return new FboProductSku(nmId,vendorCode,subjectName,brand,title,color,size,ruSize,sku,imageUrl,requiresKiz,catalogSku,variantId,gtin,productId);}

    public FboProductSku(
            long nmId,
            String vendorCode,
            String subjectName,
            String brand,
            String title,
            String color,
            String size,
            String ruSize,
            String sku,
            String imageUrl,
            boolean requiresKiz
    ) {
        this(nmId, vendorCode, subjectName, brand, title, color, size, ruSize,
                sku, imageUrl, requiresKiz, sku);
    }
}
