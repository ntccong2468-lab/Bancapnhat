package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;

/** Native Ozon identities are product_id + offer_id; a marketplace SKU is never a WB chrtID.
 * All remote transitions are persisted before dispatch. The active KIZ pointer changes only
 * after a signed candidate is observed on the exact owned Ozon product. */
public final class OzonCardRegistrationRepository {
    private static final Gson JSON=new GsonBuilder().registerTypeAdapter(Instant.class,(JsonSerializer<Instant>)(value,type,context)->new JsonPrimitive(value.toString())).create();
    public record Row(ProductKey key,String sku,Status status,String gtin,String activeGtin,String feedId,Long goodId,
                      String payload,String scopeJson,String owner,byte[] signatureXml,String signatureHash,
                      String signatureState,String error) {
        public Row { signatureXml=signatureXml==null?new byte[0]:signatureXml.clone(); }
        @Override public byte[] signatureXml(){return signatureXml.clone();}
        @Override public String toString(){return "OzonCardRegistrationRow[key="+key+", status="+status+"]";}
    }
    public static void initialize(Connection connection)throws SQLException {
        try(var s=connection.createStatement()){
            s.execute("""
              CREATE TABLE IF NOT EXISTS ozon_card_registrations(
                shop_id INTEGER NOT NULL,product_id TEXT NOT NULL,offer_id TEXT NOT NULL,sku TEXT NOT NULL,
                status TEXT NOT NULL,gtin TEXT NOT NULL DEFAULT '',active_gtin TEXT NOT NULL DEFAULT '',
                feed_id TEXT NOT NULL DEFAULT '',good_id INTEGER,payload_json TEXT NOT NULL DEFAULT '',
                scope_json TEXT NOT NULL,owner TEXT NOT NULL,signature_xml BLOB,signature_sha256 TEXT NOT NULL DEFAULT '',
                signature_state TEXT NOT NULL DEFAULT '',error TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
                PRIMARY KEY(shop_id,product_id,offer_id),FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
              """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_ozon_card_registrations_status ON ozon_card_registrations(shop_id,status,updated_at)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_ozon_card_registrations_gtin ON ozon_card_registrations(shop_id,gtin) WHERE gtin<>''");
            s.execute("""
              CREATE TABLE IF NOT EXISTS ozon_card_registration_history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,product_id TEXT NOT NULL,offer_id TEXT NOT NULL,
                gtin TEXT NOT NULL,payload_json TEXT NOT NULL,feed_id TEXT NOT NULL,good_id INTEGER,created_at TEXT NOT NULL,
                FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
              """);
        }
    }
    public Row find(ProductKey key){return readTransaction(c->{requireKey(c,key);return row(c,key);});}
    public Map<ProductKey,Row> findMany(List<ProductKey> keys) {
        if(keys.size()>100_000)throw new IllegalArgumentException("invalid_selection");
        if(keys.isEmpty())return Map.of();int shopId=keys.getFirst().shopId();
        if(keys.stream().anyMatch(k->k.shopId()!=shopId||k.marketplace()!=Marketplace.OZON))throw new IllegalArgumentException("mixed_shops");
        var wanted=Set.copyOf(keys);
        return readTransaction(c->{requireKey(c,keys.getFirst());var rows=new LinkedHashMap<ProductKey,Row>();
            try(var p=c.prepareStatement("SELECT * FROM ozon_card_registrations WHERE shop_id=?")){p.setInt(1,shopId);try(var r=p.executeQuery()){while(r.next()){var value=read(r);if(wanted.contains(value.key()))rows.put(value.key(),value);}}}
            return Map.copyOf(rows);
        });
    }
    public boolean claim(Shop shop,ProductKey key,String sku,String activeGtin,ZnackRegistrationScope scope,boolean newCard) {
        return claim(shop,key,sku,activeGtin,scope,newCard,"");
    }
    public boolean claim(Shop shop,ProductKey key,String sku,String activeGtin,ZnackRegistrationScope scope,boolean newCard,String reviewedTnved) {
        requireShop(shop,key);if(!sku.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("invalid_sku");
        return transaction(c->{requireCurrentOwner(c,shop,key,scope);var previous=row(c,key);String owner=ListingIdentity.fingerprint(shop);
            if(previous!=null){
                requireOwner(previous,owner,scope);
                boolean groupChange=TnvedGroupChange.replaceable(previous.status())&&!previous.feedId().isBlank()&&TnvedGroupChange.changed(TnvedGroupChange.payloadCode(previous.payload()),reviewedTnved);
                if(groupChange){if(Set.of("SENDING","RECONCILE_REQUIRED").contains(previous.signatureState()))return false;archive(c,previous);}
                else if(newCard){if(previous.status()!=Status.PUBLISHED||previous.gtin().isBlank()||!previous.activeGtin().equals(previous.gtin()))return false;
                    archive(c,previous);
                }else if(previous.status()!=Status.NOT_CREATED&&previous.status()!=Status.PREFLIGHT_ERROR)return false;
            }else if(newCard)return false;
            String active=previous!=null&&(newCard||TnvedGroupChange.changed(TnvedGroupChange.payloadCode(previous.payload()),reviewedTnved))?previous.activeGtin():activeGtin;
            try(var p=c.prepareStatement("""
              INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,active_gtin,scope_json,owner,created_at,updated_at)
              VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(shop_id,product_id,offer_id) DO UPDATE SET
              sku=excluded.sku,status=excluded.status,gtin='',active_gtin=excluded.active_gtin,feed_id='',good_id=NULL,payload_json='',
              signature_xml=NULL,signature_sha256='',signature_state='',error='',scope_json=excluded.scope_json,owner=excluded.owner,updated_at=excluded.updated_at
              """)){
                bindKey(p,key);p.setString(4,sku);p.setString(5,Status.CHECKING.name());p.setString(6,safe(active));p.setString(7,JSON.toJson(scope));p.setString(8,owner);p.setString(9,Instant.now().toString());p.setString(10,Instant.now().toString());p.executeUpdate();
            }return true;
        });
    }
    public void guard(Shop shop,ProductKey key,ZnackRegistrationScope scope) {
        requireShop(shop,key);var current=find(key);if(current==null)throw new IllegalStateException("registration_missing");requireOwner(current,ListingIdentity.fingerprint(shop),scope);scope.assertCurrent(shop);
    }
    public boolean transition(ProductKey key,Status expected,Status next) {
        return transaction(c->{requireKey(c,key);try(var p=c.prepareStatement("UPDATE ozon_card_registrations SET status=?,updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=? AND status=?")){
            p.setString(1,next.name());p.setString(2,Instant.now().toString());p.setInt(3,key.shopId());p.setString(4,key.cardId());p.setString(5,key.variantId());p.setString(6,expected.name());return p.executeUpdate()==1;
        }});
    }
    public void generated(ProductKey key,String gtin,String payload) {
        if(!RegisteredGtinValidator.isValid(gtin)||payload.length()>2*1024*1024)throw new IllegalArgumentException("invalid_catalog_draft");
        update(key,"UPDATE ozon_card_registrations SET gtin=?,payload_json=?,status='READY_TO_SUBMIT',error='',updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=? AND status='ALLOCATING_GTIN'",p->{p.setString(1,gtin);p.setString(2,payload);p.setString(3,Instant.now().toString());bindKey(p,4,key);});
    }
    public void submitted(ProductKey key,String feedId) {
        if(feedId==null||feedId.isBlank()||feedId.length()>200)throw new IllegalArgumentException("invalid_feed_id");
        update(key,"UPDATE ozon_card_registrations SET feed_id=?,status='FEED_SUBMITTED',updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=? AND status='FEED_SUBMITTING'",p->{p.setString(1,feedId);p.setString(2,Instant.now().toString());bindKey(p,3,key);});
    }
    public void progress(ProductKey key,Status status,Long goodId) {
        progress(key,status,goodId,"");
    }
    public void progress(ProductKey key,Status status,Long goodId,String error) {
        if(!Set.of(Status.PROCESSING,Status.READY_TO_SIGN,Status.ERROR,Status.SIGNING).contains(status))throw new IllegalArgumentException("invalid_progress");
        update(key,"UPDATE ozon_card_registrations SET status=?,good_id=COALESCE(?,good_id),error=?,updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=? AND feed_id<>''",p->{p.setString(1,status.name());if(goodId==null)p.setNull(2,Types.BIGINT);else p.setLong(2,goodId);p.setString(3,CatalogFeedback.display(error));p.setString(4,Instant.now().toString());bindKey(p,5,key);});
    }
    public void prepareSignature(ProductKey key,ZnackNationalCatalogService.SigningDocument document) {
        if(document.xml().length>4*1024*1024)throw new IllegalArgumentException("signing_document_too_large");
        update(key,"UPDATE ozon_card_registrations SET signature_xml=?,signature_sha256=?,signature_state='PREPARED',good_id=?,updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=? AND status='READY_TO_SIGN' AND signature_state IN ('','PREPARED')",p->{p.setBytes(1,document.xml());p.setString(2,document.sha256());p.setLong(3,document.goodId());p.setString(4,Instant.now().toString());bindKey(p,5,key);});
    }
    public boolean claimSignature(ProductKey key,String digest) {
        return transaction(c->{requireKey(c,key);try(var p=c.prepareStatement("UPDATE ozon_card_registrations SET status='SIGNING',signature_state='SENDING',updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=? AND status='READY_TO_SIGN' AND signature_state='PREPARED' AND signature_sha256=?")){
            p.setString(1,Instant.now().toString());bindKey(p,2,key);p.setString(5,digest);return p.executeUpdate()==1;
        }});
    }
    public void uncertain(ProductKey key,Status status) {
        update(key,"UPDATE ozon_card_registrations SET status=?,signature_state=CASE WHEN signature_state='SENDING' THEN 'RECONCILE_REQUIRED' ELSE signature_state END,error='readback_required',updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=?",p->{p.setString(1,status.name());p.setString(2,Instant.now().toString());bindKey(p,3,key);});
    }
    public void releaseSignature(ProductKey key,String digest) {
        update(key,"UPDATE ozon_card_registrations SET status='READY_TO_SIGN',signature_state='PREPARED',updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=? AND signature_state='SENDING' AND signature_sha256=?",p->{p.setString(1,Instant.now().toString());bindKey(p,2,key);p.setString(5,digest);});
    }
    public void publish(Shop shop,ProductKey key,ZnackRegistrationScope scope,long goodId) {
        guard(shop,key,scope);String source=RegisteredGtinProof.source(shop);
        transaction(c->{requireCurrentOwner(c,shop,key,scope);var row=row(c,key);requireOwner(row,ListingIdentity.fingerprint(shop),scope);
            if(row.feedId().isBlank()||!RegisteredGtinValidator.isValid(row.gtin())||goodId<=0||(row.goodId()!=null&&row.goodId()!=goodId))throw new IllegalStateException("catalog_identity_changed");
            var payload=JsonParser.parseString(row.payload()).getAsJsonObject();
            try(var p=c.prepareStatement("UPDATE ozon_card_registrations SET status='PUBLISHED',good_id=?,signature_state=CASE WHEN signature_state='' THEN '' ELSE 'CONFIRMED' END,error='',updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=?")){p.setLong(1,goodId);p.setString(2,Instant.now().toString());bindKey(p,3,key);p.executeUpdate();}
            // Feed signing proves this trade-unit GTIN. Attribute-name enrichment comes from
            // the subsequent owner registry refresh; category-specific IDs are not guessed.
            var observation=new RegisteredGtin(row.gtin(),"","","",true,true,source,Instant.now());
            try(var p=c.prepareStatement("INSERT INTO gtin_sync_catalog VALUES(?,?,?,?) ON CONFLICT(shop_id,marketplace,gtin) DO UPDATE SET data_json=excluded.data_json")){p.setInt(1,key.shopId());p.setString(2,Marketplace.OZON.name());p.setString(3,row.gtin());p.setString(4,JSON.toJson(observation));p.executeUpdate();}
            try(var p=c.prepareStatement("""
              INSERT INTO znack_products(shop_id,gtin,product_name,tn_ved,card_status,synced_at)
              VALUES(?,?,?,?,'published',?) ON CONFLICT(shop_id,gtin) DO UPDATE SET product_name=excluded.product_name,tn_ved=excluded.tn_ved,card_status=excluded.card_status,synced_at=excluded.synced_at
              """)){p.setInt(1,key.shopId());p.setString(2,row.gtin());p.setString(3,safeText(payload,"good_name"));p.setString(4,safeText(payload,"tnved"));p.setString(5,Instant.now().toString());p.executeUpdate();}
            com.vncode.app.features.sync.SyncStreamStore.request(c,shop.getId(),"ZNACK");
            return null;
        });
    }
    public boolean complete(Shop shop,ProductKey key,ZnackRegistrationScope scope,String expectedGtin) {
        guard(shop,key,scope);
        RegisteredGtinProof.requireCurrent(shop,expectedGtin);
        return transaction(c->{requireCurrentOwner(c,shop,key,scope);var row=row(c,key);requireOwner(row,ListingIdentity.fingerprint(shop),scope);
            if(row.status()!=Status.PUBLISHED||!row.gtin().equals(expectedGtin))return false;
            try(var p=c.prepareStatement("INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(shop_id,sku) DO UPDATE SET gtin=excluded.gtin,updated_at=excluded.updated_at")){p.setInt(1,key.shopId());p.setString(2,row.sku());p.setString(3,expectedGtin);p.setString(4,Instant.now().toString());p.setString(5,Instant.now().toString());p.executeUpdate();}
            try(var p=c.prepareStatement("INSERT INTO gtin_sync_mappings VALUES(?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,product_id,variant_id) DO UPDATE SET gtin=excluded.gtin,updated_at=excluded.updated_at")){p.setInt(1,key.shopId());p.setString(2,Marketplace.OZON.name());p.setString(3,key.cardId());p.setString(4,key.variantId());p.setString(5,expectedGtin);p.setString(6,Instant.now().toString());p.executeUpdate();}
            try(var p=c.prepareStatement("UPDATE ozon_card_registrations SET active_gtin=gtin,updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=?")){p.setString(1,Instant.now().toString());bindKey(p,2,key);p.executeUpdate();}
            return true;
        });
    }
    public Set<String> claimedGtins(){return readTransaction(c->{var result=new HashSet<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT gtin FROM ozon_card_registrations WHERE gtin<>'' UNION SELECT active_gtin FROM ozon_card_registrations WHERE active_gtin<>'' UNION SELECT gtin FROM znack_card_registrations WHERE gtin IS NOT NULL AND gtin<>'' UNION SELECT active_gtin FROM znack_card_registration_replacements WHERE active_gtin<>'' UNION SELECT gtin FROM znack_card_registration_history WHERE gtin<>'' UNION SELECT gtin FROM ozon_card_registration_history WHERE gtin<>''")){while(r.next())result.add(r.getString(1));}return Set.copyOf(result);});}
    private static void archive(Connection c,Row row)throws SQLException{try(var p=c.prepareStatement("INSERT INTO ozon_card_registration_history(shop_id,product_id,offer_id,gtin,payload_json,feed_id,good_id,created_at) VALUES(?,?,?,?,?,?,?,?)")){bindKey(p,row.key());p.setString(4,row.gtin());p.setString(5,row.payload());p.setString(6,row.feedId());if(row.goodId()==null)p.setNull(7,Types.BIGINT);else p.setLong(7,row.goodId());p.setString(8,Instant.now().toString());p.executeUpdate();}}
    private static String safeText(JsonObject object,String field){return object.has(field)&&object.get(field).isJsonPrimitive()?object.get(field).getAsString():"";}
    private static String safe(String value){return value==null?"":value;}
    private static void requireShop(Shop shop,ProductKey key){ListingIdentity.require(shop,key);if(shop.getMarketplace()!=Marketplace.OZON)throw new IllegalArgumentException("shop_mismatch");}
    private static void requireOwner(Row row,String owner,ZnackRegistrationScope scope){if(row==null||!row.owner().equals(owner)||!row.scopeJson().equals(JSON.toJson(scope)))throw new ZnackRegistrationScope.ScopeChangedException();}
    private static void requireCurrentOwner(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope)throws SQLException {
        requireKey(c,key);
        try(var p=c.prepareStatement("SELECT api_key,client_id FROM shops WHERE id=?")){p.setInt(1,shop.getId());try(var r=p.executeQuery()){if(!r.next()||!Objects.equals(shop.getApiKey(),r.getString(1))||!Objects.equals(shop.getClientId(),r.getString(2)))throw new ZnackRegistrationScope.ScopeChangedException();}}
        try(var p=c.prepareStatement("SELECT true_api_base_url,participant_inn,owner_inn,signer_certificate,certificate_metadata_json,signer_tested_at FROM znack_settings WHERE shop_id=?")){
            p.setInt(1,shop.getId());try(var r=p.executeQuery()){if(!r.next())throw new ZnackRegistrationScope.ScopeChangedException();
                var settings=new com.vncode.app.integration.znack.ZnackModels.Settings(r.getString(1),"","","",r.getString(2),"",r.getString(3),"",r.getString(4),"[]","","","",false,"","[]",r.getString(5),r.getString(6)==null?null:Instant.parse(r.getString(6)));
                if(!scope.equals(ZnackRegistrationScope.fromSettings(settings)))throw new ZnackRegistrationScope.ScopeChangedException();
            }
        }
    }
    private static void requireKey(Connection c,ProductKey key)throws SQLException{if(key.marketplace()!=Marketplace.OZON||!key.cardId().matches("[1-9][0-9]{0,18}")||key.variantId().isBlank())throw new IllegalArgumentException("invalid_ozon_product");try(var p=c.prepareStatement("SELECT marketplace FROM shops WHERE id=?")){p.setInt(1,key.shopId());try(var r=p.executeQuery()){if(!r.next()||!Marketplace.OZON.name().equals(r.getString(1)))throw new IllegalArgumentException("shop_mismatch");}}}
    private static Row row(Connection c,ProductKey key)throws SQLException{try(var p=c.prepareStatement("SELECT * FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=?")){bindKey(p,key);try(var r=p.executeQuery()){return r.next()?read(r):null;}}}
    private static Row read(ResultSet r)throws SQLException{long good=r.getLong("good_id");Long goodId=r.wasNull()?null:good;return new Row(new ProductKey(r.getInt("shop_id"),Marketplace.OZON,r.getString("product_id"),r.getString("offer_id")),r.getString("sku"),Status.valueOf(r.getString("status")),r.getString("gtin"),r.getString("active_gtin"),r.getString("feed_id"),goodId,r.getString("payload_json"),r.getString("scope_json"),r.getString("owner"),r.getBytes("signature_xml"),r.getString("signature_sha256"),r.getString("signature_state"),r.getString("error"));}
    private static void bindKey(PreparedStatement p,ProductKey key)throws SQLException{bindKey(p,1,key);}
    private static void bindKey(PreparedStatement p,int start,ProductKey key)throws SQLException{p.setInt(start,key.shopId());p.setString(start+1,key.cardId());p.setString(start+2,key.variantId());}
    @FunctionalInterface private interface Bind{void bind(PreparedStatement p)throws SQLException;}
    private static void update(ProductKey key,String sql,Bind bind){transaction(c->{requireKey(c,key);try(var p=c.prepareStatement(sql)){bind.bind(p);if(p.executeUpdate()!=1)throw new IllegalStateException("registration_changed");}return null;});}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private static <T>T readTransaction(Work<T> work){try(var c=Database.getConnection()){c.setAutoCommit(false);try{T value=work.run(c);c.commit();return value;}catch(Exception error){c.rollback();throw error;}}catch(SQLException error){throw new IllegalStateException("ozon_registration_storage_error");}}
    private static <T>T transaction(Work<T> work){try(var c=Database.getConnection();var s=c.createStatement()){s.execute("BEGIN IMMEDIATE");try{T value=work.run(c);s.execute("COMMIT");return value;}catch(Exception error){s.execute("ROLLBACK");throw error;}}catch(SQLException error){throw new IllegalStateException("ozon_registration_storage_error");}}
}
