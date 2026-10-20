package com.vncode.app.features.products;

import java.util.*;
public record ProductRow(ProductKey key,String title,String vendorArticle,String marketArticle,String category,String brand,String color,String size,List<String> barcodes,String verifiedGtin,String thumbnailUrl,String material,String tnved,DocumentState documentState,String gender,boolean attributesLoaded,String russianSize,String manufacturerSize) {
    public ProductRow(ProductKey key,String title,String vendorArticle,String marketArticle,String category,String brand,String color,String size,List<String> barcodes,String verifiedGtin,String thumbnailUrl,String material,String tnved,DocumentState documentState,String gender,boolean attributesLoaded,String russianSize) {
        this(key,title,vendorArticle,marketArticle,category,brand,color,size,barcodes,verifiedGtin,thumbnailUrl,material,tnved,documentState,gender,attributesLoaded,russianSize,"");
    }
    public ProductRow(ProductKey key,String title,String vendorArticle,String marketArticle,String category,String brand,String color,String size,List<String> barcodes,String verifiedGtin,String thumbnailUrl,String material,String tnved,DocumentState documentState,String gender,boolean attributesLoaded) {
        this(key,title,vendorArticle,marketArticle,category,brand,color,size,barcodes,verifiedGtin,thumbnailUrl,material,tnved,documentState,gender,attributesLoaded,"");
    }
    public ProductRow(ProductKey key,String title,String vendorArticle,String marketArticle,String category,String brand,String color,String size,List<String> barcodes,String verifiedGtin,String thumbnailUrl,String material,String tnved,DocumentState documentState) {
        this(key,title,vendorArticle,marketArticle,category,brand,color,size,barcodes,verifiedGtin,thumbnailUrl,material,tnved,documentState,"",true);
    }
    public ProductRow { Objects.requireNonNull(key);barcodes=List.copyOf(barcodes);Objects.requireNonNull(documentState);russianSize=russianSize==null?"":russianSize;manufacturerSize=manufacturerSize==null?"":manufacturerSize; }
}
