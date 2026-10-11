package com.vncode.app.features.listingcopy;

import com.vncode.app.features.products.ProductDocument;
import java.math.BigDecimal;
import java.util.*;
public record CopyOptions(Set<String> fields,Set<String> variantIds,String targetCategoryId,Map<String,String> attributeMapping,Map<String,List<String>> attributeValues,Map<String,String> newArticles,BigDecimal targetPrice,String currency,List<ProductDocument> documents,Boolean kizMarked,Map<String,Map<String,List<String>>> variantValues,Map<String,String> textOverrides) {
    public CopyOptions(Set<String> fields,Set<String> variantIds,String targetCategoryId,Map<String,String> attributeMapping,Map<String,List<String>> attributeValues,Map<String,String> newArticles,BigDecimal targetPrice,String currency,List<ProductDocument> documents,Boolean kizMarked){this(fields,variantIds,targetCategoryId,attributeMapping,attributeValues,newArticles,targetPrice,currency,documents,kizMarked,Map.of());}
    public CopyOptions(Set<String> fields,Set<String> variantIds,String targetCategoryId,Map<String,String> attributeMapping,Map<String,List<String>> attributeValues,Map<String,String> newArticles,BigDecimal targetPrice,String currency,List<ProductDocument> documents,Boolean kizMarked,Map<String,Map<String,List<String>>> variantValues){this(fields,variantIds,targetCategoryId,attributeMapping,attributeValues,newArticles,targetPrice,currency,documents,kizMarked,variantValues,Map.of());}
    public CopyOptions {
        fields=Set.copyOf(fields);variantIds=Set.copyOf(variantIds);attributeMapping=Map.copyOf(attributeMapping);newArticles=Map.copyOf(newArticles);documents=List.copyOf(documents);
        var v=new LinkedHashMap<String,List<String>>();attributeValues.forEach((k,l)->v.put(k,List.copyOf(l)));attributeValues=Map.copyOf(v);
        var vv=new LinkedHashMap<String,Map<String,List<String>>>();if(variantValues!=null)variantValues.forEach((key,m)->{var nested=new LinkedHashMap<String,List<String>>();m.forEach((id,list)->nested.put(id,List.copyOf(list)));vv.put(key,Map.copyOf(nested));});variantValues=Map.copyOf(vv);
        textOverrides=textOverrides==null?Map.of():Map.copyOf(textOverrides);
        if(targetPrice!=null&&targetPrice.signum()<=0)throw new IllegalArgumentException("Positive target price required");
        if(currency==null)currency="RUB";
    }
    @Override public String toString(){return "CopyOptions[fields="+fields.size()+", variants="+variantIds.size()+"]";}
}
