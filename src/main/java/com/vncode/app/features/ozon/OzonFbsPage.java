package com.vncode.app.features.ozon;
import com.vncode.app.integration.ozon.*;
import java.util.*;
public record OzonFbsPage(List<OzonPostingDto> postings,long total,Map<String,OzonProductDto> productsBySku,Map<String,String> verifiedGtinsBySku,Map<String,List<com.vncode.app.features.kiz.status.Kn3Mark>> kn3Marks) {
    public OzonFbsPage(List<OzonPostingDto> postings,long total,Map<String,OzonProductDto> productsBySku,Map<String,String> gtins){this(postings,total,productsBySku,gtins,Map.of());}
    public OzonFbsPage(List<OzonPostingDto> postings,long total,Map<String,OzonProductDto> productsBySku){this(postings,total,productsBySku,Map.of());}
    public OzonFbsPage{postings=List.copyOf(postings);productsBySku=Map.copyOf(productsBySku);verifiedGtinsBySku=Map.copyOf(verifiedGtinsBySku);kn3Marks=Map.copyOf(kn3Marks);}
}
