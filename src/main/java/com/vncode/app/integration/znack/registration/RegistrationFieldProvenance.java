package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;

/** Local origin of a proposed value, not evidence that Catalog accepted it or that it is true. */
public final class RegistrationFieldProvenance {
    public enum Source { MARKETPLACE_UNVERIFIED, MARKETPLACE_MAPPED_UNVERIFIED, SHOP_CONFIGURATION, SELLER_INPUT_UNCONFIRMED, INFERRED_UNVERIFIED, UNKNOWN }
    public record Evidence(String field,String valueHash,Source source,Instant observedAt,String sourceValueHash,String dictionaryHash,Instant dictionaryObservedAt) {
        public Evidence(String field,String valueHash,Source source,Instant observedAt){this(field,valueHash,source,observedAt,"","",null);}
        public Evidence {
            Objects.requireNonNull(field);Objects.requireNonNull(valueHash);Objects.requireNonNull(source);Objects.requireNonNull(observedAt);
            sourceValueHash=Objects.toString(sourceValueHash,"");dictionaryHash=Objects.toString(dictionaryHash,"");
            if(!field.matches("(?:product\\.(?:name|brand|article|tnved|material|gender|origin|document)|variant\\.(?:color|size|russian_size)|catalog\\.(?:category|attribute\\.[0-9]+))")
                    ||!valueHash.matches("(?:|[a-f0-9]{64})")||!sourceValueHash.matches("(?:|[a-f0-9]{64})")||!dictionaryHash.matches("(?:|[a-f0-9]{64})")
                    ||source!=Source.UNKNOWN&&valueHash.isEmpty())throw new IllegalArgumentException("REGISTRATION_PROVENANCE_INVALID");
            if(source==Source.MARKETPLACE_MAPPED_UNVERIFIED?(sourceValueHash.isEmpty()||dictionaryHash.isEmpty()||dictionaryObservedAt==null):(!sourceValueHash.isEmpty()||!dictionaryHash.isEmpty()||dictionaryObservedAt!=null))throw new IllegalArgumentException("REGISTRATION_PROVENANCE_INVALID");
        }
    }
    /** Input and output hashes plus exact in-operation dictionary evidence; never raw goods data. */
    record CountryMapping(String inputHash,String outputHash,String dictionaryHash,Instant dictionaryAt,String schemaRevision) {}
    public record Prepared(ProductKey key,String productRevision,String schemaRevision,String draftHash,List<Evidence> fields) {
        public Prepared { Objects.requireNonNull(key);Objects.requireNonNull(productRevision);Objects.requireNonNull(schemaRevision);Objects.requireNonNull(draftHash);fields=List.copyOf(fields);
            if(!productRevision.matches("[a-f0-9]{64}")||!schemaRevision.matches("[a-f0-9]{64}")||!draftHash.matches("[a-f0-9]{64}")||fields.size()>5000||fields.stream().map(Evidence::field).distinct().count()!=fields.size())throw new IllegalArgumentException("REGISTRATION_PROVENANCE_INVALID"); }
        @Override public String toString(){return "RegistrationFieldProvenance[key="+key+", fields="+fields.size()+"]";}
    }
    private static final Gson JSON=new Gson();
    private RegistrationFieldProvenance(){}

    /** Values are compared exactly; an inferred, transformed or ambiguous value remains UNKNOWN. */
    public static Prepared capture(ProductSnapshot original,ProductSnapshot prepared,Draft draft,List<Attribute> schema,
                                   Map<Long,RegistrationReviewedValues.Value> entered,
                                   ZnackRegistrationDocuments.Selection documents,boolean categoryEntered) {
        return capture(original,prepared,draft,schema,entered,documents,categoryEntered,Map.of());
    }
    public static Prepared capture(ProductSnapshot original,ProductSnapshot prepared,Draft draft,List<Attribute> schema,
                                   Map<Long,RegistrationReviewedValues.Value> entered,
                                   ZnackRegistrationDocuments.Selection documents,boolean categoryEntered,Map<Long,String> inferred) {
        return capture(original,prepared,draft,schema,entered,documents,categoryEntered,inferred,null);
    }
    static Prepared capture(ProductSnapshot original,ProductSnapshot prepared,Draft draft,List<Attribute> schema,
                                   Map<Long,RegistrationReviewedValues.Value> entered,
                                   ZnackRegistrationDocuments.Selection documents,boolean categoryEntered,Map<Long,String> inferred,CountryMapping mapping) {
        Objects.requireNonNull(original);Objects.requireNonNull(prepared);Objects.requireNonNull(draft);
        if(!original.key().equals(prepared.key()))throw new SecurityException("SOURCE_PRODUCT_NOT_OBSERVED");
        entered=entered==null?Map.of():Map.copyOf(entered);inferred=inferred==null?Map.of():Map.copyOf(inferred);var values=new LinkedHashMap<String,Evidence>();
        var selected=original.variants().stream().filter(v->original.key().variantId().equals(v.id())).toList();
        ProductVariant variant=selected.size()==1?selected.getFirst():null;Instant at=original.checkedAt();
        add(values,"product.name",draft.goodName(),enteredSource(entered,2478,draft.goodName(),match(draft.goodName(),original.title())),at);
        Source brand=match(draft.brand(),original.brand());
        if(brand==Source.UNKNOWN&&original.key().marketplace()==Marketplace.OZON&&blank(original.brand())
                &&same(draft.brand(),prepared.brand())&&!blank(prepared.brand()))brand=Source.SHOP_CONFIGURATION;
        add(values,"product.brand",draft.brand(),enteredSource(entered,2504,draft.brand(),brand),at);
        add(values,"product.article",variant==null?"":variant.sellerArticle(),variant==null||blank(variant.sellerArticle())?Source.UNKNOWN:Source.MARKETPLACE_UNVERIFIED,at);
        add(values,"variant.color",variant==null?"":variant.color(),variant==null||blank(variant.color())?Source.UNKNOWN:Source.MARKETPLACE_UNVERIFIED,at);
        add(values,"variant.size",variant==null?"":variant.size(),variant==null||blank(variant.size())?Source.UNKNOWN:Source.MARKETPLACE_UNVERIFIED,at);
        add(values,"variant.russian_size",variant==null?"":variant.russianSize(),variant==null||blank(variant.russianSize())?Source.UNKNOWN:Source.MARKETPLACE_UNVERIFIED,at);
        var tnved=ProductFieldNames.sourceAttributes("tnved",original.attributes()).stream().flatMap(a->a.values().stream()).map(ProductFieldNames::tnvedCode).filter(v->!v.isBlank()).distinct().toList();
        add(values,"product.tnved",draft.tnved(),tnved.size()==1?match(draft.tnved(),tnved.getFirst()):Source.UNKNOWN,at);
        String material=Objects.toString(draft.attributes().get(2483L),"");
        add(values,"product.material",material,enteredSource(entered,2483,material,sourceMatch(original,"material",material)),at);
        String gender=Objects.toString(draft.attributes().get(14013L),"");
        Source genderSource=sourceMatch(original,"gender",gender);
        if(genderSource==Source.UNKNOWN&&!blank(gender)&&same(gender,inferred.get(14013L)))genderSource=Source.INFERRED_UNVERIFIED;
        add(values,"product.gender",gender,enteredSource(entered,14013,gender,genderSource),at);
        String origin=Objects.toString(draft.attributes().get(2630L),"");
        var countries=original.attributes().stream().filter(a->a.complexId()==null||a.complexId().isBlank()||a.complexId().equals("0")).filter(a->RegistrationVerifiedDefaults.productionCountryField(a.name())).flatMap(a->a.values().stream()).filter(v->!blank(v)).distinct().toList();
        Source country=countries.size()==1?match(origin,countries.getFirst()):Source.UNKNOWN;
        if(country==Source.UNKNOWN&&countries.size()==1&&mapping!=null&&!blank(origin)
                &&mapping.inputHash().equals(ListingIdentity.hash(countries.getFirst().strip()))
                &&mapping.outputHash().equals(ListingIdentity.hash(origin.strip()))
                &&mapping.schemaRevision().equals(RegistrationReviewedValues.schemaRevision(schema)))country=Source.MARKETPLACE_MAPPED_UNVERIFIED;
        country=enteredSource(entered,2630,origin,country);
        if(country==Source.MARKETPLACE_MAPPED_UNVERIFIED)addMapped(values,"product.origin",origin,at,mapping);else add(values,"product.origin",origin,country,at);
        String doc=documents==null||documents.automatic()==null?"":JSON.toJson(documents.automatic());
        Source document=blank(doc)?Source.UNKNOWN:documents.source()==ZnackRegistrationDocuments.Source.SHOP?Source.SHOP_CONFIGURATION:documents.source()==ZnackRegistrationDocuments.Source.PRODUCT?Source.MARKETPLACE_UNVERIFIED:Source.UNKNOWN;
        add(values,"product.document",doc,document,at);
        add(values,"catalog.category",Long.toString(draft.categoryId()),categoryEntered?Source.SELLER_INPUT_UNCONFIRMED:Source.UNKNOWN,at);
        var allAttributes=new LinkedHashMap<Long,Attribute>();schema.forEach(a->allAttributes.put(a.id(),a));
        RegistrationAttributeRules.required(schema,draft.attributes()).forEach(a->allAttributes.put(a.id(),a));
        for(var attribute:allAttributes.values()){long id=attribute.id();String value=Objects.toString(draft.attributes().get(id),id==2478?draft.goodName():id==2504?draft.brand():"");
            Source originSource=enteredSource(entered,id,value,sourceAttributeMatch(original,attribute,value));
            if(id==2478)originSource=values.get("product.name").source();if(id==2504)originSource=values.get("product.brand").source();
            if(id==2483)originSource=values.get("product.material").source();if(id==2630)originSource=values.get("product.origin").source();if(id==14013)originSource=values.get("product.gender").source();
            if(id==35&&variant!=null&&same(value,variant.size()))originSource=enteredSource(entered,id,value,Source.MARKETPLACE_UNVERIFIED);
            if(id==36&&variant!=null&&same(value,variant.color()))originSource=enteredSource(entered,id,value,Source.MARKETPLACE_UNVERIFIED);
            if(id==2630&&originSource==Source.MARKETPLACE_MAPPED_UNVERIFIED)addMapped(values,"catalog.attribute."+id,value,at,mapping);else add(values,"catalog.attribute."+id,value,originSource,at);
        }
        String revision=RegistrationReviewedValues.productRevision(prepared);
        String draftHash=ListingIdentity.hash(ListingIdentity.canonical(JSON.toJsonTree(draft)));
        return new Prepared(prepared.key(),revision,RegistrationReviewedValues.schemaRevision(schema),draftHash,List.copyOf(values.values()));
    }

    /** Save an entire successful preflight in one local transaction; no request or secret is persisted. */
    public static void save(Shop shop,List<Prepared> prepared){
        if(prepared.isEmpty())return;ListingIdentity.require(shop);String owner=ListingIdentity.fingerprint(shop),scope=RegisteredGtinProof.source(shop);
        for(var value:prepared)if(value.key().shopId()!=shop.getId()||value.key().marketplace()!=shop.getMarketplace())throw new SecurityException("Foreign listing owner");
        try{SqliteWriteTransaction.run(c->{
            if(!owner.equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace()))||!scope.equals(RegisteredGtinProof.source(shop)))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");
            ensure(c);
            try(var p=c.prepareStatement("INSERT INTO znack_registration_field_provenance(shop_id,marketplace,product_id,variant_id,owner,scope,product_revision,schema_revision,draft_hash,field,value_hash,source,observed_at,source_value_hash,dictionary_hash,dictionary_observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,product_id,variant_id,owner,scope,product_revision,schema_revision,draft_hash,field) DO UPDATE SET value_hash=excluded.value_hash,source=excluded.source,observed_at=excluded.observed_at,source_value_hash=excluded.source_value_hash,dictionary_hash=excluded.dictionary_hash,dictionary_observed_at=excluded.dictionary_observed_at")){
                int queued=0;
                for(var record:prepared)for(var field:record.fields()){
                    var key=record.key();p.setInt(1,key.shopId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());p.setString(4,key.variantId());p.setString(5,owner);p.setString(6,scope);p.setString(7,record.productRevision());p.setString(8,record.schemaRevision());p.setString(9,record.draftHash());p.setString(10,field.field());p.setString(11,field.valueHash());p.setString(12,field.source().name());p.setString(13,field.observedAt().toString());p.setString(14,field.sourceValueHash());p.setString(15,field.dictionaryHash());p.setString(16,field.dictionaryObservedAt()==null?"":field.dictionaryObservedAt().toString());p.addBatch();
                    if(++queued%1000==0)p.executeBatch();
                }if(queued%1000!=0)p.executeBatch();
            }return null;
        });}catch(SQLException failure){throw new IllegalStateException("REGISTRATION_PROVENANCE_STORAGE",failure);}
    }

    /** Read only evidence for these exact current facts; historical or foreign evidence never authorizes a write. */
    public static List<Evidence> read(Shop shop,Prepared current){
        ListingIdentity.require(shop,current.key());String owner=ListingIdentity.fingerprint(shop),scope=RegisteredGtinProof.source(shop);
        try(var c=Database.getConnection()){
            try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='znack_registration_field_provenance'");var r=p.executeQuery()){if(!r.next())return List.of();}
            try(var p=c.prepareStatement("SELECT field,value_hash,source,observed_at,source_value_hash,dictionary_hash,dictionary_observed_at FROM znack_registration_field_provenance WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND owner=? AND scope=? AND product_revision=? AND schema_revision=? AND draft_hash=? ORDER BY field")){
                var key=current.key();p.setInt(1,key.shopId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());p.setString(4,key.variantId());p.setString(5,owner);p.setString(6,scope);p.setString(7,current.productRevision());p.setString(8,current.schemaRevision());p.setString(9,current.draftHash());var out=new ArrayList<Evidence>();
                try(var r=p.executeQuery()){while(r.next())out.add(new Evidence(r.getString(1),r.getString(2),Source.valueOf(r.getString(3)),Instant.parse(r.getString(4)),r.getString(5),r.getString(6),r.getString(7).isBlank()?null:Instant.parse(r.getString(7))));}return List.copyOf(out);
            }
        }catch(SQLException failure){throw new IllegalStateException("REGISTRATION_PROVENANCE_STORAGE",failure);}
    }
    public static List<Evidence> read(Shop shop,ProductSnapshot snapshot,Draft draft,List<Attribute> schema){
        Objects.requireNonNull(snapshot);Objects.requireNonNull(draft);Objects.requireNonNull(schema);
        return read(shop,new Prepared(snapshot.key(),RegistrationReviewedValues.productRevision(snapshot),RegistrationReviewedValues.schemaRevision(schema),
                ListingIdentity.hash(ListingIdentity.canonical(JSON.toJsonTree(draft))),List.of()));
    }
    /** Read one consistent SQLite snapshot for a reviewed selection without a connection per variant. */
    public static Map<ProductKey,List<Evidence>> readBatch(Shop shop,List<RegistrationDynamicPreflight.Plan> plans){
        Objects.requireNonNull(shop);Objects.requireNonNull(plans);
        if(plans.size()>100000)throw new IllegalArgumentException("REGISTRATION_PROVENANCE_INVALID");
        if(plans.isEmpty())return Map.of();
        ListingIdentity.require(shop);String owner=ListingIdentity.fingerprint(shop),scope=RegisteredGtinProof.source(shop);
        var wanted=new LinkedHashMap<ProductKey,List<Evidence>>();
        for(var plan:plans){
            Objects.requireNonNull(plan);var key=plan.snapshot().key();
            if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace())throw new SecurityException("Foreign listing owner");
            if(wanted.putIfAbsent(key,new ArrayList<>())!=null)throw new IllegalArgumentException("REGISTRATION_PROVENANCE_INVALID");
        }
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);
            if(!owner.equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");
            try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='znack_registration_field_provenance'");var r=p.executeQuery()){
                if(!r.next()){
                    if(!scope.equals(RegisteredGtinProof.source(shop)))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");
                    c.commit();var empty=new LinkedHashMap<ProductKey,List<Evidence>>();wanted.keySet().forEach(key->empty.put(key,List.of()));return Collections.unmodifiableMap(empty);
                }
            }
            try(var s=c.createStatement()){s.execute("CREATE TEMP TABLE provenance_requested(product_id TEXT NOT NULL,variant_id TEXT NOT NULL,product_revision TEXT NOT NULL,schema_revision TEXT NOT NULL,draft_hash TEXT NOT NULL,PRIMARY KEY(product_id,variant_id))");}
            try(var p=c.prepareStatement("INSERT INTO provenance_requested VALUES(?,?,?,?,?)")){
                int count=0;for(var plan:plans){var snapshot=plan.snapshot();p.setString(1,snapshot.key().cardId());p.setString(2,snapshot.key().variantId());
                    p.setString(3,RegistrationReviewedValues.productRevision(snapshot));p.setString(4,RegistrationReviewedValues.schemaRevision(plan.schema()));
                    p.setString(5,ListingIdentity.hash(ListingIdentity.canonical(JSON.toJsonTree(plan.draft()))));p.addBatch();if(++count%1000==0)p.executeBatch();
                }if(count%1000!=0)p.executeBatch();
            }
            try(var p=c.prepareStatement("""
                SELECT p.product_id,p.variant_id,p.field,p.value_hash,p.source,p.observed_at,p.source_value_hash,p.dictionary_hash,p.dictionary_observed_at
                FROM provenance_requested q JOIN znack_registration_field_provenance p
                ON p.product_id=q.product_id AND p.variant_id=q.variant_id AND p.product_revision=q.product_revision
                AND p.schema_revision=q.schema_revision AND p.draft_hash=q.draft_hash
                WHERE p.shop_id=? AND p.marketplace=? AND p.owner=? AND p.scope=? ORDER BY p.product_id,p.variant_id,p.field
                """)){
                p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());p.setString(3,owner);p.setString(4,scope);
                try(var r=p.executeQuery()){while(r.next()){
                    var key=new ProductKey(shop.getId(),shop.getMarketplace(),r.getString(1),r.getString(2));
                    var timestamp=r.getString(9);wanted.get(key).add(new Evidence(r.getString(3),r.getString(4),Source.valueOf(r.getString(5)),Instant.parse(r.getString(6)),r.getString(7),r.getString(8),timestamp.isBlank()?null:Instant.parse(timestamp)));
                }}
            }
            if(!owner.equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace()))||!scope.equals(RegisteredGtinProof.source(shop)))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");
            c.commit();var result=new LinkedHashMap<ProductKey,List<Evidence>>();wanted.forEach((key,fields)->result.put(key,List.copyOf(fields)));return Collections.unmodifiableMap(result);
        }catch(SQLException failure){throw new IllegalStateException("REGISTRATION_PROVENANCE_STORAGE",failure);}
    }
    private static void ensure(Connection c)throws SQLException {try(var s=c.createStatement()){s.execute("""
        CREATE TABLE IF NOT EXISTS znack_registration_field_provenance(
        shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,product_id TEXT NOT NULL,variant_id TEXT NOT NULL,
        owner TEXT NOT NULL,scope TEXT NOT NULL,product_revision TEXT NOT NULL,schema_revision TEXT NOT NULL,draft_hash TEXT NOT NULL,
        field TEXT NOT NULL,value_hash TEXT NOT NULL,source TEXT NOT NULL,observed_at TEXT NOT NULL,
        source_value_hash TEXT NOT NULL DEFAULT '',dictionary_hash TEXT NOT NULL DEFAULT '',dictionary_observed_at TEXT NOT NULL DEFAULT '',
        PRIMARY KEY(shop_id,marketplace,product_id,variant_id,owner,scope,product_revision,schema_revision,draft_hash,field),
        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
        """);}}
    static CountryMapping countryMapping(ProductSnapshot original,Draft draft,List<Attribute> schema,ZnackNationalCatalogService catalog){
        var raw=original.attributes().stream().filter(a->a.complexId()==null||a.complexId().isBlank()||a.complexId().equals("0"))
                .filter(a->RegistrationVerifiedDefaults.productionCountryField(a.name())).flatMap(a->a.values().stream())
                .filter(v->!blank(v)).map(String::strip).distinct().toList();
        String output=Objects.toString(draft.attributes().get(2630L),"").strip();if(raw.size()!=1||output.isBlank())return null;
        if(!output.equals(catalog.registrationCountryCode(raw.getFirst(),schema)))return null;
        var evidence=catalog.currentCountryDictionaryEvidence().orElse(null);
        if(evidence==null||evidence.contentHash()==null||!evidence.contentHash().matches("[a-f0-9]{64}")||evidence.loadedAt()==null)return null;
        return new CountryMapping(ListingIdentity.hash(raw.getFirst()),ListingIdentity.hash(output),evidence.contentHash(),evidence.loadedAt(),RegistrationReviewedValues.schemaRevision(schema));
    }
    private static void add(Map<String,Evidence> target,String field,String value,Source source,Instant at){String clean=Objects.toString(value,"").strip();target.put(field,new Evidence(field,clean.isBlank()?"":ListingIdentity.hash(clean),clean.isBlank()?Source.UNKNOWN:source,at));}
    private static void addMapped(Map<String,Evidence> target,String field,String value,Instant at,CountryMapping mapping){target.put(field,new Evidence(field,ListingIdentity.hash(value.strip()),Source.MARKETPLACE_MAPPED_UNVERIFIED,at,mapping.inputHash(),mapping.dictionaryHash(),mapping.dictionaryAt()));}
    private static Source enteredSource(Map<Long,RegistrationReviewedValues.Value> entered,long id,String value,Source otherwise){var item=entered.get(id);return item!=null&&same(item.text(),value)&&!blank(value)?Source.SELLER_INPUT_UNCONFIRMED:otherwise;}
    private static Source sourceAttributeMatch(ProductSnapshot snapshot,Attribute target,String value){if(blank(value))return Source.UNKNOWN;var matches=snapshot.attributes().stream().filter(a->a.complexId()==null||a.complexId().isBlank()||a.complexId().equals("0")).filter(a->same(a.name(),target.name())).flatMap(a->a.values().stream()).filter(v->!blank(v)).distinct().toList();return matches.size()==1?match(value,matches.getFirst()):Source.UNKNOWN;}
    private static Source sourceMatch(ProductSnapshot snapshot,String field,String value){var matches=ProductFieldNames.sourceAttributes(field,snapshot.attributes()).stream().flatMap(a->a.values().stream()).filter(v->!blank(v)).distinct().toList();return matches.size()==1?match(value,matches.getFirst()):Source.UNKNOWN;}
    private static Source match(String value,String source){return !blank(value)&&same(value,source)?Source.MARKETPLACE_UNVERIFIED:Source.UNKNOWN;}
    private static boolean same(String a,String b){return Objects.toString(a,"").strip().equals(Objects.toString(b,"").strip());}
    private static boolean blank(String value){return value==null||value.isBlank();}
}
