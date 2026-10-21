package com.vncode.app.features.listingcopy;

import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.listing.ListingIdentity;
import java.util.*;
public record CopyPreview(int shopId,Marketplace marketplace,String owner,ProductKey sourceKey,String sourceRevision,String requestHash,String requestJson,String mediaJson,String documentsJson,Map<String,String> diff,List<CopyIssue> issues,List<String> articles,CopyOptions options,String operation) {
    public CopyPreview{diff=Map.copyOf(diff);issues=List.copyOf(issues);articles=List.copyOf(articles);}
    public boolean publishable(){return sourceRevision.startsWith("live:")&&issues.stream().noneMatch(CopyIssue::blocking)&&validHash();}
    public boolean validHash(){return requestHash.equals(hash(requestJson,mediaJson,documentsJson,owner,sourceRevision,operation));}
    public static String hash(String r,String m,String d,String owner,String revision,String operation){return ListingIdentity.hash(operation+"\n"+owner+"\n"+revision+"\n"+r+"\n"+m+"\n"+d);}
    @Override public String toString(){return "CopyPreview[shop="+shopId+", publishable="+publishable()+", issues="+issues.size()+"]";}
}
