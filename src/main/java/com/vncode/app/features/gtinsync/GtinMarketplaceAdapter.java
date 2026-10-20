package com.vncode.app.features.gtinsync;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
public interface GtinMarketplaceAdapter {
    Capability capability(ProductKey key) throws Exception;
    ProductSnapshot read(ProductKey key) throws Exception;
    default boolean snapshotMatches(ProductSnapshot expected,ProductSnapshot actual)throws Exception{return expected.fingerprint().equals(actual.fingerprint());}
    boolean conflicts(ProductKey key,String gtin) throws Exception;
    default void validateRegistration(String gtin) throws Exception {}
    default void validateProduct(ProductKey key) throws Exception {}
    Submission submit(Preview preview) throws Exception;
    Verification verify(Preview preview,Submission submission) throws Exception;
}
