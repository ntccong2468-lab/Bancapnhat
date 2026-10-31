package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/** Separate seller-reviewed identity link; it never asserts that a barcode was applied. */
public final class OzonReviewedGtinLinks {
    public record Link(String gtin,long goodId,String sku,String owner,String source,String revision) {}
    private OzonReviewedGtinLinks() {}
    private static void schema(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.execute("""
            CREATE TABLE IF NOT EXISTS ozon_reviewed_gtin_links(
                shop_id INTEGER NOT NULL,product_id TEXT NOT NULL,offer_id TEXT NOT NULL,
                gtin TEXT NOT NULL,good_id INTEGER NOT NULL,sku TEXT NOT NULL,
                owner TEXT NOT NULL,source TEXT NOT NULL,revision TEXT NOT NULL,approved_at TEXT NOT NULL,
                PRIMARY KEY(shop_id,product_id,offer_id),UNIQUE(shop_id,gtin))
            """);}
    }
    public static Link read(Shop shop,ProductKey key) {
        if(shop==null||key.shopId()!=shop.getId()||key.marketplace()!=Marketplace.OZON||shop.getMarketplace()!=Marketplace.OZON)throw new SecurityException("shop_mismatch");
        ListingIdentity.require(shop);
        try(var c=Database.getConnection()){
            try{return read(c,key);}
            catch(SQLException error){
                // Older data stores may not have this optional table yet. Only
                // initialize it for that exact condition; propagate every other
                // storage error instead of treating it as a missing review.
                String message=error.getMessage();
                if(error.getErrorCode()!=1||message==null||!message.contains("no such table: ozon_reviewed_gtin_links"))throw error;
                schema(c);return read(c,key);
            }
        }catch(SQLException e){throw new IllegalStateException("SYNC_STORAGE",e);}
    }
    private static Link read(Connection c,ProductKey key)throws SQLException {
        try(var p=c.prepareStatement("SELECT gtin,good_id,sku,owner,source,revision FROM ozon_reviewed_gtin_links WHERE shop_id=? AND product_id=? AND offer_id=?")){
            p.setInt(1,key.shopId());p.setString(2,key.productId());p.setString(3,key.variantId());try(var r=p.executeQuery()){return r.next()?new Link(r.getString(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)):null;}
        }
    }
    static void save(Shop shop,ProductKey key,Link link) {
        String owner=ListingIdentity.fingerprint(shop),source=RegisteredGtinProof.source(shop);
        if(!owner.equals(link.owner())||!source.equals(link.source())||!RegisteredGtinValidator.isValid(link.gtin())||!link.revision().matches("[0-9a-f]{64}")||link.goodId()<=0||!link.sku().matches("[1-9][0-9]{0,18}"))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");
        try{SqliteWriteTransaction.run(c->{schema(c);if(!owner.equals(ListingIdentity.fingerprint(c,key.shopId(),Marketplace.OZON))||!source.equals(RegisteredGtinProof.source(shop)))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");
            try(var p=c.prepareStatement("INSERT INTO ozon_reviewed_gtin_links VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(shop_id,product_id,offer_id) DO UPDATE SET gtin=excluded.gtin,good_id=excluded.good_id,sku=excluded.sku,owner=excluded.owner,source=excluded.source,revision=excluded.revision,approved_at=excluded.approved_at")){
                p.setInt(1,key.shopId());p.setString(2,key.productId());p.setString(3,key.variantId());p.setString(4,RegisteredGtinProof.canonicalCode(link.gtin()));p.setLong(5,link.goodId());p.setString(6,link.sku());p.setString(7,owner);p.setString(8,source);p.setString(9,link.revision());p.setString(10,Instant.now().toString());p.executeUpdate();
            }return null;
        });}catch(SQLException e){throw new IllegalStateException("SYNC_STORAGE",e);}
    }
}
