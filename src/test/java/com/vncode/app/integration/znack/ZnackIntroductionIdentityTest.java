package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.znack.ZnackCisReadbackTest.*;

class ZnackIntroductionIdentityTest {
    @TempDir Path directory;ZnackRepository repository;long order,document;AtomicInteger reads=new AtomicInteger();
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture','fixture')");}
        repository=new ZnackRepository(new ShopContext(1,"Fixture"));repository.upsertProducts(List.of(new Product(GTIN,"Product","6201000000",null,null,null,null)));
        order=repository.createDraft(GTIN,2);repository.insertCodes(order,GTIN,new DownloadedCodes(List.of(FIRST+"\u001d91ABCD\u001d92signature",SECOND+"\u001d91ABCD\u001d92signature"),"block"));
        document=repository.createDocument(order,"{\"saved\":true}");repository.updateDocument(document,"accepted-document","SUBMITTED",null);repository.markCodes(order,KizLegalStatus.INTRO_SENT,null,document);repository.updateOrder(order,"accepted-order","READY",OrderStatus.INTRO_SENT,null);
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    static Stream<Object[]> invalid(){return ZnackCisReadbackTest.unknownResponses();}
    private Settings settings(){return new Settings("","","oms","connection","","","","","certificate","[]","","","",false,"","[]","certificate",Instant.now());}
    private ZnackApiClient api(JsonElement response){return new ZnackApiClient(){
        @Override public JsonElement document(String base,String token,String id){assertEquals("accepted-document",id);return JsonParser.parseString("{\"status\":\"CHECKED_OK\"}");}
        @Override public JsonElement productCards(String base,String token,String gtin){assertEquals(GTIN,gtin);return JsonParser.parseString("{\"result\":[{\"gtin\":\""+GTIN+"\",\"goodMarkFlag\":true,\"goodTurnFlag\":true,\"goodStatus\":\"published\"}]}");}
        @Override public JsonElement cisesInfo(String base,String token,JsonElement request){reads.incrementAndGet();assertEquals(Set.of(FIRST,SECOND),new HashSet<>(request.getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList()));return response;}
        @Override public String createDocument(String base,String token,JsonObject payload){throw new AssertionError("Readback must never submit a legal document");}
    };}
    private ZnackAuthService auth(ZnackApiClient api){return new ZnackAuthService(api,(bytes,context)->{throw new AssertionError("Readback must never request a signature");}){@Override public String trueApiToken(Settings settings){return "fixture";}};}
    private ZnackIntroductionService confirmation(ZnackApiClient api){return new ZnackIntroductionService(api,auth(api),(bytes,context)->{throw new AssertionError("Readback must never request a signature");},repository);}
    private void assertRetained(){assertEquals(OrderStatus.INTRO_SENT,repository.findOrder(order).orElseThrow().localStatus());var saved=repository.findLatestDocument(order).orElseThrow();assertEquals("accepted-document",saved.externalDocumentId());assertEquals("SUBMITTED",saved.status());assertEquals("{\"saved\":true}",saved.payloadJson());assertTrue(repository.findCodes(order).stream().allMatch(c->c.legalStatus()==KizLegalStatus.INTRO_SENT));}
    @ParameterizedTest(name="{0}") @MethodSource("invalid") void badReadbackCannotPromoteAnOrderOrEraseItsReceipt(String name,JsonElement response)throws Exception{
        var result=confirmation(api(response)).confirm(settings(),repository.findOrder(order).orElseThrow(),repository.findCodes(order));assertEquals(ZnackIntroductionService.ConfirmStatus.PENDING,result.status(),name);assertRetained();assertEquals(1,reads.get());
    }
    @ParameterizedTest(name="{0}") @MethodSource("invalid") void readinessDoesNotTreatUnknownOrForeignCodesAsReady(String name,JsonElement response)throws Exception{
        var api=api(response);var result=new ZnackIntroductionReadinessService(api,auth(api),repository).check(settings(),repository.findProduct(GTIN).orElseThrow(),repository.findCodes(order));assertFalse(result.ready(),name);assertFalse(result.allIntroduced(),name);assertRetained();
    }
    @Test void exactReorderedReadbackPromotesEveryPersistedCodeAfterTheDocumentWasAccepted()throws Exception{
        var response=rows(row(SECOND,GTIN,"INTRODUCED"),row(FIRST,GTIN,"INTRODUCED"));var result=confirmation(api(response)).confirm(settings(),repository.findOrder(order).orElseThrow(),repository.findCodes(order));assertTrue(result.introduced());assertEquals(OrderStatus.INTRODUCED,repository.findOrder(order).orElseThrow().localStatus());assertEquals("CHECKED_OK",repository.findLatestDocument(order).orElseThrow().status());assertTrue(repository.findCodes(order).stream().allMatch(c->c.legalStatus()==KizLegalStatus.IN_CIRCULATION));
    }
    @Test void exactSuccessfulCodeZeroResponseConfirmsCirculationAndReadiness()throws Exception{
        var first=row(FIRST,GTIN,"INTRODUCED");first.addProperty("errorCode","0");first.addProperty("errorMessage","");var second=row(SECOND,GTIN,"INTRODUCED");second.addProperty("errorCode",0);
        var response=rows(first,second);var api=api(response);assertTrue(new ZnackIntroductionReadinessService(api,auth(api),repository).check(settings(),repository.findProduct(GTIN).orElseThrow(),repository.findCodes(order)).allIntroduced());assertRetained();
        assertTrue(confirmation(api).confirm(settings(),repository.findOrder(order).orElseThrow(),repository.findCodes(order)).introduced());assertEquals(OrderStatus.INTRODUCED,repository.findOrder(order).orElseThrow().localStatus());
    }
    @Test void partialDownloadedOrderDoesNotBecomeFullyIntroduced()throws Exception{
        var result=confirmation(api(rows(row(FIRST,GTIN,"INTRODUCED")))).confirm(settings(),repository.findOrder(order).orElseThrow(),repository.findCodes(order).subList(0,1));assertEquals(ZnackIntroductionService.ConfirmStatus.PENDING,result.status());assertEquals(0,reads.get());assertRetained();
    }
    @Test void unknownReadbackPreservesPreviouslyConfirmedCirculation()throws Exception{
        repository.updateDocument(document,null,"CHECKED_OK",null);repository.markCodes(order,KizLegalStatus.IN_CIRCULATION,null,null);repository.updateOrder(order,null,null,OrderStatus.INTRODUCED,null);
        var result=confirmation(api(rows())).confirm(settings(),repository.findOrder(order).orElseThrow(),repository.findCodes(order));assertFalse(result.introduced());assertEquals(OrderStatus.INTRODUCED,repository.findOrder(order).orElseThrow().localStatus());assertEquals("CHECKED_OK",repository.findLatestDocument(order).orElseThrow().status());assertTrue(repository.findCodes(order).stream().allMatch(c->c.legalStatus()==KizLegalStatus.IN_CIRCULATION));
    }
    @Test void latestMissingProductCardPreservesLastGoodFlagsButCannotAuthorizeFreshIntroduction()throws Exception{
        Instant observed=Instant.parse("2026-10-20T00:00:00Z");repository.updateProductReadiness(new Product(GTIN,"Saved product","6201000000",null,null,null,null,true,true,"published","", "",observed));
        var api=new ZnackApiClient(){@Override public JsonElement productCards(String b,String t,String g){return new JsonObject();}@Override public JsonElement cisesInfo(String b,String t,JsonElement request){return rows(row(FIRST,GTIN,"APPLIED"),row(SECOND,GTIN,"APPLIED"));}};
        var result=new ZnackIntroductionReadinessService(api,auth(api),repository).check(settings(),repository.findProduct(GTIN).orElseThrow(),repository.findCodes(order));assertFalse(result.ready());assertFalse(result.allIntroduced());var retained=repository.findProduct(GTIN).orElseThrow();assertTrue(retained.goodMarkFlag());assertTrue(retained.goodTurnFlag());assertEquals(observed,retained.readinessCheckedAt());assertEquals("published",retained.cardStatus());assertRetained();
    }
}
