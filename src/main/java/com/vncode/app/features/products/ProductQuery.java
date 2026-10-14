package com.vncode.app.features.products;

import com.vncode.app.integration.marketplace.Marketplace;
import java.util.*;
public record ProductQuery(int shopId, Marketplace marketplace, String text, List<String> categoryIds, DocumentState documentState, boolean missingGtin, String cursor, int limit,ProductIssueFilter issue) {
    public ProductQuery(int shopId,Marketplace marketplace,String text,List<String> categoryIds,DocumentState documentState,boolean missingGtin,String cursor,int limit) {
        this(shopId,marketplace,text,categoryIds,documentState,missingGtin,cursor,limit,ProductIssueFilter.ALL);
    }
    public ProductQuery {
        if(shopId<=0||limit<1||limit>1000)throw new IllegalArgumentException("Invalid product page");
        Objects.requireNonNull(marketplace);text=text==null?"":text.strip();if(text.length()>512)throw new IllegalArgumentException("Search too long");
        Objects.requireNonNull(issue);
        categoryIds=categoryIds==null?List.of():List.copyOf(categoryIds);if(categoryIds.size()>100)throw new IllegalArgumentException("Too many categories");
        if(cursor!=null&&cursor.length()>8192)throw new IllegalArgumentException("Invalid cursor");
    }
}
