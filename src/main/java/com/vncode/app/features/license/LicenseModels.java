package com.vncode.app.features.license;

import java.util.Set;

public final class LicenseModels {
    private LicenseModels() {}
    public enum Permission { SELLER_API, SHOP_MANAGE, GTIN, KIZ, LISTING_WRITE, PACKING, PRINT_NEW, GS1 }
    public enum Status { UNREGISTERED, REGISTERING, VERIFYING_EMAIL, PENDING, APPROVED, CHECKING, ACTIVE, OFFLINE, EXPIRED, DENIED, UNVERIFIED }
    public record Envelope(String keyId, String payload, String signature) {
        @Override public String toString() { return "Envelope[redacted]"; }
    }
    public record Account(String id, String email, String status, long expiresAt) {
        @Override public String toString() { return "Account[redacted]"; }
    }
    public record Lease(String accountId, String deviceId, String deviceHash, long revision,
                        long issuedAt, long expiresAt, long subscriptionExpiresAt,
                        int maxDevices, int maxShops, int offlineHours, Set<Permission> permissions) {
        public Lease { permissions = Set.copyOf(permissions); }
        @Override public String toString() { return "Lease[redacted]"; }
    }
    public record Snapshot(Status status, String email, long expiresAt, long offlineExpiresAt,
                           int maxDevices, int maxShops, String errorCode, boolean busy) {
        @Override public String toString() { return "Snapshot[" + status + "]"; }
    }
    public record State(String publicKey, String privateKey, String registrationToken, String accessToken,
                        Account account, Envelope envelope, long maxObservedEpoch, long minRevision, String denialCode) {
        @Override public String toString() { return "State[redacted]"; }
    }
}
