package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.ZnackModels.GoodsDocument;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackVerifiedRegistrationAttributesTest {
    @TempDir Path profile;
    static final String LIGHT="ТР ТС 017/2011 \"О безопасности продукции легкой промышленности\"";
    static final String CHILD="ТР ТС 007/2011 \"О безопасности продукции, предназначенной для детей и подростков\"";
    final GoodsDocument selected=new GoodsDocument("CONFORMITY_DECLARATION","FIXTURE","2025-01-01");
    final FixtureClock clock=new FixtureClock();
    FixtureApi api;
    ZnackNationalCatalogService catalog;
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",profile.toString());api=new FixtureApi();catalog=service(api);}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    ZnackNationalCatalogService service(FixtureApi fixture){return new ZnackNationalCatalogService(fixture,null,null,ZnackModels.Settings.empty(),clock);}
    static List<Attribute> schema(String... values){return List.of(new Attribute(13836,"Технический регламент","text",true,false,true,false,List.of(values)));}
    void review(GoodsDocument document)throws Exception {catalog.reviewDocuments("fixture",new ZnackRegistrationDocuments.Selection(List.of(document),document,ZnackRegistrationDocuments.Source.PRODUCT,false),"6204310000");}

    @Test void actualPermitCodeSelectsTheExactCurrentSchemaLiteralWithoutAnotherRequest()throws Exception {
        review(selected);
        var facts=catalog.verifiedRegistrationAttributes(selected,schema(LIGHT,CHILD));
        assertEquals(Map.of(13836L,LIGHT),facts);assertEquals(1,api.calls);
        assertThrows(UnsupportedOperationException.class,()->facts.put(13836L,"changed"));
    }
    @Test void aDifferentVerifiedRegulationIsSelectedInsteadOfAssumingTheGreenExample()throws Exception {
        api.regulations="ТР ТС 007/2011 О безопасности продукции для детей";review(selected);
        assertEquals(Map.of(13836L,CHILD),catalog.verifiedRegistrationAttributes(selected,schema(LIGHT,CHILD)));
        assertTrue(catalog.verifiedRegistrationAttributes(selected,schema(LIGHT)).isEmpty());
        assertTrue(catalog.verifiedRegistrationAttributes(selected,schema("ТР ЕАЭС 007/2011 Другая юрисдикция")).isEmpty());
    }
    @Test void noFactIsAvailableBeforeVerificationOrForAnotherExactDocument()throws Exception {
        assertTrue(catalog.verifiedRegistrationAttributes(selected,schema(LIGHT)).isEmpty());review(selected);
        for(var other:List.of(new GoodsDocument(selected.type(),"OTHER",selected.date()),new GoodsDocument(selected.type(),selected.number(),"2025-01-02"),new GoodsDocument("CONFORMITY_CERTIFICATE",selected.number(),selected.date())))
            assertTrue(catalog.verifiedRegistrationAttributes(other,schema(LIGHT)).isEmpty());
    }
    @Test void inactiveUnknownExpiredAmbiguousAndPendingRegistryResultsCannotSupplyDefaults()throws Exception {
        for(String failure:List.of("inactive","unknown","expired","ambiguous","pending","wrong-date")){
            api=new FixtureApi();api.failure=failure;catalog=service(api);review(selected);
            assertTrue(catalog.verifiedRegistrationAttributes(selected,schema(LIGHT)).isEmpty(),failure);
        }
    }
    @Test void missingUnknownOrAmbiguousSchemaMetadataDoesNotInventAValue()throws Exception {
        review(selected);
        assertTrue(catalog.verifiedRegistrationAttributes(selected,List.of()).isEmpty());
        assertTrue(catalog.verifiedRegistrationAttributes(selected,schema(LIGHT,"ТР ТС 017/2011 Другая подпись")).isEmpty());
        assertTrue(catalog.verifiedRegistrationAttributes(selected,List.of(new Attribute(13836,"Regulation","number",true,false,true,false,List.of(LIGHT)))).isEmpty());
        assertTrue(catalog.verifiedRegistrationAttributes(selected,schema("Не подлежит обязательной оценке соответствия")).isEmpty());
    }
    @Test void multiplePermitRegulationsRequireOneUniqueAllowedSchemaChoice()throws Exception {
        api.regulations="ТР ТС 017/2011; ТР ТС 007/2011";review(selected);
        assertTrue(catalog.verifiedRegistrationAttributes(selected,schema(LIGHT,CHILD)).isEmpty());
        assertEquals(Map.of(13836L,LIGHT),catalog.verifiedRegistrationAttributes(selected,schema(LIGHT)));
    }
    @Test void unknownTechnicalMetadataIsNotReadFromUnrelatedOrMalformedFields()throws Exception {
        for(String shape:List.of("missing","object","other-field")){
            api=new FixtureApi();api.metadataShape=shape;catalog=service(api);review(selected);
            assertTrue(catalog.verifiedRegistrationAttributes(selected,schema(LIGHT)).isEmpty(),shape);
        }
    }
    @Test void verificationCacheIsCopiedAndExpiresWithTheVerifiedReadback()throws Exception {
        review(selected);api.last.getAsJsonObject("result").getAsJsonArray("documents").get(0).getAsJsonObject().addProperty("product_tech_regulations","ТР ТС 007/2011");
        assertEquals(Map.of(13836L,LIGHT),catalog.verifiedRegistrationAttributes(selected,schema(LIGHT,CHILD)));
        clock.now=clock.now.plusSeconds(121);
        assertTrue(catalog.verifiedRegistrationAttributes(selected,schema(LIGHT)).isEmpty());assertEquals(1,api.calls);
    }
    @Test void serviceCacheCannotCrossAnApplicationDataProfile()throws Exception {
        review(selected);System.setProperty("vncode.appdata.dir",profile.resolve("other-profile").toString());
        assertTrue(catalog.verifiedRegistrationAttributes(selected,schema(LIGHT)).isEmpty());
        var otherApi=new FixtureApi();otherApi.regulations="ТР ТС 007/2011";var other=service(otherApi);
        other.reviewDocuments("fixture",new ZnackRegistrationDocuments.Selection(List.of(selected),selected,ZnackRegistrationDocuments.Source.PRODUCT,false),"6204310000");
        assertEquals(Map.of(13836L,CHILD),other.verifiedRegistrationAttributes(selected,schema(LIGHT,CHILD)));
    }
    @Test void permitCacheRemainsBoundedToRecentExactDocuments()throws Exception {
        GoodsDocument oldest=null,latest=null;
        for(int n=0;n<257;n++){
            var document=new GoodsDocument(selected.type(),"FIXTURE-"+n,selected.date());if(oldest==null)oldest=document;latest=document;
            review(document);clock.now=clock.now.plusMillis(1);
        }
        assertTrue(catalog.verifiedRegistrationAttributes(oldest,schema(LIGHT)).isEmpty());
        assertEquals(Map.of(13836L,LIGHT),catalog.verifiedRegistrationAttributes(latest,schema(LIGHT)));assertEquals(257,api.calls);
    }
    static final class FixtureClock extends Clock {
        Instant now=Instant.parse("2026-10-20T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    static final class FixtureApi extends ZnackApiClient {
        int calls;String regulations="ТР ТС 017/2011 О безопасности продукции легкой промышленности",failure="",metadataShape="";JsonObject last;
        @Override public JsonElement registrationPermitInfo(String base,String token,JsonArray requested){
            calls++;var documents=requested.deepCopy();var document=documents.get(0).getAsJsonObject();
            document.addProperty("to_date",failure.equals("expired")?"2020-01-01":"2099-01-01");document.addProperty("product_tnved","6204");
            if(!failure.equals("unknown")){document.addProperty("status_group",failure.equals("inactive")?2:1);document.addProperty("status",failure.equals("inactive")?"Прекращен":"Действует");}
            if(!metadataShape.equals("missing")&&!metadataShape.equals("other-field"))document.addProperty("product_tech_regulations",regulations);
            if(metadataShape.equals("other-field"))document.addProperty("arbitrary_description",regulations);
            if(metadataShape.equals("object"))document.add("product_tech_regulations",new JsonObject());
            if(failure.equals("wrong-date"))document.addProperty("from_date","2025-01-02");
            if(failure.equals("ambiguous"))documents.add(document.deepCopy());
            var result=new JsonObject();result.add("documents",documents);last=new JsonObject();last.addProperty("apiversion",4);last.add("result",result);
            if(failure.equals("pending"))last.addProperty("error_code",18);return last;
        }
    }
}
