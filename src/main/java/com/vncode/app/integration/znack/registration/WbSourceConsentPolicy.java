package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.SQLException;
import java.util.Set;

/** A legacy receipt can be read, but automatic writes require the exact reviewed WB submission. */
public final class WbSourceConsentPolicy {
    private WbSourceConsentPolicy() {}

    public static boolean permitsAutomatic(Shop shop, ProductKey key, String gtin) {
        if (shop == null || key == null || shop.getMarketplace() != Marketplace.WILDBERRIES
                || key.marketplace() != Marketplace.WILDBERRIES || key.shopId() != shop.getId()
                || !key.cardId().matches("[1-9][0-9]{0,18}") || !key.variantId().matches("[1-9][0-9]{0,18}")
                || !RegisteredGtinValidator.isValid(gtin)) return false;
        try {
            ListingIdentity.require(shop, key);
            String owner = ListingIdentity.fingerprint(shop), source = RegisteredGtinProof.source(shop);
            try (var c = Database.getConnection(); var p = c.prepareStatement("""
                SELECT b.owner,b.scope,b.state,i.feed_id,c.owner,c.scope,c.phase,c.expected_gtin_hash,c.submission_hash,
                       r.gtin,r.good_id,r.feed_id,r.payload_json,s.owner_hash,s.participant_inn,s.environment,s.fingerprint
                FROM znack_registration_batch_items i JOIN znack_registration_batches b ON b.id=i.run_id
                JOIN znack_card_registrations r ON r.shop_id=b.shop_id
                  AND json_valid(i.sku_json) AND r.nm_id=json_extract(i.sku_json,'$.nmId')
                  AND r.chrt_id=json_extract(i.sku_json,'$.chrtId')
                JOIN znack_card_registration_scopes s ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id
                LEFT JOIN znack_source_consents c ON c.run_id=i.run_id AND c.position=i.position AND c.shop_id=b.shop_id
                  AND c.marketplace='WILDBERRIES' AND c.product_id=CAST(r.nm_id AS TEXT) AND c.variant_id=CAST(r.chrt_id AS TEXT)
                WHERE b.shop_id=? AND r.nm_id=? AND r.chrt_id=? ORDER BY b.rowid DESC LIMIT 1
                """)) {
                p.setInt(1, shop.getId()); p.setString(2, key.cardId()); p.setString(3, key.variantId());
                try (var r = p.executeQuery()) {
                    if (!r.next() || !owner.equals(r.getString(1)) || !owner.equals(r.getString(5))
                            || !source.equals(r.getString(6)) || !"SUBMITTED".equals(r.getString(7))
                            || !owner.equals(r.getString(14)) || Set.of("PAUSED", "CANCELLED").contains(r.getString(3))) return false;
                    var batchScope = new Gson().fromJson(r.getString(2), ZnackRegistrationScope.class);
                    var nativeScope = new ZnackRegistrationScope(r.getString(15), r.getString(16), r.getString(17));
                    if (!nativeScope.equals(batchScope)) return false;
                    nativeScope.assertCurrent(shop);
                    String current = r.getString(10), feed = r.getString(12), saved = r.getString(4), payload = r.getString(13);
                    if (!RegisteredGtinProof.sameCode(gtin, current) || feed == null || feed.isBlank() || !feed.equals(saved)
                            || r.getLong(11) <= 0 || r.wasNull() || !ListingIdentity.hash(current).equals(r.getString(8))
                            || payload == null || payload.isBlank()) return false;
                    return ListingIdentity.hash(ListingIdentity.canonical(JsonParser.parseString(payload))).equals(r.getString(9));
                }
            }
        } catch (SQLException | RuntimeException unavailable) { return false; }
    }
}
