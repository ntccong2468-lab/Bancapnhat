package com.tuandev.fbsbarcode.integration.license;

/**
 * License file dạng ký số: {@code payload} là base64 của JSON {@link LicensePayload},
 * {@code signature} là chữ ký Ed25519 trên đúng chuỗi byte đó.
 */
public record SignedLicenseFile(String payload, String signature, String algorithm) {
}
