package com.vncode.app.features.products;

import java.util.List;
import java.util.Map;
public record ProductPage(List<ProductRow> rows,long total,String nextCursor,Map<ProductIssueFilter,Long> issueCounts) {
    public ProductPage(List<ProductRow> rows,long total,String nextCursor){this(rows,total,nextCursor,Map.of());}
    public ProductPage {rows=List.copyOf(rows);issueCounts=Map.copyOf(issueCounts);if(total<0)throw new IllegalArgumentException("Invalid count");}
}
