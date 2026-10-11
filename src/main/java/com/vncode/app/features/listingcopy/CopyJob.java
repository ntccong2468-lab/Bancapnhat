package com.vncode.app.features.listingcopy;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.*;
public record CopyJob(String id,int sourceShopId,String sourceOwner,int destinationShopId,Marketplace marketplace,String destinationOwner,ProductSnapshot source,CopyOptions options,CopyPreview preview,RemoteSubmission submission,CopyState state,List<CopyIssue> issues,Instant createdAt,Instant updatedAt) {
    public CopyJob{issues=List.copyOf(issues);}
    public CopyJob with(CopyState state,ProductSnapshot source,CopyOptions options,CopyPreview preview,RemoteSubmission submission,List<CopyIssue> issues){return new CopyJob(id,sourceShopId,sourceOwner,destinationShopId,marketplace,destinationOwner,source,options,preview,submission,state,issues,createdAt,Instant.now());}
    public CopyJob state(CopyState value){return new CopyJob(id,sourceShopId,sourceOwner,destinationShopId,marketplace,destinationOwner,source,options,preview,submission,value,issues,createdAt,updatedAt);}
    /** Bounded history projection; publishing always reloads the complete owned job. */
    public CopyJob summary(){
        var brief=new ProductSnapshot(source.key(),source.revision(),shortText(source.title(),512),"","",shortText(source.vendorArticle(),128),source.categoryId(),List.of(),List.of(),List.of(),null,List.of(),source.checkedAt());
        return new CopyJob(id,sourceShopId,sourceOwner,destinationShopId,marketplace,destinationOwner,brief,null,null,null,state,List.of(),createdAt,updatedAt);
    }
    private static String shortText(String value,int maximum){return value==null?"":value.length()<=maximum?value:value.substring(0,maximum);}
    @Override public String toString(){return "CopyJob[id="+id+", destination="+destinationShopId+", state="+state+"]";}
}
