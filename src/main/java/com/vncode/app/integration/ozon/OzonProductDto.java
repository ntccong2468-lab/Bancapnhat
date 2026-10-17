package com.vncode.app.integration.ozon;

import java.util.List;

public record OzonProductDto(
        String productId,
        String offerId,
        String sku,
        String name,
        String primaryImageUrl,
        String article,
        String color,
        String size,
        String category,
        String gender,
        boolean archived,
        String updatedAt,
        List<String> barcodes, String descriptionCategoryId, String typeId,String manufacturerSize,String russianSize) {
    public OzonProductDto {
        productId = OzonApiClient.requireExternalId(productId, "product id");
        offerId = safe(offerId);
        sku = safe(sku);
        name = bounded(name, 1000);
        primaryImageUrl = bounded(primaryImageUrl, 4096);
        article = safe(article);
        color = safe(color);
        size = safe(size);
        category = safe(category);
        descriptionCategoryId = safe(descriptionCategoryId);typeId = safe(typeId);
        manufacturerSize=safe(manufacturerSize);russianSize=safe(russianSize);
        gender = safe(gender);
        updatedAt = bounded(updatedAt, 80);
        barcodes = barcodes == null ? List.of() : barcodes.stream()
                .map(OzonProductDto::safe)
                .filter(value -> !value.isEmpty())
                .distinct()
                .limit(100)
                .toList();
    }
    public OzonProductDto(String productId,String offerId,String sku,String name,String image,String article,String color,String size,String category,String gender,boolean archived,String updatedAt,List<String> barcodes,String descriptionCategoryId,String typeId){
        this(productId,offerId,sku,name,image,article,color,size,category,gender,archived,updatedAt,barcodes,descriptionCategoryId,typeId,"","");
    }

    public OzonProductDto(String productId,String offerId,String sku,String name,String image,String article,String color,String size,String category,String gender,boolean archived,String updatedAt,List<String> barcodes){
        this(productId,offerId,sku,name,image,article,color,size,category,gender,archived,updatedAt,barcodes,"","");
    }
    public OzonProductDto withCategoryIdentity(String categoryId,String nativeTypeId){
        return new OzonProductDto(productId,offerId,sku,name,primaryImageUrl,article,color,size,category,gender,archived,updatedAt,barcodes,categoryId,nativeTypeId,manufacturerSize,russianSize);
    }

    public OzonProductDto(
            String productId,
            String offerId,
            String sku,
            String name,
            String primaryImageUrl,
            boolean archived,
            String updatedAt,
            List<String> barcodes) {
        this(productId, offerId, sku, name, primaryImageUrl, offerId, "", "", "", "",
                archived, updatedAt, barcodes);
    }

    /** Compatibility constructor for callers that do not yet provide category filters. */
    public OzonProductDto(
            String productId,
            String offerId,
            String sku,
            String name,
            String primaryImageUrl,
            String article,
            String color,
            String size,
            boolean archived,
            String updatedAt,
            List<String> barcodes) {
        this(productId, offerId, sku, name, primaryImageUrl, article, color, size, "", "",
                archived, updatedAt, barcodes);
    }

    public OzonProductDto withCardAttributes(
            String article, String color, String size, String category, String gender) {
        return withCardAttributes(article,color,size,category,gender,manufacturerSize,russianSize);
    }
    public OzonProductDto withCardAttributes(String article,String color,String size,String category,String gender,String manufacturerSize,String russianSize){
        return new OzonProductDto(
                productId, offerId, sku, name, primaryImageUrl,
                article == null || article.isBlank() ? offerId : article,
                color, size, category, gender, archived, updatedAt, barcodes, descriptionCategoryId, typeId,manufacturerSize,russianSize);
    }

    private static String safe(String value) {
        return bounded(value, 512);
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\p{Cntrl}", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
