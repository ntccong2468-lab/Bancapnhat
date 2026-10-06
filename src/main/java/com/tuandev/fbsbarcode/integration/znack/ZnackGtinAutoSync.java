package com.tuandev.fbsbarcode.integration.znack;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-wide guard shared by every pane that pulls GTIN data from Znack automatically.
 * GTIN data is auto-synced at most once per shop per application session; afterwards the
 * user triggers a sync manually with the refresh buttons.
 */
public final class ZnackGtinAutoSync {
    private static final Set<Integer> AUTO_SYNCED_SHOPS = ConcurrentHashMap.newKeySet();

    private ZnackGtinAutoSync() {
    }

    /** Returns true exactly once per shop for the lifetime of the application session. */
    public static boolean shouldAutoSync(int shopId) {
        return AUTO_SYNCED_SHOPS.add(shopId);
    }

    /** Records a successful background recovery sync so another pane does not immediately repeat it. */
    public static void markAutoSynced(int shopId) {
        AUTO_SYNCED_SHOPS.add(shopId);
    }
}
