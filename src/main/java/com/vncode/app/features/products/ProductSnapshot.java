package com.vncode.app.features.products;

import java.time.Instant;
import java.util.*;
public record ProductSnapshot(ProductKey key,String revision,String title,String description,String brand,String vendorArticle,String categoryId,List<ProductMedia> media,List<ProductAttribute> attributes,List<ProductVariant> variants,PackageDimensions dimensions,List<ProductDocument> documents,Instant checkedAt) {
    public ProductSnapshot {
        Objects.requireNonNull(key);Objects.requireNonNull(revision);Objects.requireNonNull(checkedAt);
        media=List.copyOf(media);attributes=List.copyOf(attributes);variants=List.copyOf(variants);documents=List.copyOf(documents);
        if(documents.stream().anyMatch(d->d.marketplace()!=key.marketplace()))throw new IllegalArgumentException("Foreign marketplace documents");
    }
    @Override public String toString(){return "ProductSnapshot[key="+key+", revision="+revision+"]";}
}
