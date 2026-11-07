package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Immutable seller review, made durable only with its exact queued operation. */
public final class RegistrationSourceConsent {
    public enum Action { CREATE, RESUME_GTIN, EXISTING, REPAIR, REPLACE }
    public record Target(Action action,String gtin,Long goodId,boolean ozonConflict){
        public Target(Action action,String gtin,Long goodId){this(action,gtin,goodId,false);}
        public Target {
            Objects.requireNonNull(action);gtin=Objects.toString(gtin,"").strip();
            boolean valid=switch(action){case CREATE->gtin.isBlank()&&goodId==null;case RESUME_GTIN->RegisteredGtinValidator.isValid(gtin)&&goodId==null;case EXISTING,REPAIR,REPLACE->RegisteredGtinValidator.isValid(gtin)&&goodId!=null&&goodId>0;};
            if(!valid||ozonConflict&&action!=Action.REPLACE)throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");
        }
    }
    public static Target forIdentity(String gtin,Long goodId){
        String value=Objects.toString(gtin,"").strip();return new Target(value.isBlank()?Action.CREATE:goodId==null?Action.RESUME_GTIN:Action.EXISTING,value,goodId);
    }
    private record Item(ProductKey key,Action action,String sourceRevision,String schemaRevision,String payloadHash,String skuHash,String gtinHash,Long goodId,String ozonConflictGtin,Set<String> imageHashes,List<RegistrationFieldProvenance.Evidence> fields){}
    public static final class Prepared {
        private final UUID intent;private final String owner,scope;private final List<Item> items;private final Map<ProductKey,Action> actions;private final boolean repairApproved;
        private Prepared(UUID intent,String owner,String scope,List<Item> items,boolean repairApproved){this.intent=intent;this.owner=owner;this.scope=scope;this.items=List.copyOf(items);var byKey=new HashMap<ProductKey,Action>();for(var item:items)byKey.put(item.key(),item.action());this.actions=Map.copyOf(byKey);this.repairApproved=repairApproved;}
        public UUID intent(){return intent;}
        public Set<ProductKey> keys(){return items.stream().map(Item::key).collect(java.util.stream.Collectors.toUnmodifiableSet());}
        public Action action(ProductKey key){var action=actions.get(key);if(action==null)throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");return action;}
        @Override public String toString(){return "RegistrationSourceConsent[redacted,items="+items.size()+"]";}
    }
    private static final Gson JSON=new Gson();
    private RegistrationSourceConsent(){}

    /** Drop already queued targets without changing any reviewed value or granting a new action. */
    static Prepared restrict(Prepared prepared,Set<ProductKey> keys){
        if(prepared==null||keys.isEmpty()||!prepared.keys().containsAll(keys))throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");
        return new Prepared(prepared.intent,prepared.owner,prepared.scope,prepared.items.stream().filter(item->keys.contains(item.key())).toList(),prepared.repairApproved);
    }

    /** Call only after the seller reviewed these exact variant values and visible images. */
    public static Prepared prepare(Shop shop,UUID intent,List<RegistrationDynamicPreflight.Plan> plans,
                                   Map<ProductKey,Target> targets,Map<ProductKey,Set<String>> reviewedImages,
                                   Map<ProductKey,List<RegistrationFieldProvenance.Evidence>> reviewedFields,boolean repairApproved){
        Objects.requireNonNull(intent);ListingIdentity.require(shop);String owner=ListingIdentity.fingerprint(shop),scope=RegisteredGtinProof.source(shop);
        if(plans.isEmpty()||plans.size()>100000)throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");
        var items=new ArrayList<Item>(plans.size());var seen=new HashSet<ProductKey>();
        var currentFields=RegistrationFieldProvenance.readBatch(shop,plans);
        for(var plan:plans){var snapshot=plan.snapshot();var key=snapshot.key();var target=targets.get(key);
            if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace()||!owner.equals(snapshot.metadata().get("owner"))||!seen.add(key)||target==null||target.ozonConflict()&&key.marketplace()!=Marketplace.OZON)throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");
            if(snapshot.variants().stream().filter(variant->key.variantId().equals(variant.id())).limit(2).count()!=1)throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");
            String sku=snapshot.metadata().getOrDefault("sku","");if(key.marketplace()==Marketplace.OZON&&!sku.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");
            var media=new HashSet<String>();for(var source:snapshot.media())if(source.uri()!=null)media.add(ListingIdentity.hash(source.uri().toString()));
            var approved=new TreeSet<String>();for(String url:reviewedImages.getOrDefault(key,Set.of())){String hash=ListingIdentity.hash(url);if(!media.contains(hash))throw new IllegalArgumentException("ZNACK_SOURCE_IMAGE_REVIEW_REQUIRED");approved.add(hash);}
            var shown=List.copyOf(reviewedFields.getOrDefault(key,List.of()));var current=currentFields.getOrDefault(key,List.of());
            if(shown.isEmpty()||!shown.equals(current))throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");
            String payload=ZnackNationalCatalogService.buildPayload("",plan.draft(),"").toString();
            items.add(new Item(key,target.action(),RegistrationReviewedValues.productRevision(snapshot),RegistrationReviewedValues.schemaRevision(plan.schema()),ListingIdentity.hash(ListingIdentity.canonical(JsonParser.parseString(payload))),sku.isBlank()?"":ListingIdentity.hash(sku),target.gtin().isBlank()?"":ListingIdentity.hash(target.gtin()),target.goodId(),target.ozonConflict()?target.gtin():"",Set.copyOf(approved),shown));
        }
        if(!seen.equals(targets.keySet())||!seen.containsAll(reviewedImages.keySet())||!seen.equals(reviewedFields.keySet()))throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");
        return new Prepared(intent,owner,scope,items,repairApproved);
    }

    /** Must be called within the transaction creating the same run and its exact items. */
    public static void persist(Connection c,Shop shop,String runId,List<ProductKey> expected,Prepared prepared)throws SQLException{
        persist(c,shop,runId,expected,prepared,0);
    }
    static void persist(Connection c,Shop shop,String runId,List<ProductKey> expected,Prepared prepared,int offset)throws SQLException{
        if(offset<0||offset>100000)throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");
        Objects.requireNonNull(c);Objects.requireNonNull(prepared);if(runId==null||runId.isBlank()||runId.length()>200)throw new IllegalStateException("ZNACK_SOURCE_REVIEW_REQUIRED");
        String owner=ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace());
        if(!prepared.owner.equals(owner)||!prepared.scope.equals(RegisteredGtinProof.source(shop)))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");
        var keys=new LinkedHashSet<>(expected);if(keys.size()!=expected.size()||keys.size()!=prepared.items.size()||!keys.equals(prepared.items.stream().map(Item::key).collect(java.util.stream.Collectors.toSet())))throw new IllegalArgumentException("ZNACK_SOURCE_REVIEW_REQUIRED");
        var positions=new HashMap<ProductKey,Integer>();for(int index=0;index<expected.size();index++)positions.put(expected.get(index),Math.addExact(offset,index));
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS znack_source_consents(intent_id TEXT NOT NULL,run_id TEXT NOT NULL,position INTEGER NOT NULL,shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,product_id TEXT NOT NULL,variant_id TEXT NOT NULL,owner TEXT NOT NULL,scope TEXT NOT NULL,action TEXT NOT NULL,source_revision TEXT NOT NULL,schema_revision TEXT NOT NULL,payload_hash TEXT NOT NULL,sku_hash TEXT NOT NULL,expected_gtin_hash TEXT NOT NULL,expected_good_id INTEGER,image_hashes TEXT NOT NULL,repair_policy TEXT NOT NULL,repair_limit INTEGER NOT NULL,phase TEXT NOT NULL,approved_at TEXT NOT NULL,PRIMARY KEY(intent_id,shop_id,marketplace,product_id,variant_id),UNIQUE(run_id,shop_id,marketplace,product_id,variant_id),UNIQUE(run_id,position))");
            s.execute("CREATE TABLE IF NOT EXISTS znack_source_consent_fields(intent_id TEXT NOT NULL,run_id TEXT NOT NULL,shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,product_id TEXT NOT NULL,variant_id TEXT NOT NULL,field TEXT NOT NULL,value_hash TEXT NOT NULL,source TEXT NOT NULL,source_value_hash TEXT NOT NULL,dictionary_hash TEXT NOT NULL,observed_at TEXT NOT NULL,dictionary_observed_at TEXT NOT NULL,PRIMARY KEY(intent_id,shop_id,marketplace,product_id,variant_id,field))");
        }
        ensureReplacementColumns(c);
        try(var p=c.prepareStatement("INSERT INTO znack_source_consents(intent_id,run_id,position,shop_id,marketplace,product_id,variant_id,owner,scope,action,source_revision,schema_revision,payload_hash,sku_hash,expected_gtin_hash,expected_good_id,image_hashes,repair_policy,repair_limit,phase,approved_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'ACTIVE',?)")){
            int count=0;for(var item:prepared.items){var key=item.key();p.setString(1,prepared.intent.toString());p.setString(2,runId);p.setInt(3,positions.get(key));p.setInt(4,key.shopId());p.setString(5,key.marketplace().name());p.setString(6,key.cardId());p.setString(7,key.variantId());p.setString(8,owner);p.setString(9,prepared.scope);p.setString(10,item.action().name());p.setString(11,item.sourceRevision());p.setString(12,item.schemaRevision());p.setString(13,item.payloadHash());p.setString(14,item.skuHash());p.setString(15,item.gtinHash());if(item.goodId()==null)p.setNull(16,Types.BIGINT);else p.setLong(16,item.goodId());p.setString(17,JSON.toJson(new TreeSet<>(item.imageHashes())));p.setString(18,prepared.repairApproved?"EVIDENCE_V1":"");p.setInt(19,prepared.repairApproved?2:0);p.setString(20,Instant.now().toString());p.addBatch();if(++count%1000==0)p.executeBatch();}if(count%1000!=0)p.executeBatch();
        }
        try(var p=c.prepareStatement("INSERT INTO znack_source_consent_fields(intent_id,run_id,shop_id,marketplace,product_id,variant_id,field,value_hash,source,source_value_hash,dictionary_hash,observed_at,dictionary_observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")){
            int count=0;for(var item:prepared.items)for(var field:item.fields()){var key=item.key();p.setString(1,prepared.intent.toString());p.setString(2,runId);p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());p.setString(7,field.field());p.setString(8,field.valueHash());p.setString(9,field.source().name());p.setString(10,field.sourceValueHash());p.setString(11,field.dictionaryHash());p.setString(12,field.observedAt().toString());p.setString(13,field.dictionaryObservedAt()==null?"":field.dictionaryObservedAt().toString());p.addBatch();if(++count%1000==0)p.executeBatch();}if(count%1000!=0)p.executeBatch();
        }
        // Keep the previously reviewed identity separately: allocation later binds a
        // new GTIN to expected_gtin_hash, which must not erase the old-card proof.
        try(var p=c.prepareStatement("UPDATE znack_source_consents SET replaced_gtin_hash=expected_gtin_hash,replaced_good_id=expected_good_id,expected_gtin_hash='',expected_good_id=NULL WHERE run_id=? AND intent_id=? AND action='REPLACE'")){
            p.setString(1,runId);p.setString(2,prepared.intent.toString());p.executeUpdate();
        }
        try(var p=c.prepareStatement("UPDATE znack_source_consents SET ozon_conflict_gtin=? WHERE run_id=? AND position=? AND action='REPLACE' AND marketplace='OZON'")){
            for(var item:prepared.items)if(!item.ozonConflictGtin().isBlank()) {p.setString(1,item.ozonConflictGtin());p.setString(2,runId);p.setInt(3,positions.get(item.key()));if(p.executeUpdate()!=1)throw new SQLException("ZNACK_SOURCE_REVIEW_REQUIRED");}
        }
    }

    static void ensureReplacementColumns(Connection c)throws SQLException{
        var present=new HashSet<String>();try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(znack_source_consents)")){while(r.next())present.add(r.getString("name"));}
        if(!present.contains("replaced_gtin_hash"))try(var s=c.createStatement()){s.execute("ALTER TABLE znack_source_consents ADD COLUMN replaced_gtin_hash TEXT NOT NULL DEFAULT ''");}
        if(!present.contains("replaced_good_id"))try(var s=c.createStatement()){s.execute("ALTER TABLE znack_source_consents ADD COLUMN replaced_good_id INTEGER");}
        if(!present.contains("ozon_conflict_gtin"))try(var s=c.createStatement()){s.execute("ALTER TABLE znack_source_consents ADD COLUMN ozon_conflict_gtin TEXT NOT NULL DEFAULT ''");}
    }
}
