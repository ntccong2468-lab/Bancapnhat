package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.models.Shop;
import java.sql.*;
import com.vncode.app.integration.listing.ListingIdentity;
import java.net.URI;
import java.util.*;

/** Exact, re-readable Catalog image evidence for editing an existing card. */
final class CatalogRepairImageEvidence {
    private static final Set<String> TYPES=Set.of("default","7","19","13","si1","si2","si3","si4","si5","3ds","marketing","text","ecommerce");
    record Images(JsonArray values,String hash){
        Images {values=values.deepCopy();}
        @Override public JsonArray values(){return values.deepCopy();}
    }
    static Images fromCatalog(JsonObject card,String gtin,long goodId){
        if(card==null||goodId<=0||!card.has("good_id")||card.get("good_id").isJsonNull())throw unavailable();
        try{if(card.get("good_id").getAsLong()!=goodId)throw unavailable();}catch(RuntimeException invalid){throw unavailable();}
        boolean matched=card.has("gtin")&&!card.get("gtin").isJsonNull()&&RegisteredGtinProof.sameCode(gtin,card.get("gtin").getAsString());
        if(card.has("identified_by")&&card.get("identified_by").isJsonArray())for(var entry:card.getAsJsonArray("identified_by"))if(entry.isJsonObject()){
            var id=entry.getAsJsonObject();if("gtin".equalsIgnoreCase(text(id,"type"))&&RegisteredGtinProof.sameCode(gtin,text(id,"value")))matched=true;
        }
        if(!matched)throw unavailable();
        if(!card.has("good_images")||!card.get("good_images").isJsonArray())throw unavailable();
        var images=new JsonArray();
        for(var value:card.getAsJsonArray("good_images")){
            if(!value.isJsonObject())throw unavailable();var source=value.getAsJsonObject();
            String type=text(source,"photo_type");type=switch(type){case "left"->"7";case "right"->"19";case "back"->"13";default->type;};
            if(!TYPES.contains(type)||!source.has("photo_url"))throw unavailable();var url=source.get("photo_url");
            if(url.isJsonPrimitive()&&url.getAsJsonPrimitive().isString())validateUrl(url.getAsString());
            else if(type.equals("3ds")&&url.isJsonArray()&&!url.getAsJsonArray().isEmpty())for(var item:url.getAsJsonArray()){if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString())throw unavailable();validateUrl(item.getAsString());}
            else throw unavailable();
            var image=new JsonObject();image.addProperty("photo_type",type);image.add("photo_url",url.deepCopy());
            String identifier=text(source,"identifier"),identifierType=text(source,"identifier_type"),barcode=text(source,"barcode");
            if(!identifier.isBlank()){
                if(!"gtin".equals(identifierType)||!RegisteredGtinProof.sameCode(gtin,identifier))throw unavailable();
                image.addProperty("identifier",identifier);image.addProperty("identifier_type",identifierType);
            }else if(!identifierType.isBlank())throw unavailable();
            else if(!barcode.isBlank()){
                if(!RegisteredGtinProof.sameCode(gtin,barcode))throw unavailable();image.addProperty("identifier",barcode);image.addProperty("identifier_type","gtin");
            }
            images.add(image);
        }
        return new Images(images,ListingIdentity.hash(ListingIdentity.canonical(images)));
    }
    static void verifyFresh(ZnackCardRegistrationWorkflow.CatalogSession session,String gtin,long goodId,String expectedHash)throws Exception {
        if(session==null||session.scope()==null||expectedHash==null||expectedHash.isBlank())throw unavailable();
        var fresh=session.catalog().rejectedCard(session.token(),gtin,goodId);
        if(!expectedHash.equals(fromCatalog(fresh,gtin,goodId).hash()))throw unavailable();
        if(!session.catalog().verifiedRejectedCardOwner(session.token(),gtin,goodId,session.scope().inn(),()->{},fresh))throw new RegistrationValidationException("CATALOG_OWNER_UNCONFIRMED");
    }
    /** A review proof contains hashes only and is tied to the exact manual repair and native payload. */
    static void store(Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,String gtin,long goodId,JsonObject payload,Images images){
        if(intent==null||scope==null||images==null||!images.hash().equals(payloadImageHash(payload)))throw unavailable();
        try{SqliteWriteTransaction.run(c->{checkLocal(c,shop,key,scope,intent,gtin,goodId,payload);
            try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS catalog_repair_image_proofs(run_id TEXT NOT NULL,position INTEGER NOT NULL,shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,product_id TEXT NOT NULL,variant_id TEXT NOT NULL,owner TEXT NOT NULL,scope_hash TEXT NOT NULL,gtin TEXT NOT NULL,good_id INTEGER NOT NULL,payload_hash TEXT NOT NULL,image_hash TEXT NOT NULL,PRIMARY KEY(run_id,position,shop_id,marketplace,product_id,variant_id))");}
            try(var p=c.prepareStatement("INSERT INTO catalog_repair_image_proofs VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(run_id,position,shop_id,marketplace,product_id,variant_id) DO UPDATE SET owner=excluded.owner,scope_hash=excluded.scope_hash,gtin=excluded.gtin,good_id=excluded.good_id,payload_hash=excluded.payload_hash,image_hash=excluded.image_hash")){
                bind(p,shop,key,scope,intent,gtin,goodId,payload);p.setString(12,images.hash());p.executeUpdate();
            }return null;
        });}catch(SQLException e){throw new IllegalStateException("SYNC_STORAGE",e);}
    }
    /** Called after source/cross-market guards, immediately before the non-idempotent POST. */
    static void verifyBeforeSubmit(Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,String gtin,long goodId,JsonObject payload,ZnackCardRegistrationWorkflow.CatalogSession session)throws Exception {
        if(intent==null||scope==null||session==null||!scope.equals(session.scope()))throw unavailable();String hash;
        try(var c=Database.getConnection()){hash=assertLocal(c,shop,key,scope,intent,gtin,goodId,payload);}
        catch(SQLException error){throw unavailable();}
        if(!hash.equals(payloadImageHash(payload)))throw unavailable();
        verifyFresh(session,gtin,goodId,hash);scope.assertCurrent(shop);ListingIdentity.require(shop,key);
    }
    static String assertLocal(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,String gtin,long goodId,JsonObject payload)throws SQLException {
        checkLocal(c,shop,key,scope,intent,gtin,goodId,payload);
        try(var p=c.prepareStatement("SELECT image_hash FROM catalog_repair_image_proofs WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND owner=? AND scope_hash=? AND gtin=? AND good_id=? AND payload_hash=?")){
            bind(p,shop,key,scope,intent,gtin,goodId,payload);try(var r=p.executeQuery()){if(!r.next())throw unavailable();String hash=r.getString(1);if(r.next()||!hash.equals(payloadImageHash(payload)))throw unavailable();return hash;}
        }
    }
    private static void checkLocal(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,String gtin,long goodId,JsonObject payload)throws SQLException {
        ListingIdentity.require(shop,key);scope.assertCurrent(shop);
        if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace()))||goodId<=0)throw unavailable();
        try(var p=c.prepareStatement("SELECT 1 FROM sync_runs r JOIN sync_run_items i ON i.run_id=r.id WHERE r.id=? AND r.shop_id=? AND r.marketplace=? AND r.owner=? AND r.kind='catalog_repair' AND r.state NOT IN ('CANCELLED','FAILED') AND i.product_id=? AND i.variant_id=? AND i.gtin=? AND i.state NOT IN ('CANCELLED','FAILED')")){
            p.setString(1,intent.runId());p.setInt(2,shop.getId());p.setString(3,key.marketplace().name());p.setString(4,ListingIdentity.fingerprint(shop));p.setString(5,key.cardId());p.setString(6,key.variantId());p.setString(7,gtin);try(var r=p.executeQuery()){if(!r.next())throw unavailable();}
        }
        String nativeSql=key.marketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON
                ?"SELECT payload_json FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=? AND gtin=? AND good_id=? AND status IN ('READY_TO_SUBMIT','FEED_SUBMITTING') AND NULLIF(feed_id,'') IS NULL"
                :"SELECT payload_json FROM znack_card_registrations WHERE shop_id=? AND nm_id=? AND chrt_id=? AND gtin=? AND good_id=? AND status IN ('READY_TO_SUBMIT','FEED_SUBMITTING') AND NULLIF(feed_id,'') IS NULL";
        try(var p=c.prepareStatement(nativeSql)){p.setInt(1,shop.getId());p.setString(2,key.cardId());p.setString(3,key.variantId());p.setString(4,gtin);p.setLong(5,goodId);
            try(var r=p.executeQuery()){if(!r.next()||!ListingIdentity.hash(payload.toString()).equals(ListingIdentity.hash(r.getString(1))))throw unavailable();}
        }
    }
    private static void bind(PreparedStatement p,Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,String gtin,long goodId,JsonObject payload)throws SQLException {
        p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,shop.getId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());p.setString(7,ListingIdentity.fingerprint(shop));p.setString(8,ListingIdentity.hash(new Gson().toJson(scope)));p.setString(9,gtin);p.setLong(10,goodId);p.setString(11,ListingIdentity.hash(payload.toString()));
    }
    private static String payloadImageHash(JsonObject payload){
        if(payload==null)throw unavailable();JsonArray images;
        if(!payload.has("good_images"))images=new JsonArray();
        else if(payload.get("good_images").isJsonArray())images=payload.getAsJsonArray("good_images");else throw unavailable();
        return ListingIdentity.hash(ListingIdentity.canonical(images));
    }
    private static void validateUrl(String value){try{var uri=URI.create(value);if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT))||uri.getHost()==null||uri.getUserInfo()!=null)throw unavailable();}catch(IllegalArgumentException e){throw unavailable();}}
    private static String text(JsonObject object,String name){return object.has(name)&&object.get(name).isJsonPrimitive()&&object.getAsJsonPrimitive(name).isString()?object.get(name).getAsString():"";}
    private static RegistrationValidationException unavailable(){return new RegistrationValidationException("CATALOG_EXISTING_IMAGES_NOT_VERIFIED");}
    private CatalogRepairImageEvidence(){}
}
