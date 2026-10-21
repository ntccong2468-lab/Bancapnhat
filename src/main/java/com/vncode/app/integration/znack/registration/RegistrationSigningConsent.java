package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.vncode.app.config.*;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;

/** Consent approves an immutable business payload or explicitly reviewed document, never a GTIN alone. */
public final class RegistrationSigningConsent {
    private RegistrationSigningConsent(){}
    public static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.execute("""
            CREATE TABLE IF NOT EXISTS znack_registration_signing_consents(
              shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,marketplace TEXT NOT NULL,
              product_id TEXT NOT NULL,variant_id TEXT NOT NULL,gtin TEXT NOT NULL,owner TEXT NOT NULL,
              enabled INTEGER NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(shop_id,marketplace,product_id,variant_id,gtin,owner))
            """);}
        column(c,"payload_sha256","TEXT NOT NULL DEFAULT ''");
        column(c,"scope_sha256","TEXT NOT NULL DEFAULT ''");
        column(c,"approval_kind","TEXT NOT NULL DEFAULT ''");
        column(c,"document_good_id","INTEGER");
        column(c,"document_sha256","TEXT NOT NULL DEFAULT ''");
    }
    private static void column(Connection c,String name,String definition)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(znack_registration_signing_consents)")){
            while(r.next())if(name.equals(r.getString("name")))return;
        }
        try(var s=c.createStatement()){s.execute("ALTER TABLE znack_registration_signing_consents ADD COLUMN "+name+" "+definition);}
    }
    /** Explicit review only: resuming an operation must not silently approve a changed payload. */
    public static void set(Shop shop,ProductKey key,String gtin,boolean enabled) { save(shop,key,gtin,enabled,null); }
    /** Explicit approval of displayed frozen XML, including adopted cards without a local payload. */
    public static void set(Shop shop,ProductKey key,String gtin,boolean enabled,ZnackNationalCatalogService.SigningDocument document) {
        if(enabled&&document==null)throw new IllegalArgumentException("CATALOG_SIGNING_REVIEW_REQUIRED");
        save(shop,key,gtin,enabled,document);
    }
    private static void save(Shop shop,ProductKey key,String gtin,boolean enabled,ZnackNationalCatalogService.SigningDocument document) {
        ListingIdentity.require(shop,key);
        try{SqliteWriteTransaction.run(c->{
            require(c,shop);
            if(!enabled){
                // Withdrawal cannot authorize anything and must also work for legacy/unbound records.
                try(var p=c.prepareStatement("UPDATE znack_registration_signing_consents SET enabled=0,updated_at=? WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND gtin=? AND owner=?")){
                    p.setString(1,Instant.now().toString());p.setInt(2,shop.getId());p.setString(3,key.marketplace().name());
                    p.setString(4,key.cardId());p.setString(5,key.variantId());p.setString(6,gtin);p.setString(7,ListingIdentity.fingerprint(shop));p.executeUpdate();
                }
                return null;
            }
            var row=current(c,shop,key,gtin);
            if(row==null)throw new IllegalArgumentException("REGISTRATION_CHANGED");
            String scope="",payload="",kind="";
            if(enabled){
                scope=scopeHash(shop,row);payload=digest(row.payload());
                if(document==null){
                    if(!validPayload(row.payload(),gtin))throw new IllegalArgumentException("CATALOG_SIGNING_REVIEW_REQUIRED");
                    kind="PAYLOAD";
                }else{
                    if(!matchesDocument(row,document))throw new IllegalArgumentException("REGISTRATION_CHANGED");
                    kind="DOCUMENT";
                }
            }
            try(var p=c.prepareStatement("""
                INSERT INTO znack_registration_signing_consents(shop_id,marketplace,product_id,variant_id,gtin,owner,
                  enabled,updated_at,payload_sha256,scope_sha256,approval_kind,document_good_id,document_sha256)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id,marketplace,product_id,variant_id,gtin,owner) DO UPDATE SET
                  enabled=excluded.enabled,updated_at=excluded.updated_at,payload_sha256=excluded.payload_sha256,
                  scope_sha256=excluded.scope_sha256,approval_kind=excluded.approval_kind,
                  document_good_id=excluded.document_good_id,document_sha256=excluded.document_sha256
                """)){
                bind(p,shop,key,gtin);p.setInt(7,enabled?1:0);p.setString(8,Instant.now().toString());
                p.setString(9,payload);p.setString(10,scope);p.setString(11,kind);
                if(enabled&&document!=null)p.setLong(12,document.goodId());
                else if(enabled&&row.goodId()!=null)p.setLong(12,row.goodId());
                else p.setNull(12,Types.BIGINT);
                p.setString(13,enabled&&document!=null?document.sha256():"");p.executeUpdate();
            }return null;
        });}catch(SQLException e){throw new IllegalStateException("REGISTRATION_STORAGE",e);}
    }
    public static boolean allowed(Shop shop,ProductKey key,String gtin) { return allowed(shop,key,gtin,null); }
    public static boolean allowed(Shop shop,ProductKey key,String gtin,ZnackNationalCatalogService.SigningDocument document) {
        ListingIdentity.require(shop,key);
        try(var c=Database.getConnection()){
            require(c,shop);var row=current(c,shop,key,gtin);if(row==null)return false;
            var grant=grant(c,shop,key,gtin);if(!valid(shop,row,grant))return false;
            return document==null||matchesDocument(row,document)&&grant.goodId()!=null
                    &&grant.goodId()==document.goodId()&&grant.documentHash().equals(document.sha256());
        }catch(SQLException e){throw new IllegalStateException("REGISTRATION_STORAGE",e);}
        catch(ZnackRegistrationScope.ScopeChangedException e){return false;}
    }
    /** Caller must first compare fresh remote business data with the unchanged approved native payload. */
    public static boolean bindVerifiedDocument(Shop shop,ProductKey key,String gtin,ZnackNationalCatalogService.SigningDocument document) {
        ListingIdentity.require(shop,key);if(document==null)return false;
        try{return SqliteWriteTransaction.run(c->{
            require(c,shop);var row=current(c,shop,key,gtin);var grant=grant(c,shop,key,gtin);
            if(row==null||!valid(shop,row,grant)||!"PAYLOAD".equals(grant.kind())||!matchesDocument(row,document))return false;
            if(!grant.documentHash().isEmpty())return grant.goodId()!=null&&grant.goodId()==document.goodId()&&grant.documentHash().equals(document.sha256());
            try(var p=c.prepareStatement("UPDATE znack_registration_signing_consents SET document_good_id=?,document_sha256=?,updated_at=? WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND gtin=? AND owner=? AND enabled=1")){
                p.setLong(1,document.goodId());p.setString(2,document.sha256());p.setString(3,Instant.now().toString());
                p.setInt(4,shop.getId());p.setString(5,key.marketplace().name());p.setString(6,key.cardId());p.setString(7,key.variantId());p.setString(8,gtin);p.setString(9,ListingIdentity.fingerprint(shop));return p.executeUpdate()==1;
            }
        });}catch(SQLException e){throw new IllegalStateException("REGISTRATION_STORAGE",e);}
        catch(ZnackRegistrationScope.ScopeChangedException e){return false;}
    }
    private record Native(String payload,Long goodId,ZnackRegistrationScope scope){}
    private record Grant(boolean enabled,String payloadHash,String scopeHash,String kind,Long goodId,String documentHash){}
    private static boolean valid(Shop shop,Native row,Grant grant){
        return grant!=null&&grant.enabled()&&("PAYLOAD".equals(grant.kind())||"DOCUMENT".equals(grant.kind()))
                &&grant.payloadHash().equals(digest(row.payload()))&&grant.scopeHash().equals(scopeHash(shop,row))
                &&(grant.goodId()==null||grant.goodId().equals(row.goodId()))
                &&(!"DOCUMENT".equals(grant.kind())||grant.goodId()!=null&&!grant.documentHash().isBlank())
                &&(!"PAYLOAD".equals(grant.kind())||!row.payload().isBlank());
    }
    private static String scopeHash(Shop shop,Native row){
        var current=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
        if(!current.equals(row.scope()))throw new ZnackRegistrationScope.ScopeChangedException();
        return digest(new Gson().toJson(current));
    }
    private static boolean matchesDocument(Native row,ZnackNationalCatalogService.SigningDocument document){ return row.goodId()!=null&&row.goodId()==document.goodId(); }
    private static Grant grant(Connection c,Shop shop,ProductKey key,String gtin)throws SQLException {
        try(var p=c.prepareStatement("SELECT enabled,payload_sha256,scope_sha256,approval_kind,document_good_id,document_sha256 FROM znack_registration_signing_consents WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND gtin=? AND owner=?")){
            bind(p,shop,key,gtin);try(var r=p.executeQuery()){if(!r.next())return null;long id=r.getLong(5);Long goodId=r.wasNull()?null:id;
                return new Grant(r.getInt(1)==1,r.getString(2),r.getString(3),r.getString(4),goodId,r.getString(6));}
        }
    }
    private static void bind(PreparedStatement p,Shop shop,ProductKey key,String gtin)throws SQLException {
        p.setInt(1,shop.getId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());p.setString(4,key.variantId());p.setString(5,gtin);p.setString(6,ListingIdentity.fingerprint(shop));
    }
    private static Native current(Connection c,Shop shop,ProductKey key,String gtin)throws SQLException {
        if(!RegisteredGtinValidator.isValid(gtin))return null;
        boolean ozon=key.marketplace()==Marketplace.OZON;
        String sql=ozon?"SELECT payload_json,good_id,scope_json,owner FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=? AND gtin=?"
                :"SELECT r.payload_json,r.good_id,s.participant_inn,s.environment,s.fingerprint,s.owner_hash FROM znack_card_registrations r JOIN znack_card_registration_scopes s ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id WHERE r.shop_id=? AND r.nm_id=? AND r.chrt_id=? AND r.gtin=?";
        try(var p=c.prepareStatement(sql)){p.setInt(1,key.shopId());p.setString(2,key.cardId());p.setString(3,key.variantId());p.setString(4,gtin);
            try(var r=p.executeQuery()){
                if(!r.next()||!ListingIdentity.fingerprint(shop).equals(r.getString(ozon?4:6)))return null;
                long id=r.getLong(2);Long goodId=r.wasNull()?null:id;
                var scope=ozon?new Gson().fromJson(r.getString(3),ZnackRegistrationScope.class):new ZnackRegistrationScope(r.getString(3),r.getString(4),r.getString(5));
                return new Native(r.getString(1)==null?"":r.getString(1),goodId,scope);
            }
        }
    }
    private static boolean validPayload(String payload,String gtin){
        try{var json=JsonParser.parseString(payload).getAsJsonObject();return gtin.equals(json.get("gtin").getAsString());}
        catch(RuntimeException invalid){return false;}
    }
    private static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void require(Connection c,Shop shop)throws SQLException {
        if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
    }
}
