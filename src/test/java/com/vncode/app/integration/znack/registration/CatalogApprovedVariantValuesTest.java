package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import com.vncode.app.integration.znack.ZnackApiClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogApprovedVariantValuesTest {
    @TempDir Path directory;
    Shop shop; ProductKey key; ProductSnapshot snapshot; List<Attribute> schema; JsonObject payload;
    RegistrationSourceConsentGuard.VerifiedRepairIntent proof;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString()); Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES); key=new ProductKey(shop.getId(),Marketplace.WILDBERRIES,"101","201");
        var variant=new ProductVariant("201","article","Blue","L","",false,"48");
        snapshot=new ProductSnapshot(key,"revision","product","","brand","article","category",List.of(),List.of(),List.of(variant),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
        schema=List.of(new Attribute(35,"Размер","text",false,false,true,false,List.of(),List.of()),new Attribute(36,"Цвет","text",true,false,true,false,List.of("Blue","Red"),List.of()));
        var draft=new Draft("6103109000","6103",10,"product","brand",Map.of(),Map.of(),false,false);
        var plan=new RegistrationDynamicPreflight.Plan(snapshot,draft,schema,null);
        RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,schema,Map.of(),null,false)));
        var evidence=RegistrationFieldProvenance.read(shop,snapshot,draft,schema);
        var prepared=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(plan),Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),Map.of(),Map.of(key,evidence),true);
        String gtin=GtinWriteTestFixture.GTIN; payload=ZnackNationalCatalogService.buildPayload(gtin,draft,"");
        proof=SqliteWriteTransaction.run(c->{
            ZnackRegistrationBatchStore.initialize(c);String now=Instant.now().toString();
            try(var p=c.prepareStatement("INSERT INTO znack_registration_batches(id,shop_id,owner,scope,auto_sign,state,created_at,updated_at) VALUES('run',?,?, '',0,'RUNNING',?,?)")){p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,now);p.setString(4,now);p.executeUpdate();}
            try(var p=c.prepareStatement("INSERT INTO znack_registration_batch_items(run_id,position,sku_json,state,updated_at) VALUES('run',0,'{\"nmId\":101,\"chrtId\":201}','WAITING_REMOTE',?)")){p.setString(1,now);p.executeUpdate();}
            RegistrationSourceConsent.persist(c,shop,"run",List.of(key),prepared);
            try(var p=c.prepareStatement("UPDATE znack_source_consents SET phase='SUBMITTED',expected_gtin_hash=? WHERE run_id='run'")){p.setString(1,ListingIdentity.hash(gtin));p.executeUpdate();}
            return RegistrationSourceConsentGuard.require(c,shop,"run",snapshot,schema,payload,RegistrationSourceConsent.Action.CREATE,true);
        });
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private ZnackNationalCatalogService.FeedProgress rejected(String... errors){return new ZnackNationalCatalogService.FeedProgress("Rejected",91L,List.of(errors));}
    @Test void exactSellerApprovedVariantMayFillOnlyMissingFields() throws Exception {
        var approved=CatalogApprovedVariantValues.read(shop,proof,snapshot,schema);assertNotNull(approved);
        var result=CatalogFieldCorrections.suggest(rejected("35: обязательное поле отсутствует","36: обязательное поле отсутствует"),payload,GtinWriteTestFixture.GTIN,91,true,schema,approved);
        assertNotNull(result);var attrs=result.payload().getAsJsonArray("good_attrs");assertEquals(payload.getAsJsonArray("good_attrs").size()+2,attrs.size());
        var values=new HashMap<Long,String>();attrs.forEach(e->values.put(e.getAsJsonObject().get("attr_id").getAsLong(),e.getAsJsonObject().get("attr_value").getAsString()));
        assertEquals("L",values.get(35L));assertEquals("Blue",values.get(36L));
        assertTrue(result.changes().stream().filter(c->c.field().startsWith("attr:")).allMatch(c->c.source().equals("SELLER_REVIEWED_EXACT_VARIANT_AND_CURRENT_SCHEMA")));
        assertFalse(payload.getAsJsonArray("good_attrs").asList().stream().anyMatch(e->Set.of(35L,36L).contains(e.getAsJsonObject().get("attr_id").getAsLong())));
    }
    @Test void changedVariantSchemaConsentOrExistingAttributeCannotBeOverwritten() throws Exception {
        var changed=new ProductSnapshot(key,snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),List.of(new ProductVariant("201","article","Red","L","",false,"48")),snapshot.dimensions(),snapshot.documents(),snapshot.checkedAt(),snapshot.metadata());
        assertNull(CatalogApprovedVariantValues.read(shop,proof,changed,schema));
        assertNull(CatalogApprovedVariantValues.read(shop,proof,snapshot,List.of()));
        var approved=CatalogApprovedVariantValues.read(shop,proof,snapshot,schema);
        var existing=payload.deepCopy();var attr=new JsonObject();attr.addProperty("attr_id",36);attr.addProperty("attr_value","Red");attr.addProperty("attr_value_type","");existing.getAsJsonArray("good_attrs").add(attr);
        assertNull(CatalogFieldCorrections.suggest(rejected("36: обязательное поле отсутствует"),existing,GtinWriteTestFixture.GTIN,91,true,schema,approved));
        assertNull(CatalogFieldCorrections.suggest(rejected("36: неверное значение"),payload,GtinWriteTestFixture.GTIN,91,true,schema,approved));
        assertNull(CatalogFieldCorrections.suggest(rejected("36: обязательное поле отсутствует"),payload,GtinWriteTestFixture.GTIN,91,false,schema,approved));
        var differentSchema=List.of(schema.getFirst(),new Attribute(36,"Цвет","text",true,false,true,false,List.of("Blue"),List.of()));
        assertNull(CatalogFieldCorrections.suggest(rejected("36: обязательное поле отсутствует"),payload,GtinWriteTestFixture.GTIN,91,true,differentSchema,approved));
        var another=payload.deepCopy();another.addProperty("gtin","00000000000024");
        assertNull(CatalogFieldCorrections.suggest(rejected("36: обязательное поле отсутствует"),another,"00000000000024",91,true,schema,approved));
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_source_consent_fields SET value_hash='' WHERE run_id='run' AND field='variant.color'")){p.executeUpdate();}
        var incomplete=CatalogApprovedVariantValues.read(shop,proof,snapshot,schema);
        assertNull(CatalogFieldCorrections.suggest(rejected("36: обязательное поле отсутствует"),payload,GtinWriteTestFixture.GTIN,91,true,schema,incomplete));
    }
    @Test void correctionLifecycleSendsApprovedValuesToLoopbackAndStopsIfVariantChangesBeforeDispatch() throws Exception {
        var received=new AtomicReference<JsonObject>();var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v3/feed",exchange->{
            try {assertEquals("POST",exchange.getRequestMethod());received.set(com.google.gson.JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject());count.incrementAndGet();byte[] bytes="{\"result\":{\"feed_id\":\"fixture-feed\"}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });server.start();
        try {
            var current=new AtomicReference<>(snapshot);var api=new ZnackApiClient();String base="http://127.0.0.1:"+server.getAddress().getPort();
            class Adapter implements CatalogCorrectionLifecycle.Adapter<String> {
                boolean changeAfterCheckpoint;
                public void observe(){}
                public String rejectedCandidate(){return "fixture";}
                public ZnackNationalCatalogService.FeedProgress readProgress(String item){return rejected("36: обязательное поле отсутствует");}
                public ZnackNationalCatalogService.FeedProgress proveRejection(String item,long id){return rejected("36: обязательное поле отсутствует");}
                public CatalogAutomaticRepair.Proposal propose(String item,ZnackNationalCatalogService.FeedProgress status,long id)throws Exception {
                    var approved=CatalogApprovedVariantValues.read(shop,proof,current.get(),schema);
                    return CatalogFieldCorrections.suggest(status,payload,GtinWriteTestFixture.GTIN,id,true,schema,approved);
                }
                public boolean checkpoint(String item,CatalogAutomaticRepair.Proposal proposal)throws Exception {
                    if(changeAfterCheckpoint){var old=current.get();current.set(new ProductSnapshot(key,old.revision(),old.title(),old.description(),old.brand(),old.vendorArticle(),old.categoryId(),old.media(),old.attributes(),List.of(new ProductVariant("201","article","Red","L","",false,"48")),old.dimensions(),old.documents(),old.checkedAt(),old.metadata()));}
                    return true;
                }
                public void submit(String item,CatalogAutomaticRepair.Proposal proposal)throws Exception {
                    if(CatalogApprovedVariantValues.read(shop,proof,current.get(),schema)==null)return;
                    api.submitNationalCatalogFeed(base,"fixture-token",proposal.payload());
                }
            }
            var changed=new Adapter();changed.changeAfterCheckpoint=true;CatalogCorrectionLifecycle.advance(changed);assertEquals(0,count.get());
            current.set(snapshot);CatalogCorrectionLifecycle.advance(new Adapter());assertEquals(1,count.get());
            var attrs=received.get().getAsJsonArray("good_attrs");assertTrue(attrs.asList().stream().map(e->e.getAsJsonObject()).anyMatch(a->a.get("attr_id").getAsLong()==36&&a.get("attr_value").getAsString().equals("Blue")));
            assertEquals(91,received.get().get("good_id").getAsLong());assertEquals(GtinWriteTestFixture.GTIN,received.get().get("gtin").getAsString());
        } finally {server.stop(0);}
    }
}
