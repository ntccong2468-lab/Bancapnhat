package com.tuandev.fbsbarcode.integration.znack.registration;

import java.util.List;
import java.util.Map;

public final class ZnackCardRegistrationModels {
    private ZnackCardRegistrationModels() {
    }

    public enum Status {
        NOT_CREATED,
        CHECKING,
        PREFLIGHT_ERROR,
        ALLOCATING_GTIN,
        GTIN_REVIEW_REQUIRED,
        GTIN_GENERATED,
        READY_TO_SUBMIT,
        PRE_SUBMIT_ERROR,
        FEED_SUBMITTING,
        FEED_REVIEW_REQUIRED,
        FEED_SUBMITTED,
        PROCESSING,
        READY_TO_SIGN,
        SIGNING,
        PUBLISHED,
        ERROR
    }

    public enum RegistrationAction { REGISTER, REQUEST_NEW_GTIN, RESUME }

    public record Sku(long nmId, long chrtId, int subjectId, String vendorCode, String subjectName, String brand,
                      String title, String color, String size, List<String> barcodes, String imageUrl,
                      boolean needKiz, String gtin, Long goodId, String feedId, Status status,
                      String errorMessage, boolean wbUpdated, String wbSize, String registrationRevision) {
        public Sku {
            barcodes = barcodes == null ? List.of() : List.copyOf(barcodes);
            status = status == null ? Status.NOT_CREATED : status;
            wbSize = wbSize == null ? "" : wbSize.trim();
            registrationRevision = registrationRevision == null ? "" : registrationRevision;
        }

        public Sku(long nmId, long chrtId, int subjectId, String vendorCode, String subjectName, String brand,
                   String title, String color, String size, List<String> barcodes, String imageUrl,
                   boolean needKiz, String gtin, Long goodId, String feedId, Status status,
                   String errorMessage, boolean wbUpdated, String wbSize) {
            this(nmId,chrtId,subjectId,vendorCode,subjectName,brand,title,color,size,barcodes,imageUrl,
                    needKiz,gtin,goodId,feedId,status,errorMessage,wbUpdated,wbSize,"");
        }

        public boolean hasIdentity() {
            return hasGtin() || goodId != null || (feedId != null && !feedId.isBlank()) || wbUpdated;
        }

        public boolean hasGtin() { return gtin != null && !gtin.isBlank(); }

        public boolean needsGtinReview() {
            return !hasIdentity() && (status == Status.CHECKING || status == Status.ALLOCATING_GTIN
                    || status == Status.GTIN_REVIEW_REQUIRED || status == Status.ERROR);
        }

        public boolean canRegister() {
            return ((status == Status.NOT_CREATED || status == Status.PREFLIGHT_ERROR) && !hasIdentity())
                    || (status == Status.ERROR && hasGtin() && hasFeed())
                    || (status == Status.PRE_SUBMIT_ERROR && hasGtin() && !hasFeed() && goodId==null);
        }

        public boolean hasFeed() { return feedId != null && !feedId.isBlank(); }

        public boolean needsFeedReview() {
            return hasGtin() && (status == Status.FEED_SUBMITTING || status == Status.FEED_REVIEW_REQUIRED
                    || (!hasFeed() && (status == Status.ERROR || status == Status.GTIN_GENERATED
                    || status == Status.CHECKING)));
        }

        public boolean canResume() {
            return hasGtin() && (hasFeed() || status==Status.READY_TO_SUBMIT)
                    && status != Status.PUBLISHED && status != Status.ERROR
                    && status != Status.NOT_CREATED && status != Status.GTIN_REVIEW_REQUIRED
                    && status != Status.FEED_SUBMITTING && status != Status.FEED_REVIEW_REQUIRED;
        }

        public Sku(long nmId, long chrtId, int subjectId, String vendorCode, String subjectName, String brand,
                   String title, String color, String size, List<String> barcodes, String imageUrl,
                   boolean needKiz, String gtin, Long goodId, String feedId, Status status,
                   String errorMessage, boolean wbUpdated) {
            this(nmId, chrtId, subjectId, vendorCode, subjectName, brand, title, color, size, barcodes,
                    imageUrl, needKiz, gtin, goodId, feedId, status, errorMessage, wbUpdated, "");
        }

        public String sourceBarcode() {
            return barcodes.stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
        }
    }

    public record WbCharacteristic(int id, String name, List<String> values) {
        public WbCharacteristic {
            name = name == null ? "" : name;
            values = values == null ? List.of() : List.copyOf(values);
        }

        public String joinedValue() { return String.join(", ", values); }
    }

    public record SearchCriteria(int shopId, String query, List<String> subjects, String status,
                                 int limit, int offset) {
    }

    public record Category(long id, String name) {
        @Override public String toString() { return name + " (" + id + ")"; }
    }

    public record Attribute(long id, String name, String fieldType, boolean presetOnly,
                            boolean multiple, boolean firstLayer, boolean secondLayer,
                            List<String> presets, List<String> valueTypes) {
        public Attribute {
            presets = presets == null ? List.of() : List.copyOf(presets);
            valueTypes = valueTypes == null ? List.of() : List.copyOf(valueTypes);
        }

        public Attribute(long id, String name, String fieldType, boolean presetOnly,
                         boolean multiple, boolean firstLayer, boolean secondLayer, List<String> presets) {
            this(id, name, fieldType, presetOnly, multiple, firstLayer, secondLayer, presets, List.of());
        }
    }

    public record Gs1Status(long limit, long usage, int existingDrafts, boolean quotaKnown) {
        public Gs1Status(long limit, long usage, int existingDrafts) {
            this(limit, usage, existingDrafts, true);
        }

        public long remaining() { return Math.max(0L, limit - usage); }
        public boolean canGenerate() { return !quotaKnown || remaining() > 0; }
    }

    public record Draft(String tnved, String feedTnved, long categoryId, String goodName, String brand,
                        Map<Long, String> attributes, Map<Long, String> attributeTypes) {
        public Draft {
            feedTnved = feedTnved == null || feedTnved.isBlank() ? tnved : feedTnved;
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            attributeTypes = attributeTypes == null ? Map.of() : Map.copyOf(attributeTypes);
        }

        public Draft(String tnved, String feedTnved, long categoryId, String goodName, String brand,
                     Map<Long, String> attributes) {
            this(tnved, feedTnved, categoryId, goodName, brand, attributes, Map.of());
        }

        public Draft(String tnved, long categoryId, String goodName, String brand,
                     Map<Long, String> attributes) {
            this(tnved, tnved, categoryId, goodName, brand, attributes);
        }
    }
}
