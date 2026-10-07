package com.vncode.app.features.gtinsync;

import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, credential-free boundary between GTIN UI, storage and marketplace adapters. */
public final class GtinSyncModels {
    private GtinSyncModels() {}
    public enum Operation { ADD, REPLACE }
    public enum Status { QUEUED, VALIDATING, SENDING, AWAITING_VERIFICATION, SUCCEEDED, FAILED, RECONCILE_REQUIRED, CANCELLED }
    public enum Verification { PENDING, APPLIED, REJECTED, UNKNOWN }
    public record ProductKey(int shopId, Marketplace marketplace, String productId, String variantId) {
        public ProductKey {
            if (shopId <= 0) throw new IllegalArgumentException("Positive shop identity required");
            Objects.requireNonNull(marketplace);
            productId = required(productId); variantId = required(variantId);
        }
    }
    public record RegisteredGtin(String gtin, String article, String color, String size,
                                 boolean tradeUnit, boolean published, String source, Instant syncedAt) {
        public RegisteredGtin {
            gtin = required(gtin); article = safe(article); color = safe(color); size = safe(size);
            source = required(source); Objects.requireNonNull(syncedAt);
        }
    }
    public record ProductSnapshot(ProductKey key, String article, String color, String size,
                                  List<String> barcodes, String fingerprint, Instant fetchedAt) {
        public ProductSnapshot {
            Objects.requireNonNull(key); article = safe(article); color = safe(color); size = safe(size);
            barcodes = List.copyOf(barcodes); fingerprint = required(fingerprint); Objects.requireNonNull(fetchedAt);
        }
    }
    public record Capability(boolean add, boolean replace, String reason) {
        public Capability { reason = safe(reason); }
        public boolean supports(Operation operation) { return operation == Operation.ADD ? add : replace; }
    }
    public record MatchResult(ProductKey key, List<RegisteredGtin> candidates, String reason) {
        public MatchResult { candidates = List.copyOf(candidates); reason = safe(reason); }
        public boolean exact() { return candidates.size() == 1 && reason.isBlank(); }
    }
    public record Preview(ProductSnapshot before, Operation operation, String oldGtin, String newGtin, Capability capability) {
        public Preview {
            Objects.requireNonNull(before); Objects.requireNonNull(operation); Objects.requireNonNull(capability);
            oldGtin = safe(oldGtin); newGtin = required(newGtin);
        }
    }
    public record Submission(String remoteTaskId) { public Submission { remoteTaskId = safe(remoteTaskId); } }
    public record JobItem(UUID id, UUID jobId, Preview preview, Status status, String remoteTaskId) {}
    static String safe(String value) { return value == null ? "" : value.strip(); }
    private static String required(String value) {
        String result = safe(value);
        if (result.isBlank() || result.length() > 1000 || result.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid identity or value");
        return result;
    }
}
