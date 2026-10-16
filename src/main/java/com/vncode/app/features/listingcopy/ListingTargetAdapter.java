package com.vncode.app.features.listingcopy;

import com.vncode.app.features.products.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;
public interface ListingTargetAdapter {
    List<TargetCategory> categories(Shop shop)throws IOException;
    List<TargetAttribute> attributes(Shop shop,String categoryId)throws IOException;
    /** Read-only value suggestions. The preview still validates every selected card. */
    default List<String> suggestValues(Shop shop,String categoryId,String field,String query)throws IOException{return List.of();}
    CopyPreview preview(Shop shop,ProductSnapshot snapshot,CopyOptions options)throws IOException;
    RemoteSubmission submit(Shop shop,CopyPreview preview)throws IOException;
    RemoteResult reconcile(Shop shop,RemoteSubmission submission)throws IOException;
    /** Invoked only after a separate durable phase claim; never from a read callback. */
    RemoteSubmission finishAssets(Shop shop,RemoteSubmission submission)throws IOException;
    record ProductEditPreview(ProductKey key,String sourceRevision,CopyPreview preview){}
    ProductEditPreview previewUpdate(Shop shop,ProductSnapshot fresh,Map<String,List<String>> changes,List<ProductDocument> documents)throws IOException;
    RemoteSubmission submitUpdate(Shop shop,ProductEditPreview preview)throws IOException;
}
