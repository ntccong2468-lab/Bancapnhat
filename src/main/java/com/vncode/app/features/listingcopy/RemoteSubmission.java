package com.vncode.app.features.listingcopy;

import com.vncode.app.integration.marketplace.Marketplace;
import java.util.*;
public record RemoteSubmission(int shopId,Marketplace marketplace,String owner,String requestHash,String taskId,List<String> articles,String requestJson,String mediaJson,String documentsJson,String phase,Map<String,String> identities) {
    public RemoteSubmission{articles=List.copyOf(articles);identities=Map.copyOf(identities);}
    public RemoteSubmission withPhase(String phase,Map<String,String> ids){return new RemoteSubmission(shopId,marketplace,owner,requestHash,taskId,articles,requestJson,mediaJson,documentsJson,phase,ids);}
    @Override public String toString(){return "RemoteSubmission[shop="+shopId+", phase="+phase+"]";}
}
