package com.vncode.app.ui.workspace;

import java.util.Objects;

/** One print intent for the supply load currently owned by the JavaFX thread. */
final class SupplyPrintRequest {
    private int shopId;
    private String supplyId;
    private long token;
    private boolean loading;
    private boolean pending;

    void begin(int shopId, String supplyId, long token) {
        this.shopId = shopId;
        this.supplyId = supplyId;
        this.token = token;
        loading = true;
        pending = false;
    }

    boolean request() {
        if (!loading || pending) return false;
        pending = true;
        return true;
    }

    boolean complete(int shopId, String supplyId, long token, boolean success) {
        if (!loading || this.shopId != shopId || !Objects.equals(this.supplyId, supplyId)
                || this.token != token) return false;
        boolean print = success && pending;
        cancel();
        return print;
    }

    void cancel() {
        loading = false;
        pending = false;
        supplyId = null;
    }

    boolean isLoading() { return loading; }
    boolean isPending() { return pending; }
}
