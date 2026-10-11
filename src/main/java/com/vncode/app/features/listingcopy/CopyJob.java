package com.vncode.app.features.listingcopy;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.*;
public record CopyJob(String id,int sourceShopId,String sourceOwner,int destinationShopId,Marketplace marketplace,String destinationOwner,ProductSnapshot source,CopyOptions options,CopyPreview preview,RemoteSubmission submission,CopyState state,List<CopyIssue> issues,Instant createdAt,Instant updatedAt) {
    public CopyJob{issues=List.copyOf(issues);}
    public CopyJob with(CopyState state,ProductSnapshot source,CopyOptions options,CopyPreview preview,RemoteSubmission submission,List<CopyIssue> issues){return new CopyJob(id,sourceShopId,sourceOwner,destinationShopId,marketplace,destinationOwner,source,options,preview,submission,state,issues,createdAt,Instant.now());}
    public CopyJob state(CopyState value){return new CopyJob(id,sourceShopId,sourceOwner,destinationShopId,marketplace,destinationOwner,source,options,preview,submission,value,issues,createdAt,updatedAt);}
    @Override public String toString(){return "CopyJob[id="+id+", destination="+destinationShopId+", state="+state+"]";}
}
