package com.vncode.app.features.products;

import java.util.List;
public record ProductAttribute(String id,String name,List<String> values,String unit) {
    public ProductAttribute {values=List.copyOf(values);}
}
