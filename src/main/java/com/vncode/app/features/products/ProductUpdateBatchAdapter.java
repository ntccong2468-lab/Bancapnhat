package com.vncode.app.features.products;

import com.vncode.app.features.listingcopy.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;

/** One HTTP request with separate durable outcomes for every selected own product. */
public interface ProductUpdateBatchAdapter extends ListingTargetAdapter {
    record Outcome(RemoteSubmission submission,String rejection){
        public static Outcome accepted(RemoteSubmission value){return new Outcome(Objects.requireNonNull(value),null);}
        public static Outcome rejected(String reason){return new Outcome(null,Objects.requireNonNull(reason));}
    }
    List<List<ProductEditPreview>> updateBatches(List<ProductEditPreview> previews)throws IOException;
    Map<ProductKey,Outcome> submitUpdates(Shop shop,List<ProductEditPreview> previews,Runnable beforeSend)throws IOException;
}
