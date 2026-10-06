package com.tuandev.fbsbarcode.integration.license;

/** Trạng thái license hiện tại của app; {@code payload} có thể null khi chưa kích hoạt. */
public record LicenseState(LicenseStatus status, LicensePayload payload, String detail) {

    public static LicenseState of(LicenseStatus status) {
        return new LicenseState(status, null, null);
    }

    /** Tính năng trả phí (pipeline mua KIZ) chỉ mở ở hai trạng thái này. */
    public boolean kizAllowed() {
        return status == LicenseStatus.VALID || status == LicenseStatus.OFFLINE_GRACE;
    }

    public enum LicenseStatus {
        /** Chưa nhập license key. */
        NOT_ACTIVATED,
        /** Server xác nhận còn hạn. */
        VALID,
        /** Mất mạng nhưng license file cache còn hạn và trong grace period. */
        OFFLINE_GRACE,
        /** Hết hạn thuê bao (server xác nhận hoặc theo license file cache). */
        EXPIRED,
        /** Key sai hoặc đã bị thu hồi. */
        INVALID,
        /** Vượt số máy cho phép của license. */
        DEVICE_LIMIT,
        /** Đồng hồ hệ thống bị chỉnh lùi so với lần xác thực cuối. */
        CLOCK_TAMPERED,
        /** Mất mạng và không có (hoặc đã quá grace) license file cache. */
        NETWORK_ERROR
    }
}
