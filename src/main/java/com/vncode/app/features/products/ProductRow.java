package com.vncode.app.features.products;

import java.util.*;
public record ProductRow(ProductKey key,String title,String vendorArticle,String marketArticle,String category,String brand,String color,String size,List<String> barcodes,String verifiedGtin,String thumbnailUrl,String material,String tnved,DocumentState documentState) {
    public ProductRow { Objects.requireNonNull(key);barcodes=List.copyOf(barcodes);Objects.requireNonNull(documentState); }
}
