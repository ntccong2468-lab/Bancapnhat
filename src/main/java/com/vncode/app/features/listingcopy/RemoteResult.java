package com.vncode.app.features.listingcopy;

import java.util.*;
public record RemoteResult(CopyState state,List<String> destinationIds,List<CopyIssue> issues,Map<String,String> identities) {
    public RemoteResult{destinationIds=List.copyOf(destinationIds);issues=List.copyOf(issues);identities=Map.copyOf(identities);}
    public static RemoteResult pending(){return new RemoteResult(CopyState.REMOTE_PROCESSING,List.of(),List.of(),Map.of());}
}
