package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.util.*;
import com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal;
import com.vncode.app.integration.znack.registration.v3.RegistrationJob;

/** Verifies a specific reviewed operation; it does not by itself authorize a network write. */
final class RegistrationSourceConsentGuard {
    private RegistrationSourceConsentGuard(){}

    static final class VerifiedRepairIntent {
        private final Shop shop;private final String run,intent;private final ProductSnapshot snapshot;private final List<Attribute> schema;private final JsonObject original;private final RegistrationSourceConsent.Action action;
        private VerifiedRepairIntent(Shop shop,String run,String intent,ProductSnapshot snapshot,List<Attribute> schema,JsonObject original,RegistrationSourceConsent.Action action){this.shop=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());this.run=run;this.intent=intent;this.snapshot=snapshot;this.schema=List.copyOf(schema);this.original=original.deepCopy();this.action=action;}
        String run(){return run;}String intent(){return intent;}com.vncode.app.features.products.ProductKey key(){return snapshot.key();}
        boolean matchesPayload(JsonObject payload){return payload!=null&&ListingIdentity.canonical(original).equals(ListingIdentity.canonical(payload));}
        JsonObject originalPayload(){return original.deepCopy();}
    }

    static VerifiedRepairIntent require(Connection c,Shop shop,String run,ProductSnapshot snapshot,List<Attribute> schema,
                        JsonObject payload,RegistrationSourceConsent.Action action,boolean repairPolicy)throws SQLException {
        if(run==null||run.isBlank()||snapshot==null||schema==null||payload==null||action==null)throw denied();
        var key=snapshot.key();ListingIdentity.require(shop,key);
        String owner=ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace());
        String source=RegisteredGtinProof.source(shop);
        String manual="EXISTS(SELECT 1 FROM sync_runs r JOIN sync_run_items i ON i.run_id=r.id WHERE r.id=s.run_id AND r.shop_id=s.shop_id AND r.marketplace=s.marketplace AND r.owner=s.owner AND r.kind='catalog_repair' AND r.state NOT IN ('CANCELLED','FAILED') AND i.product_id=s.product_id AND i.variant_id=s.variant_id AND i.gtin<>'' AND i.state NOT IN ('CANCELLED','FAILED'))";
        String active=action==RegistrationSourceConsent.Action.REPAIR
                ? manual
                : key.marketplace()==Marketplace.OZON
                ? "EXISTS(SELECT 1 FROM ozon_registration_batches b JOIN ozon_registration_batch_items i ON i.run_id=b.run_id WHERE b.run_id=s.run_id AND i.position=s.position AND b.shop_id=s.shop_id AND b.owner=s.owner AND b.state NOT IN "+(repairPolicy?"('CANCELLED','PAUSED')":"('CANCELLED','PAUSED','COMPLETED','FAILED')")+" AND i.product_id=s.product_id AND i.offer_id=s.variant_id AND i.state NOT IN "+(repairPolicy?"('CANCELLED')":"('CANCELLED','FAILED','SUBMITTED')")+")"
                : "EXISTS(SELECT 1 FROM znack_registration_batches b JOIN znack_registration_batch_items i ON i.run_id=b.id WHERE b.id=s.run_id AND i.position=s.position AND b.shop_id=s.shop_id AND b.owner=s.owner AND b.state NOT IN "+(repairPolicy?"('CANCELLED','PAUSED')":"('CANCELLED','PAUSED','COMPLETED','FAILED','NEEDS_ACTION')")+" AND json_valid(i.sku_json) AND CAST(json_extract(i.sku_json,'$.nmId') AS TEXT)=s.product_id AND CAST(json_extract(i.sku_json,'$.chrtId') AS TEXT)=s.variant_id AND i.state NOT IN "+(repairPolicy?"('CANCELLED')":"('CANCELLED','NEEDS_ACTION','SUBMITTED')")+")";
        if(action==RegistrationSourceConsent.Action.RESUME_GTIN)active="("+manual+" OR "+active+")";
        try(var p=c.prepareStatement("SELECT s.* FROM znack_source_consents s WHERE s.run_id=? AND s.shop_id=? AND s.marketplace=? AND s.product_id=? AND s.variant_id=? AND "+active)){
            p.setString(1,run);p.setInt(2,key.shopId());p.setString(3,key.marketplace().name());p.setString(4,key.cardId());p.setString(5,key.variantId());
            try(var r=p.executeQuery()){
                if(!r.next()||!owner.equals(r.getString("owner"))||!source.equals(r.getString("scope"))||!action.name().equals(r.getString("action"))||!(repairPolicy?Set.of("ACTIVE","ALLOCATED","SUBMITTED"):Set.of("ACTIVE","ALLOCATED")).contains(r.getString("phase")))throw denied();
                if(repairPolicy&&(!"EVIDENCE_V1".equals(r.getString("repair_policy"))||r.getInt("repair_limit")!=2))throw denied();
                if(!owner.equals(snapshot.metadata().get("owner"))||!RegistrationReviewedValues.productRevision(snapshot).equals(r.getString("source_revision"))||!RegistrationReviewedValues.schemaRevision(schema).equals(r.getString("schema_revision")))throw denied();
                String sku=snapshot.metadata().getOrDefault("sku","");if(key.marketplace()==Marketplace.OZON&&(!sku.matches("[1-9][0-9]{0,18}")||!ListingIdentity.hash(sku).equals(r.getString("sku_hash"))))throw denied();
                String gtin=text(payload,"gtin");Long goodId=positiveId(payload);
                if(action==RegistrationSourceConsent.Action.RESUME_GTIN&&(!ListingIdentity.hash(gtin).equals(r.getString("expected_gtin_hash"))||goodId!=null))throw denied();
                if(Set.of(RegistrationSourceConsent.Action.EXISTING,RegistrationSourceConsent.Action.REPAIR).contains(action)&&(!ListingIdentity.hash(gtin).equals(r.getString("expected_gtin_hash"))||goodId==null||goodId.longValue()!=r.getLong("expected_good_id")||r.wasNull()))throw denied();
                if(action==RegistrationSourceConsent.Action.CREATE||action==RegistrationSourceConsent.Action.REPLACE){
                    if(goodId!=null)throw denied();
                    if(gtin.isBlank()){if(!"ACTIVE".equals(r.getString("phase")))throw denied();}
                    else if(!(repairPolicy?Set.of("ALLOCATED","SUBMITTED"):Set.of("ALLOCATED")).contains(r.getString("phase"))||!ListingIdentity.hash(gtin).equals(r.getString("expected_gtin_hash")))throw denied();
                }
                var approved=new HashSet<String>();var images=JsonParser.parseString(r.getString("image_hashes")).getAsJsonArray();images.forEach(value->approved.add(value.getAsString()));
                if(action==RegistrationSourceConsent.Action.REPAIR){
                    var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
                    CatalogRepairImageEvidence.assertLocal(c,shop,key,ZnackRegistrationScope.fromSettings(settings),new RegistrationIntent(run,r.getInt("position")),gtin,goodId,payload);
                }else if(action==RegistrationSourceConsent.Action.RESUME_GTIN&&manualRepairRun(c,run)){
                    var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
                    CatalogRepairFeedEvidence.assertLocal(c,shop,key,ZnackRegistrationScope.fromSettings(settings),new RegistrationIntent(run,r.getInt("position")),gtin,payload);
                }else if(payload.has("good_images")){if(!payload.get("good_images").isJsonArray())throw denied();for(var value:payload.getAsJsonArray("good_images")){
                    if(!value.isJsonObject())throw denied();var image=value.getAsJsonObject();
                    if(!approved.contains(ListingIdentity.hash(text(image,"photo_url")))||!"default".equals(text(image,"photo_type"))||!"gtin".equals(text(image,"identifier_type"))||!gtin.equals(text(image,"identifier"))||image.size()!=4)throw denied();
                }}
                var normalized=payload.deepCopy();normalized.addProperty("gtin","");normalized.remove("good_id");normalized.remove("good_images");normalized.remove("_vncode_reviewed_draft");
                if(normalized.has("identified_by")){
                    if(!normalized.get("identified_by").isJsonArray())throw denied();
                    for(var entry:normalized.getAsJsonArray("identified_by")){
                        if(!entry.isJsonObject())throw denied();var identifier=entry.getAsJsonObject();
                        if("gtin".equals(text(identifier,"type"))){if(!gtin.equals(text(identifier,"value")))throw denied();identifier.addProperty("value","");}
                    }
                }
                if(!ListingIdentity.hash(ListingIdentity.canonical(normalized)).equals(r.getString("payload_hash")))throw denied();
                String intent=r.getString("intent_id");if(intent==null||intent.isBlank()||r.next())throw denied();
                return repairPolicy?new VerifiedRepairIntent(shop,run,intent,snapshot,schema,payload,action):null;
            }
        }
    }
    static void requireCurrent(Connection c,VerifiedRepairIntent evidence)throws SQLException{
        if(evidence==null)throw denied();var current=require(c,evidence.shop,evidence.run,evidence.snapshot,evidence.schema,evidence.original,evidence.action,true);
        if(!evidence.intent.equals(current.intent))throw denied();
    }
    /** Only CREATE callers may omit an optional photo when this returns false. */
    static boolean imageReviewed(Connection c,Shop shop,String run,ProductSnapshot snapshot,List<Attribute> schema,JsonObject payload,String url)throws SQLException{
        var withoutImage=payload.deepCopy();withoutImage.remove("good_images");
        var key=snapshot.key();var intent=lookupIntent(c,run,key);var sourceAction=action(c,intent,key);
        if(sourceAction!=RegistrationSourceConsent.Action.CREATE&&sourceAction!=RegistrationSourceConsent.Action.REPLACE)throw denied();
        require(c,shop,run,snapshot,schema,withoutImage,sourceAction,false);
        if(url==null||url.isBlank())return false;
        try(var p=c.prepareStatement("SELECT image_hashes FROM znack_source_consents WHERE run_id=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
            p.setString(1,run);p.setInt(2,key.shopId());p.setString(3,key.marketplace().name());p.setString(4,key.cardId());p.setString(5,key.variantId());try(var r=p.executeQuery()){
                if(!r.next())throw denied();for(var value:JsonParser.parseString(r.getString(1)).getAsJsonArray())if(ListingIdentity.hash(url).equals(value.getAsString()))return true;return false;
            }
        }
    }
    static void cancel(Connection c,String run)throws SQLException{
        try(var q=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='znack_source_consents'");var rows=q.executeQuery()){if(!rows.next())return;}
        try(var p=c.prepareStatement("UPDATE znack_source_consents SET phase='CANCELLED' WHERE run_id=? AND phase<>'CANCELLED'")){p.setString(1,run);p.executeUpdate();}
    }
    static void requirePosition(Connection c,RegistrationIntent intent,com.vncode.app.features.products.ProductKey key)throws SQLException{
        if(intent==null||key==null)throw denied();
        try(var q=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='znack_source_consents'");var r=q.executeQuery()){if(!r.next())throw denied();}
        try(var p=c.prepareStatement("SELECT 1 FROM znack_source_consents WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());try(var r=p.executeQuery()){if(!r.next())throw denied();}
        }
    }
    /** Missing consent denotes a legacy read-only job; a conflicting record is never ignored. */
    static boolean hasPositionOrLegacy(Connection c,RegistrationIntent intent,com.vncode.app.features.products.ProductKey key)throws SQLException{
        if(intent==null||key==null)throw denied();
        try(var q=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='znack_source_consents'");var r=q.executeQuery()){if(!r.next())return false;}
        try(var p=c.prepareStatement("SELECT 1 FROM znack_source_consents WHERE run_id=? AND position=?")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());try(var r=p.executeQuery()){if(!r.next())return false;}
        }
        requirePosition(c,intent,key);return true;
    }
    /** This is only a scheduling gate; each correction still requires fresh Catalog evidence. */
    static boolean hasApprovedRepair(Connection c,RegistrationIntent intent,com.vncode.app.features.products.ProductKey key)throws SQLException{
        if(!hasPositionOrLegacy(c,intent,key))return false;
        try(var p=c.prepareStatement("SELECT phase,repair_policy,repair_limit FROM znack_source_consents WHERE run_id=? AND position=?")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());try(var r=p.executeQuery()){
                return r.next()&&"SUBMITTED".equals(r.getString("phase"))&&"EVIDENCE_V1".equals(r.getString("repair_policy"))&&r.getInt("repair_limit")==2;
            }
        }
    }
    private static RegistrationIntent lookupIntent(Connection c,String run,com.vncode.app.features.products.ProductKey key)throws SQLException{
        try(var p=c.prepareStatement("SELECT position FROM znack_source_consents WHERE run_id=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
            p.setString(1,run);p.setInt(2,key.shopId());p.setString(3,key.marketplace().name());p.setString(4,key.cardId());p.setString(5,key.variantId());
            try(var r=p.executeQuery()){if(!r.next())throw denied();var intent=new RegistrationIntent(run,r.getInt(1));if(r.next())throw denied();return intent;}
        }
    }
    static RegistrationSourceConsent.Action action(Connection c,RegistrationIntent intent,com.vncode.app.features.products.ProductKey key)throws SQLException{
        requirePosition(c,intent,key);
        try(var p=c.prepareStatement("SELECT action FROM znack_source_consents WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());try(var r=p.executeQuery()){if(!r.next())throw denied();try{return RegistrationSourceConsent.Action.valueOf(r.getString(1));}catch(RuntimeException bad){throw denied();}}
        }
    }
    static String ozonConflictGtin(Connection c,RegistrationIntent intent,com.vncode.app.features.products.ProductKey key)throws SQLException {
        requirePosition(c,intent,key);RegistrationSourceConsent.ensureReplacementColumns(c);
        try(var p=c.prepareStatement("SELECT action,ozon_conflict_gtin,replaced_gtin_hash FROM znack_source_consents WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());
            try(var r=p.executeQuery()){
                if(!r.next())throw denied();String value=Objects.toString(r.getString(2),"");
                if(!value.isBlank()&&(key.marketplace()!=com.vncode.app.integration.marketplace.Marketplace.OZON||!"REPLACE".equals(r.getString(1))||!com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(value)||!ListingIdentity.hash(value).equals(r.getString(3))))throw denied();
                return value;
            }
        }
    }
    static boolean isCorrection(Connection c,RegistrationIntent intent,com.vncode.app.features.products.ProductKey key)throws SQLException{
        requirePosition(c,intent,key);try(var p=c.prepareStatement("SELECT phase FROM znack_source_consents WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());try(var r=p.executeQuery()){return r.next()&&"SUBMITTED".equals(r.getString(1));}
        }
    }
    static void requireRecoverableDraft(Connection c,Shop shop,RegistrationIntent intent,com.vncode.app.features.products.ProductKey key,JsonObject blank)throws SQLException{
        requirePosition(c,intent,key);if(!"".equals(text(blank,"gtin"))||blank.has("good_id")||blank.has("good_images"))throw denied();
        try(var p=c.prepareStatement("SELECT owner,scope,action,phase,payload_hash FROM znack_source_consents WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());try(var r=p.executeQuery()){
                if(!r.next()||!ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace()).equals(r.getString("owner"))||!RegisteredGtinProof.source(shop).equals(r.getString("scope"))||!Set.of("CREATE","REPLACE").contains(r.getString("action"))||!"ALLOCATING".equals(r.getString("phase"))||!ListingIdentity.hash(ListingIdentity.canonical(blank)).equals(r.getString("payload_hash")))throw denied();
            }
        }
    }
    /** Called in the journal's live send callback. A stale cached ACK cannot pass this claim. */
    static void claimAllocation(Connection c,Shop shop,String run,ProductSnapshot snapshot,List<Attribute> schema,JsonObject payload,
                                RegistrationJob.Owner owner,RegistrationCommandJournal.Command command)throws SQLException {
        claimAllocation(c,shop,run,snapshot,schema,payload,owner,command,null);
    }
    static void claimAllocation(Connection c,Shop shop,String run,ProductSnapshot snapshot,List<Attribute> schema,JsonObject payload,
                                RegistrationJob.Owner owner,RegistrationCommandJournal.Command command,CatalogReplacementEvidence replacement)throws SQLException {
        var intent=lookupIntent(c,run,snapshot.key());var sourceAction=action(c,intent,snapshot.key());
        if(sourceAction==RegistrationSourceConsent.Action.REPLACE){if(replacement==null)throw denied();replacement.assertCurrent(c,shop,snapshot.key(),intent);}
        else if(sourceAction!=RegistrationSourceConsent.Action.CREATE)throw denied();
        require(c,shop,run,snapshot,schema,payload,sourceAction,false);
        if(command.action()!=RegistrationJob.Action.ALLOCATE_GTIN||!owner.equals(expectedOwner(shop))||!command.resource().equals(allocationResource(snapshot.key())))throw denied();
        ensureAllocationColumns(c);
        try(var p=c.prepareStatement("SELECT 1 FROM znack_v3_dispatches WHERE owner_key=? AND action='ALLOCATE_GTIN' AND resource=? AND content_hash=? AND state='SENDING'")){
            p.setString(1,owner.key());p.setString(2,command.resource());p.setString(3,command.contentHash());try(var r=p.executeQuery()){if(!r.next())throw denied();}
        }
        var key=snapshot.key();try(var p=c.prepareStatement("UPDATE znack_source_consents SET phase='ALLOCATING',allocation_owner=?,allocation_resource=?,allocation_hash=? WHERE run_id=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND phase='ACTIVE'")){
            p.setString(1,owner.key());p.setString(2,command.resource());p.setString(3,command.contentHash());p.setString(4,run);p.setInt(5,key.shopId());p.setString(6,key.marketplace().name());p.setString(7,key.cardId());p.setString(8,key.variantId());if(p.executeUpdate()!=1)throw denied();
        }
    }
    /** The intent claim is durable before any POST; crash after this point is uncertain. */
    static void claimSubmission(Connection c,Shop shop,RegistrationIntent intent,ProductSnapshot snapshot,List<Attribute> schema,JsonObject payload)throws SQLException{
        if(intent==null)throw denied();var key=snapshot.key();var action=action(c,intent,key);
        require(c,shop,intent.runId(),snapshot,schema,payload,action,false);
        var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        RegistrationRepairConsent.grantIfApproved(c,shop,key,ZnackRegistrationScope.fromSettings(settings),intent,snapshot,schema,payload);
        ensureAllocationColumns(c);
        try(var p=c.prepareStatement("UPDATE znack_source_consents SET submission_origin=phase,submission_hash=?,phase='SUBMITTING' WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND phase IN ('ACTIVE','ALLOCATED')")){
            p.setString(1,ListingIdentity.hash(ListingIdentity.canonical(payload)));p.setString(2,intent.runId());p.setInt(3,intent.position());p.setInt(4,key.shopId());p.setString(5,key.marketplace().name());p.setString(6,key.cardId());p.setString(7,key.variantId());if(p.executeUpdate()!=1)throw denied();
        }
    }
    static void claimCorrection(Connection c,Shop shop,RegistrationIntent intent,com.vncode.app.features.products.ProductKey key,ZnackRegistrationScope scope,JsonObject payload,VerifiedRepairIntent proof)throws SQLException{
        requirePosition(c,intent,key);requireCurrent(c,proof);String gtin=text(payload,"gtin");
        if(!proof.run().equals(intent.runId())||!proof.key().equals(key)||!RegistrationRepairConsent.allowed(c,shop,key,scope,gtin,payload,proof))throw denied();
        ensureAllocationColumns(c);try(var p=c.prepareStatement("UPDATE znack_source_consents SET submission_origin=phase,submission_hash=?,phase='SUBMITTING' WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND phase='SUBMITTED'")){
            p.setString(1,ListingIdentity.hash(ListingIdentity.canonical(payload)));p.setString(2,intent.runId());p.setInt(3,intent.position());p.setInt(4,key.shopId());p.setString(5,key.marketplace().name());p.setString(6,key.cardId());p.setString(7,key.variantId());if(p.executeUpdate()!=1)throw denied();
        }
    }
    static void acceptedSubmission(Connection c,RegistrationIntent intent,com.vncode.app.features.products.ProductKey key,JsonObject payload)throws SQLException{
        requirePosition(c,intent,key);if(payload==null)throw denied();ensureAllocationColumns(c);String expected=ListingIdentity.hash(ListingIdentity.canonical(payload));
        try(var p=c.prepareStatement("SELECT phase,submission_hash FROM znack_source_consents WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());try(var r=p.executeQuery()){
                if(!r.next()||!expected.equals(r.getString("submission_hash"))||!Set.of("SUBMITTING","SUBMITTED","CANCELLED").contains(r.getString("phase")))throw denied();
                if("CANCELLED".equals(r.getString("phase"))||"SUBMITTED".equals(r.getString("phase")))return;
            }
        }
        try(var p=c.prepareStatement("UPDATE znack_source_consents SET phase='SUBMITTED' WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND phase='SUBMITTING' AND submission_hash=?")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());p.setString(7,expected);if(p.executeUpdate()!=1)throw denied();
        }
    }
    /** Only an explicit no-write response, or a failure before dispatch, permits reuse. */
    static void releaseKnownUnsent(Connection c,RegistrationIntent intent,com.vncode.app.features.products.ProductKey key,boolean allocation)throws SQLException{
        requirePosition(c,intent,key);String from=allocation?"ALLOCATING":"SUBMITTING";
        ensureAllocationColumns(c);
        try(var p=c.prepareStatement("UPDATE znack_source_consents SET phase=CASE WHEN ? THEN 'ACTIVE' ELSE submission_origin END WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND phase=?")){
            p.setBoolean(1,allocation);p.setString(2,intent.runId());p.setInt(3,intent.position());p.setInt(4,key.shopId());p.setString(5,key.marketplace().name());p.setString(6,key.cardId());p.setString(7,key.variantId());p.setString(8,from);p.executeUpdate();
        }
    }
    /** Must share the transaction persisting the native GTIN checkpoint. */
    static void bindAcknowledgedAllocation(Connection c,Shop shop,String run,com.vncode.app.features.products.ProductKey key,
                                           RegistrationJob.Owner owner,RegistrationCommandJournal.Command command,String gtin)throws SQLException {
        ListingIdentity.require(shop,key);if(run==null||run.isBlank()||!com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(gtin)||!owner.equals(expectedOwner(shop))||command.action()!=RegistrationJob.Action.ALLOCATE_GTIN||!command.resource().equals(allocationResource(key)))throw denied();
        ensureAllocationColumns(c);String receipt=RegistrationCommandJournal.acknowledged(c,owner,command).orElseThrow(RegistrationSourceConsentGuard::denied);
        if(!receipt.equals(gtin))throw denied();
        String active=key.marketplace()==Marketplace.OZON
                ? "EXISTS(SELECT 1 FROM ozon_registration_batches b JOIN ozon_registration_batch_items i ON i.run_id=b.run_id WHERE b.run_id=s.run_id AND i.position=s.position AND b.shop_id=s.shop_id AND b.owner=s.owner AND b.state NOT IN ('CANCELLED','PAUSED','COMPLETED','FAILED') AND i.product_id=s.product_id AND i.offer_id=s.variant_id AND i.state NOT IN ('CANCELLED','FAILED','SUBMITTED'))"
                : "EXISTS(SELECT 1 FROM znack_registration_batches b JOIN znack_registration_batch_items i ON i.run_id=b.id WHERE b.id=s.run_id AND i.position=s.position AND b.shop_id=s.shop_id AND b.owner=s.owner AND b.state NOT IN ('CANCELLED','PAUSED','COMPLETED','FAILED','NEEDS_ACTION') AND json_valid(i.sku_json) AND CAST(json_extract(i.sku_json,'$.nmId') AS TEXT)=s.product_id AND CAST(json_extract(i.sku_json,'$.chrtId') AS TEXT)=s.variant_id AND i.state NOT IN ('CANCELLED','NEEDS_ACTION','SUBMITTED'))";
        try(var p=c.prepareStatement("UPDATE znack_source_consents AS s SET phase='ALLOCATED',expected_gtin_hash=? WHERE run_id=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND owner=? AND scope=? AND action IN ('CREATE','REPLACE') AND phase='ALLOCATING' AND allocation_owner=? AND allocation_resource=? AND allocation_hash=? AND "+active)){
            p.setString(1,ListingIdentity.hash(gtin));p.setString(2,run);p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());p.setString(7,ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace()));p.setString(8,RegisteredGtinProof.source(shop));p.setString(9,owner.key());p.setString(10,command.resource());p.setString(11,command.contentHash());if(p.executeUpdate()!=1)throw denied();
        }
    }
    private static void ensureAllocationColumns(Connection c)throws SQLException{
        var present=new HashSet<String>();try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(znack_source_consents)")){while(r.next())present.add(r.getString("name"));}
        for(String col:List.of("allocation_owner","allocation_resource","allocation_hash","submission_origin","submission_hash"))if(!present.contains(col))try(var s=c.createStatement()){s.execute("ALTER TABLE znack_source_consents ADD COLUMN "+col+" TEXT NOT NULL DEFAULT ''");}
    }
    private static String allocationResource(com.vncode.app.features.products.ProductKey key){return key.marketplace()==Marketplace.OZON?RegistrationCommandJournal.ozonAllocationResource(key.cardId(),key.variantId()):key.cardId()+"/"+key.variantId();}
    private static RegistrationJob.Owner expectedOwner(Shop shop){
        var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var scope=ZnackRegistrationScope.fromSettings(settings);scope.assertCurrent(shop);return OzonCardRegistrationRepository.allocationOwner(shop,scope);
    }
    static boolean manualRepairRun(Connection c,String run)throws SQLException {
        try(var p=c.prepareStatement("SELECT 1 FROM sync_runs WHERE id=? AND kind='catalog_repair'")){p.setString(1,run);try(var r=p.executeQuery()){return r.next();}}
    }
    private static String text(JsonObject value,String key){return value.has(key)&&!value.get(key).isJsonNull()?value.get(key).getAsString():"";}
    private static Long positiveId(JsonObject value){if(!value.has("good_id")||value.get("good_id").isJsonNull())return null;try{long n=value.get("good_id").getAsLong();if(n>0)return n;}catch(RuntimeException ignored){}throw denied();}
    private static RegistrationValidationException denied(){return new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");}
}
