package com.vncode.app.features.products;

public record ProductVariant(String id,String sellerArticle,String color,String size,String gtin,boolean gtinVerified,String russianSize) {
    public ProductVariant(String id,String sellerArticle,String color,String size,String gtin,boolean gtinVerified){this(id,sellerArticle,color,size,gtin,gtinVerified,null);}
}
