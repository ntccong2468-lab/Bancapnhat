package com.vncode.app.features.ozon;
import com.vncode.app.integration.ozon.*;
import java.util.*;
public record OzonFbsPage(List<OzonPostingDto> postings,long total,Map<String,OzonProductDto> productsBySku,Map<String,String> verifiedGtinsBySku) {
    public OzonFbsPage(List<OzonPostingDto> postings,long total,Map<String,OzonProductDto> productsBySku){this(postings,total,productsBySku,Map.of());}
    public OzonFbsPage{postings=List.copyOf(postings);productsBySku=Map.copyOf(productsBySku);verifiedGtinsBySku=Map.copyOf(verifiedGtinsBySku);}
}
