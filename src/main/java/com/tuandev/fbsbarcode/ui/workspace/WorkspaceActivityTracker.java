package com.tuandev.fbsbarcode.ui.workspace;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class WorkspaceActivityTracker {
    private final Map<Integer, Boolean> runningByShopId = new ConcurrentHashMap<>();
    private final Set<Integer> syncingShopIds = ConcurrentHashMap.newKeySet();

    boolean markSyncStarted(int shopId) {
        runningByShopId.put(shopId, true);
        return syncingShopIds.add(shopId);
    }

    void markRunning(int shopId, boolean running) {
        runningByShopId.put(shopId, running);
        if (!running) {
            syncingShopIds.remove(shopId);
        }
    }

    boolean isRunning(int shopId) {
        return runningByShopId.getOrDefault(shopId, false);
    }

    boolean isSyncing(int shopId) {
        return syncingShopIds.contains(shopId);
    }

    void clear(int shopId) {
        runningByShopId.remove(shopId);
        syncingShopIds.remove(shopId);
    }
}
