package com.vncode.app.integration.znack.registration;

/** WB and Ozon must not concurrently claim the same reusable enterprise draft GTIN. */
public final class CatalogAllocationLock {
    public static final Object MONITOR=new Object();
    private CatalogAllocationLock() {}
}
