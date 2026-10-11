package com.vncode.app.features.products;

import java.util.List;
public record ProductPage(List<ProductRow> rows,long total,String nextCursor) {
    public ProductPage {rows=List.copyOf(rows);if(total<0)throw new IllegalArgumentException("Invalid count");}
}
