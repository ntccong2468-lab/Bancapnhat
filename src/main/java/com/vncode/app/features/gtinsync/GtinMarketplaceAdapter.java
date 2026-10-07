package com.vncode.app.features.gtinsync;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
public interface GtinMarketplaceAdapter {
    Capability capability(ProductKey key) throws Exception;
    ProductSnapshot read(ProductKey key) throws Exception;
    boolean conflicts(ProductKey key,String gtin) throws Exception;
    Submission submit(Preview preview) throws Exception;
    Verification verify(Preview preview,Submission submission) throws Exception;
}
