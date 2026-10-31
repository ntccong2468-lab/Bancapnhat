package com.vncode.app.integration.wb;

import java.io.IOException;

/** WB acknowledged the write, but the later readback is not yet confirmed. */
public final class WbReadbackPendingException extends IOException {
    private final String supplyId;
    public WbReadbackPendingException(String supplyId, Throwable cause) {
        super("WB_SUPPLY_READBACK_PENDING", cause);
        this.supplyId = supplyId;
    }
    public String supplyId() { return supplyId; }
}
