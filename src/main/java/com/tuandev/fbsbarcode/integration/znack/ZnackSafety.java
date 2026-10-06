package com.tuandev.fbsbarcode.integration.znack;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Settings;

public final class ZnackSafety {
    public static final String MISSING_SHOP_CONFIGURATION =
            "Cửa hàng hiện tại chưa cấu hình chữ ký điện tử hoặc omsConnection cho Честный ЗНАК.";
    public static final String UNVERIFIED_SIGNATURE =
            "Chữ ký điện tử của cửa hàng hiện tại chưa được kiểm tra thành công. Vui lòng bấm Test signature trước khi gửi yêu cầu Честный ЗНАК.";

    private ZnackSafety() {
    }

    public static void requireSigned(Settings settings, boolean requireOmsConnection) {
        if (settings == null || blank(settings.signerCertificate()) || settings.signerTestedAt() == null) {
            throw new IllegalStateException(UNVERIFIED_SIGNATURE);
        }
        if (requireOmsConnection && blank(settings.omsConnection())) {
            throw new IllegalStateException(MISSING_SHOP_CONFIGURATION);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
