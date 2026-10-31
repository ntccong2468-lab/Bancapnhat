package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.SQLException;

/** Old receipts remain readable; automatic writes require the exact durable reviewed submission. */
public final class OzonSourceConsentPolicy {
    private OzonSourceConsentPolicy(){}
    public static boolean permitsAutomatic(Shop shop,ProductKey key,String gtin){
        if(shop==null||key==null||shop.getMarketplace()!=Marketplace.OZON||key.marketplace()!=Marketplace.OZON||key.shopId()!=shop.getId()||!RegisteredGtinValidator.isValid(gtin))return false;
        try{
            ListingIdentity.require(shop,key);String owner=ListingIdentity.fingerprint(shop),source=RegisteredGtinProof.source(shop);
            try(var c=Database.getConnection()){
                try(var q=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='znack_source_consents'" );var found=q.executeQuery()){if(!found.next())return false;}
                try(var p=c.prepareStatement("""
                    SELECT b.owner,b.scope_json,b.state,i.feed_id,c.owner,c.scope,c.phase,c.expected_gtin_hash,c.submission_hash,
                           r.gtin,r.good_id,r.feed_id,r.payload_json,r.owner,r.scope_json
                    FROM ozon_registration_batch_items i JOIN ozon_registration_batches b ON b.run_id=i.run_id
                    JOIN ozon_card_registrations r ON r.shop_id=b.shop_id AND r.product_id=i.product_id AND r.offer_id=i.offer_id
                    LEFT JOIN znack_source_consents c ON c.run_id=i.run_id AND c.position=i.position AND c.shop_id=b.shop_id
                        AND c.marketplace='OZON' AND c.product_id=i.product_id AND c.variant_id=i.offer_id
                    WHERE b.shop_id=? AND i.product_id=? AND i.offer_id=? ORDER BY b.rowid DESC LIMIT 1
                    """)){
                    p.setInt(1,shop.getId());p.setString(2,key.cardId());p.setString(3,key.variantId());
                    try(var r=p.executeQuery()){
                        if(!r.next()||!owner.equals(r.getString(1))||!owner.equals(r.getString(5))||!source.equals(r.getString(6))
                                ||!"SUBMITTED".equals(r.getString(7))||!owner.equals(r.getString(14))||!r.getString(2).equals(r.getString(15))
                                ||java.util.Set.of("PAUSED","CANCELLED").contains(r.getString(3)))return false;
                        String current=r.getString(10),feed=r.getString(12),saved=r.getString(4),payload=r.getString(13);
                        if(!RegisteredGtinProof.sameCode(gtin,current)||feed==null||feed.isBlank()||!feed.equals(saved)||r.getLong(11)<=0||r.wasNull()
                                ||!ListingIdentity.hash(current).equals(r.getString(8))||payload==null||payload.isBlank())return false;
                        return ListingIdentity.hash(ListingIdentity.canonical(JsonParser.parseString(payload))).equals(r.getString(9));
                    }
                }
            }
        }catch(SQLException|RuntimeException unavailable){return false;}
    }
}
