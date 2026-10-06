package com.tuandev.fbsbarcode.integration.wb;

public final class WbSupplyNotEmptyException extends IllegalStateException {
    public WbSupplyNotEmptyException() {
        super("Wildberries supply is not empty");
    }
}
