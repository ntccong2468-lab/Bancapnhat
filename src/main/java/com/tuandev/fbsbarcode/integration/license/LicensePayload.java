package com.tuandev.fbsbarcode.integration.license;

/**
 * Nội dung license file đã được server ký Ed25519. Trường khớp 1:1 với JSON payload
 * mà license-server trả về (xem license-server/README.md).
 */
public record LicensePayload(
        int v,
        String licenseKey,
        String fingerprint,
        String plan,
        int maxDevices,
        String status,
        long issuedAt,
        long expiresAt) {
}
