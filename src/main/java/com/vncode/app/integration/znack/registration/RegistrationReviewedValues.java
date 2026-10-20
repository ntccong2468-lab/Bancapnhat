package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.products.*;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;

/** Seller-approved Catalog fields are usable only for the same owner, product facts and schema. */
public final class RegistrationReviewedValues {
    public record Value(String text,String type){public Value{text=RegistrationTemplate.clean(text);type=type==null?null:RegistrationTemplate.clean(type);}}
    public record Approval(ProductSnapshot snapshot,List<Attribute> schema,Map<Long,Value> values,String permitRevision,long categoryId){public Approval(ProductSnapshot snapshot,List<Attribute> schema,Map<Long,Value> values,String permitRevision){this(snapshot,schema,values,permitRevision,0);}public Approval(ProductSnapshot snapshot,List<Attribute> schema,Map<Long,Value> values){this(snapshot,schema,values,"",0);}public Approval{schema=List.copyOf(schema);values=Map.copyOf(values);}}
    private static final Gson JSON=new Gson();
    private RegistrationReviewedValues(){}
    public static void ensureTables(Connection connection)throws SQLException{
        try(var statement=connection.createStatement()){statement.execute("""
            CREATE TABLE IF NOT EXISTS znack_registration_reviewed_values(
              shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,product_id TEXT NOT NULL,variant_id TEXT NOT NULL,
              owner TEXT NOT NULL,scope TEXT NOT NULL,product_revision TEXT NOT NULL,schema_revision TEXT NOT NULL,
              values_json TEXT NOT NULL,approved_at TEXT NOT NULL,permit_revision TEXT NOT NULL DEFAULT '',category_id INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(shop_id,marketplace,product_id,variant_id),
              FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
            """);}
        boolean hasPermit=false,hasCategory=false;try(var statement=connection.createStatement();var columns=statement.executeQuery("PRAGMA table_info(znack_registration_reviewed_values)")){while(columns.next()){if("permit_revision".equals(columns.getString("name")))hasPermit=true;if("category_id".equals(columns.getString("name")))hasCategory=true;}}
        if(!hasPermit)try(var statement=connection.createStatement()){statement.execute("ALTER TABLE znack_registration_reviewed_values ADD COLUMN permit_revision TEXT NOT NULL DEFAULT ''");}
        if(!hasCategory)try(var statement=connection.createStatement()){statement.execute("ALTER TABLE znack_registration_reviewed_values ADD COLUMN category_id INTEGER NOT NULL DEFAULT 0");}
    }
    public static void save(Shop shop,List<Approval> approvals){
        String owner=ListingIdentity.fingerprint(shop),scope=RegisteredGtinProof.source(shop);
        for(var approval:approvals){ListingIdentity.require(shop,approval.snapshot().key());validate(approval.schema(),approval.values());}
        try{SqliteWriteTransaction.run(connection->{
            ListingIdentity.require(shop);if(!scope.equals(RegisteredGtinProof.source(shop)))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");ensureTables(connection);
            try(var statement=connection.prepareStatement("INSERT INTO znack_registration_reviewed_values VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,product_id,variant_id) DO UPDATE SET owner=excluded.owner,scope=excluded.scope,product_revision=excluded.product_revision,schema_revision=excluded.schema_revision,values_json=excluded.values_json,approved_at=excluded.approved_at,permit_revision=excluded.permit_revision,category_id=excluded.category_id")){
                for(var approval:approvals){var key=approval.snapshot().key();statement.setInt(1,key.shopId());statement.setString(2,key.marketplace().name());statement.setString(3,key.cardId());statement.setString(4,key.variantId());statement.setString(5,owner);statement.setString(6,scope);statement.setString(7,productRevision(approval.snapshot()));statement.setString(8,schemaRevision(approval.schema()));statement.setString(9,JSON.toJson(approval.values()));statement.setString(10,Instant.now().toString());statement.setString(11,approval.permitRevision());statement.setLong(12,approval.categoryId());statement.addBatch();}statement.executeBatch();
            }return null;
        });}catch(SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}
    }
    public static Map<Long,Value> load(Shop shop,ProductSnapshot snapshot,List<Attribute> schema){
        return load(shop,snapshot,schema,0);
    }
    private static Map<Long,Value> load(Shop shop,ProductSnapshot snapshot,List<Attribute> schema,long categoryId){
        ListingIdentity.require(shop,snapshot.key());String owner=ListingIdentity.fingerprint(shop),scope=RegisteredGtinProof.source(shop);
        try(var connection=Database.getConnection()){
            try(var exists=connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='znack_registration_reviewed_values'");var found=exists.executeQuery()){if(!found.next())return Map.of();}
            try(var statement=connection.prepareStatement("SELECT owner,scope,product_revision,schema_revision,values_json,category_id FROM znack_registration_reviewed_values WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
                var key=snapshot.key();statement.setInt(1,key.shopId());statement.setString(2,key.marketplace().name());statement.setString(3,key.cardId());statement.setString(4,key.variantId());
                try(var row=statement.executeQuery()){
                    if(!row.next()||!owner.equals(row.getString(1))||!scope.equals(row.getString(2)))return Map.of();
                    if(!productRevision(snapshot).equals(row.getString(3))||!schemaRevision(schema).equals(row.getString(4))||categoryId>0&&row.getLong(6)>0&&categoryId!=row.getLong(6))throw new RegistrationValidationException("ZNACK_REVIEWED_VALUES_CHANGED");
                    var values=new LinkedHashMap<Long,Value>();JsonParser.parseString(row.getString(5)).getAsJsonObject().entrySet().forEach(entry->values.put(Long.parseLong(entry.getKey()),JSON.fromJson(entry.getValue(),Value.class)));validate(schema,values);return Map.copyOf(values);
                }
            }
        }catch(SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}
    }
    /** An advisory can be retained only after explicit review of these exact papers/facts. */
    public static ZnackRegistrationDocuments.Selection reviewedDocuments(Shop shop,ProductSnapshot snapshot,ZnackRegistrationDocuments.Selection selected){
        if(selected.automatic()!=null||selected.candidates().size()!=1||selected.issues().isEmpty()||selected.issues().stream().anyMatch(issue->!RegistrationPermitValidator.advisory(issue)))return selected;
        ListingIdentity.require(shop,snapshot.key());
        try(var connection=Database.getConnection()){
            try(var exists=connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='znack_registration_reviewed_values'");var found=exists.executeQuery()){if(!found.next())return selected;}
            try(var statement=connection.prepareStatement("SELECT owner,scope,product_revision,permit_revision FROM znack_registration_reviewed_values WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
                var key=snapshot.key();statement.setInt(1,key.shopId());statement.setString(2,key.marketplace().name());statement.setString(3,key.cardId());statement.setString(4,key.variantId());
                try(var row=statement.executeQuery()){if(row.next()&&ListingIdentity.fingerprint(shop).equals(row.getString(1))&&RegisteredGtinProof.source(shop).equals(row.getString(2))&&productRevision(snapshot).equals(row.getString(3))&&permitRevision(selected).equals(row.getString(4)))return new ZnackRegistrationDocuments.Selection(selected.candidates(),selected.candidates().getFirst(),selected.source(),false,selected.issues());}
            }
        }catch(SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}return selected;
    }
    public static String permitRevision(ZnackRegistrationDocuments.Selection selected){return ListingIdentity.hash(JSON.toJson(selected.candidates())+"\n"+JSON.toJson(selected.issues()));}
    public static Optional<Category> reviewedCategory(Shop shop,ProductSnapshot snapshot,List<Category> candidates){
        ListingIdentity.require(shop,snapshot.key());try(var connection=Database.getConnection()){
            try(var exists=connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='znack_registration_reviewed_values'");var found=exists.executeQuery()){if(!found.next())return Optional.empty();}
            try(var statement=connection.prepareStatement("SELECT owner,scope,product_revision,category_id FROM znack_registration_reviewed_values WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
                var key=snapshot.key();statement.setInt(1,key.shopId());statement.setString(2,key.marketplace().name());statement.setString(3,key.cardId());statement.setString(4,key.variantId());
                try(var row=statement.executeQuery()){if(row.next()&&ListingIdentity.fingerprint(shop).equals(row.getString(1))&&RegisteredGtinProof.source(shop).equals(row.getString(2))&&productRevision(snapshot).equals(row.getString(3))){long id=row.getLong(4);return candidates.stream().filter(c->c.id()==id).findFirst();}}
            }
        }catch(SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}return Optional.empty();
    }
    public static Draft apply(Shop shop,ProductSnapshot snapshot,Draft draft,List<Attribute> schema){return apply(draft,load(shop,snapshot,schema,draft.categoryId()));}
    public static Draft apply(Draft draft,Map<Long,Value> reviewed){
        var values=new LinkedHashMap<>(draft.attributes());var types=new LinkedHashMap<>(draft.attributeTypes());
        reviewed.forEach((id,value)->{values.put(id,value.text());if(value.type()!=null)types.put(id,value.type());});
        return new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),values.getOrDefault(2478L,draft.goodName()),values.getOrDefault(2504L,draft.brand()),values,types,draft.autoSign(),draft.isKit());
    }
    public static void validate(List<Attribute> schema,Map<Long,Value> values){
        var all=new HashMap<Long,Attribute>();var pending=new ArrayDeque<>(schema);while(!pending.isEmpty()){var a=pending.remove();if(all.putIfAbsent(a.id(),a)!=null)continue;a.dependencies().values().forEach(pending::addAll);}
        for(var entry:values.entrySet()){
            var attribute=all.get(entry.getKey());var value=entry.getValue();
            if(attribute==null||Set.of(3959L,13933L,23557L,23561L).contains(entry.getKey())||value==null||value.text().isBlank()||value.text().length()>5000||attribute.presetOnly()&&!attribute.presets().contains(value.text())||!RegistrationAttributeRules.validFieldValue(attribute,value.text())||value.type()==null||!attribute.valueTypes().isEmpty()&&!attribute.valueTypes().contains(value.type()))throw new RegistrationValidationException("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
        }
    }
    public static String productRevision(ProductSnapshot snapshot){
        var json=ProductJson.GSON.toJsonTree(snapshot).getAsJsonObject();json.remove("revision");json.remove("checkedAt");json.remove("metadata");json.addProperty("sourceCategory",snapshot.metadata().getOrDefault("source.category.name",""));
        for(var document:json.getAsJsonArray("documents")){var object=document.getAsJsonObject();object.remove("checkedAt");object.remove("source");object.remove("metadata");}
        return ListingIdentity.hash(ListingIdentity.canonical(json));
    }
    public static String schemaRevision(List<Attribute> schema){return ListingIdentity.hash(ListingIdentity.canonical(JSON.toJsonTree(schema)));}
}
