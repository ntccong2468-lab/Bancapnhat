package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;

/** Fresh, exact variant facts explicitly displayed to and approved by the seller. */
final class CatalogApprovedVariantValues {
    private final Map<Long, String> values;
    private final String gtin;
    private final String schemaRevision;
    private CatalogApprovedVariantValues(Map<Long, String> values, String gtin, String schemaRevision) {
        this.values = Map.copyOf(values);
        this.gtin = gtin;
        this.schemaRevision = schemaRevision;
    }
    String value(long id) { return values.get(id); }
    boolean matches(String candidate,List<Attribute> schema){
        return candidate!=null&&com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(gtin,candidate)
                &&schema!=null&&schemaRevision.equals(RegistrationReviewedValues.schemaRevision(schema));
    }

    /** Caller must supply a fresh marketplace snapshot and a fresh Catalog schema. */
    static CatalogApprovedVariantValues read(Shop shop, RegistrationSourceConsentGuard.VerifiedRepairIntent proof,
                                               ProductSnapshot snapshot, List<Attribute> schema) throws SQLException {
        if (shop == null || proof == null || snapshot == null || schema == null || !proof.key().equals(snapshot.key())) return null;
        var key = snapshot.key();
        ListingIdentity.require(shop,key);
        if (!ListingIdentity.fingerprint(shop).equals(snapshot.metadata().get("owner"))) return null;
        var variants = snapshot.variants().stream().filter(v -> key.variantId().equals(v.id())).limit(2).toList();
        if (variants.size() != 1) return null;
        try (Connection c = Database.getConnection()) {
            RegistrationSourceConsentGuard.requireCurrent(c, proof);
            try (var q = c.prepareStatement("SELECT source_revision,schema_revision,owner,sku_hash FROM znack_source_consents WHERE run_id=? AND intent_id=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")) {
                q.setString(1, proof.run()); q.setString(2, proof.intent()); q.setInt(3, key.shopId());
                q.setString(4, key.marketplace().name()); q.setString(5, key.cardId()); q.setString(6, key.variantId());
                try (var r = q.executeQuery()) {
                    if (!r.next() || !RegistrationReviewedValues.productRevision(snapshot).equals(r.getString(1))
                            || !RegistrationReviewedValues.schemaRevision(schema).equals(r.getString(2))
                            || !Objects.equals(snapshot.metadata().get("owner"), r.getString(3))) return null;
                    String sku = snapshot.metadata().getOrDefault("sku", "");
                    if (key.marketplace() == Marketplace.OZON && (!sku.matches("[1-9][0-9]{0,18}") || !ListingIdentity.hash(sku).equals(r.getString(4)))) return null;
                    if (r.next()) return null;
                }
            }
            var variant = variants.getFirst();
            var out = new HashMap<Long, String>();
            addIfApproved(c, proof, key, out, 35L, "variant.size", variant.size());
            addIfApproved(c, proof, key, out, 36L, "variant.color", variant.color());
            RegistrationSourceConsentGuard.requireCurrent(c, proof);
            var original=proof.originalPayload();
            if(!original.has("gtin")||!original.get("gtin").isJsonPrimitive())return null;
            return new CatalogApprovedVariantValues(out,original.get("gtin").getAsString(),RegistrationReviewedValues.schemaRevision(schema));
        }
    }
    private static void addIfApproved(Connection c, RegistrationSourceConsentGuard.VerifiedRepairIntent proof,
                                      com.vncode.app.features.products.ProductKey key, Map<Long,String> out,
                                      long id, String field, String value) throws SQLException {
        if (approved(c, proof, key, field, value)) out.put(id, value.strip());
    }
    private static boolean approved(Connection c, RegistrationSourceConsentGuard.VerifiedRepairIntent proof,
                                    com.vncode.app.features.products.ProductKey key, String field, String value) throws SQLException {
        String exact = Objects.toString(value, "").strip();
        if (exact.isBlank()) return false;
        try (var q = c.prepareStatement("SELECT value_hash,source FROM znack_source_consent_fields WHERE intent_id=? AND run_id=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND field=?")) {
            q.setString(1, proof.intent()); q.setString(2, proof.run()); q.setInt(3, key.shopId()); q.setString(4, key.marketplace().name());
            q.setString(5, key.cardId()); q.setString(6, key.variantId()); q.setString(7, field);
            try (var r = q.executeQuery()) {
                return r.next() && ListingIdentity.hash(exact).equals(r.getString(1))
                        && "MARKETPLACE_UNVERIFIED".equals(r.getString(2)) && !r.next();
            }
        }
    }
}
