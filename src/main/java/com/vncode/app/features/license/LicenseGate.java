package com.vncode.app.features.license;

import static com.vncode.app.features.license.LicenseModels.*;

public final class LicenseGate {
    private final LicenseService service;
    public LicenseGate(LicenseService service){this.service=service;}
    public void require(Permission permission){if(!service.allows(permission))throw new LicenseDeniedException();}
    public void requireShopCapacity(int currentCount){require(Permission.SHOP_MANAGE);if(currentCount<0||currentCount>=service.maxShops())throw new LicenseDeniedException();}
    public static final class LicenseDeniedException extends IllegalStateException {
        public LicenseDeniedException(){this("LICENSE_REQUIRED");}
        public LicenseDeniedException(String code){super(code.equals("LICENSE_SHOP_LIMIT")?code:"LICENSE_REQUIRED");}
    }
}
