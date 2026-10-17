package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.*;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;

/** Registering the selected card authorizes its exact published GTIN to reach that variant.
 * Existing installations gain no blanket approval; consent is captured only on a new user action. */
public final class RegistrationMarketplaceConsent {
    private RegistrationMarketplaceConsent(){}
    public static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.execute("""
            CREATE TABLE IF NOT EXISTS znack_registration_marketplace_consents(
              shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,marketplace TEXT NOT NULL,
              product_id TEXT NOT NULL,variant_id TEXT NOT NULL,gtin TEXT NOT NULL,owner TEXT NOT NULL,
              enabled INTEGER NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(shop_id,marketplace,product_id,variant_id,gtin,owner))
            """);}
    }
    public static void set(Shop shop,ProductKey key,String gtin,boolean enabled) {
        ListingIdentity.require(shop,key);
        try{SqliteWriteTransaction.run(c->{
            require(c,shop);
            if(!current(c,key,gtin))throw new IllegalArgumentException("REGISTRATION_CHANGED");
            try(var p=c.prepareStatement("INSERT INTO znack_registration_marketplace_consents VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,product_id,variant_id,gtin,owner) DO UPDATE SET enabled=excluded.enabled,updated_at=excluded.updated_at")){
                bind(p,shop,key,gtin);p.setInt(7,enabled?1:0);p.setString(8,Instant.now().toString());p.executeUpdate();
            }return null;
        });}catch(SQLException e){throw new IllegalStateException("REGISTRATION_STORAGE",e);}
    }
    public static boolean allowed(Shop shop,ProductKey key,String gtin) {
        ListingIdentity.require(shop,key);
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT enabled FROM znack_registration_marketplace_consents WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND gtin=? AND owner=?")){
            require(c,shop);if(!current(c,key,gtin))return false;bind(p,shop,key,gtin);try(var rows=p.executeQuery()){return rows.next()&&rows.getInt(1)==1;}
        }catch(SQLException e){throw new IllegalStateException("REGISTRATION_STORAGE",e);}
    }
    public record Candidate(String productId,String variantId,String gtin){}
    /** A scheduling filter only. The live owner/scope/consent guards still run before confirmation. */
    public static java.util.Set<Candidate> publishedCandidates(Shop shop){
        ListingIdentity.require(shop);
        String nativeJoin=shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON?
                "JOIN ozon_card_registrations r ON r.shop_id=c.shop_id AND r.product_id=c.product_id AND r.offer_id=c.variant_id AND r.gtin=c.gtin AND r.owner=c.owner ":
                "JOIN znack_card_registrations r ON r.shop_id=c.shop_id AND r.nm_id=c.product_id AND r.chrt_id=c.variant_id AND r.gtin=c.gtin JOIN znack_card_registration_scopes s ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id AND s.owner_hash=c.owner ";
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT c.product_id,c.variant_id,c.gtin FROM znack_registration_marketplace_consents c "+nativeJoin+"WHERE c.shop_id=? AND c.marketplace=? AND c.owner=? AND c.enabled=1 AND r.status='PUBLISHED'")){
            require(c,shop);p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());p.setString(3,ListingIdentity.fingerprint(shop));
            var candidates=new java.util.HashSet<Candidate>();try(var rows=p.executeQuery()){while(rows.next())candidates.add(new Candidate(rows.getString(1),rows.getString(2),rows.getString(3)));}
            return java.util.Set.copyOf(candidates);
        }catch(SQLException e){throw new IllegalStateException("REGISTRATION_STORAGE",e);}
    }
    private static void bind(PreparedStatement p,Shop shop,ProductKey key,String gtin)throws SQLException {
        p.setInt(1,shop.getId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());p.setString(4,key.variantId());p.setString(5,gtin);p.setString(6,ListingIdentity.fingerprint(shop));
    }
    private static boolean current(Connection c,ProductKey key,String gtin)throws SQLException {
        if(!com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(gtin))return false;
        String sql=key.marketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON?"SELECT 1 FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=? AND gtin=?":"SELECT 1 FROM znack_card_registrations WHERE shop_id=? AND nm_id=? AND chrt_id=? AND gtin=?";
        try(var p=c.prepareStatement(sql)){p.setInt(1,key.shopId());p.setString(2,key.cardId());p.setString(3,key.variantId());p.setString(4,gtin);try(var r=p.executeQuery()){return r.next();}}
    }
    private static void require(Connection c,Shop shop)throws SQLException {
        if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
    }
}
