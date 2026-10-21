package com.vncode.app.features.gtinsync;

/** A fresh, product-bound marketplace rejection is terminal and must not replay the write. */
public final class GtinRejectedException extends java.io.IOException {
    private final String code;
    public GtinRejectedException(String code){super(com.vncode.app.features.sync.SyncRunRepository.code(code));this.code=getMessage();}
    public String code(){return code;}
}
