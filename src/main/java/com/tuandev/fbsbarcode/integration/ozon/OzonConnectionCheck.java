package com.tuandev.fbsbarcode.integration.ozon;

/** Safe identity/permission summary suitable for returning to UI. */
public record OzonConnectionCheck(
        String clientId,
        int roleCount,
        int warehouseCount,
        String credentialExpiresAt,
        boolean exemplarAccess,
        boolean shipAccess,
        boolean labelAccess) {
}
