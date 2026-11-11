package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.signature.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic signed fixture and local DB only; no real signature or remote business request. */
class ZnackIntroductionSubmissionOutcomeTest {
    private static final String GTIN="04601234567890";
    @TempDir Path root;
    ZnackRepository repository;
    long order;
    final AtomicInteger sent=new AtomicInteger();
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture','fixture')");}
        repository=new ZnackRepository(new ShopContext(1,"Fixture"));
        repository.upsertProducts(List.of(new Product(GTIN,"Fixture","6201000000",null,null,null,null)));
        order=repository.createDraft(GTIN,1);
        repository.insertCodes(order,GTIN,new DownloadedCodes(List.of("010460123456789021abcdefghijklm\u001d91ABCD\u001d92synthetic"),"fixture-block"));
    }
    @AfterEach void cleanup(){ZnackPurchaseCoordinator.cancelPendingPolls();ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");}

    @ParameterizedTest @ValueSource(ints={0,408,409,499,500,502,503,504})
    void ambiguousResponsesCannotAuthorizeFreshSubmission(int status)throws Exception{
        assertThrows(ZnackApiClient.ZnackApiException.class,()->submit(new ZnackApiClient.ZnackApiException("Synthetic response",status,"{}")));
        assertEquals("FAILED",repository.findLatestDocument(order).orElseThrow().status(),"Uncertain delivery must not be labelled a definitive rejection");
        assertRetained();
    }

    @ParameterizedTest @ValueSource(ints={400,401,403,404,405,406,410,411,413,414,415,422,429})
    void definitiveClientRejectionsRetainRetryClassification(int status)throws Exception{
        assertThrows(ZnackApiClient.ZnackApiException.class,()->submit(new ZnackApiClient.ZnackApiException("Synthetic rejection",status,"{}")));
        assertEquals("REJECTED",repository.findLatestDocument(order).orElseThrow().status());assertRetained();
    }

    @Test void responseTimeoutRemainsAmbiguousAndNeverMarksCodesIntroduced()throws Exception{
        assertThrows(SocketTimeoutException.class,()->submit(new SocketTimeoutException("Synthetic unknown response")));
        assertEquals("FAILED",repository.findLatestDocument(order).orElseThrow().status());assertRetained();
    }

    @Test void coordinatorCannotSubmitAgainAfterAnAmbiguousServerResponse()throws Exception{
        assertThrows(ZnackApiClient.ZnackApiException.class,()->submit(new ZnackApiClient.ZnackApiException("Synthetic HTTP 503",503,"{}")));
        long pipeline=repository.createPipeline(GTIN,1);
        repository.updatePipeline(pipeline,order,PurchaseStage.SUBMITTING_INTRODUCTION,null);
        var introduction=new ZnackIntroductionService(null,null,null,repository){
            @Override public long submit(Settings s,KizOrder o,Product p,List<KizCode> codes){sent.incrementAndGet();throw new AssertionError("Do not send a duplicate legal document");}
        };
        var coordinator=new ZnackPurchaseCoordinator(repository,null,null,introduction){@Override void schedule(long id){}};
        coordinator.advance(settings(),pipeline);
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(pipeline).orElseThrow().stage(),
                "An uncertain submission must keep observing the original codes rather than retrying the document");
        assertEquals("FAILED",repository.findLatestDocument(order).orElseThrow().status());
        coordinator.advance(settings(),pipeline);
        assertEquals("KIZ_CIRCULATION_STATUS_UNVERIFIED",repository.findPipeline(pipeline).orElseThrow().errorMessage());
        coordinator.resumeEligibleIntroductions(settings());
        assertRetained();
    }

    @ParameterizedTest @ValueSource(strings={"Znack API request failed (HTTP 503)","HTTP 408: timeout","HTTP 409: conflict","old rejection without transport evidence","HTTP 503: upstream body mentioned HTTP 422","HTTP 422: body mentioned HTTP 500"})
    void oldRejectedLabelsWithoutDefinitiveEvidenceCannotBeRetried(String diagnostic){
        var saved=new Document(10,order,"{\"retained\":true}",null,"REJECTED",diagnostic);
        assertFalse(ZnackIntroductionService.isDefinitivelyRejectedSubmission(saved));assertEquals(0,sent.get());
    }

    @Test void explicitRemoteOrUnsentEvidenceStillAllowsACorrectedDocument(){
        for(String status:List.of("CHECKED_NOT_OK","NOT_SUBMITTED"))
            assertTrue(ZnackIntroductionService.isDefinitivelyRejectedSubmission(new Document(10,order,"{}",null,status,"retained evidence")));
        assertTrue(ZnackIntroductionService.isDefinitivelyRejectedSubmission(new Document(10,order,"{}",null,"REJECTED","Znack API request failed (HTTP 400)")));
        assertTrue(ZnackIntroductionService.isDefinitivelyRejectedSubmission(new Document(10,order,"{}",null,"FAILED","Znack API request failed (HTTP 422)")));
        assertFalse(ZnackIntroductionService.isDefinitivelyRejectedSubmission(new Document(10,order,"{}","accepted-id","REJECTED","HTTP 400")),"A remote receipt must be read before submitting another document");
        assertFalse(ZnackIntroductionService.isDefinitivelyRejectedSubmission(new Document(10,order,"{}","accepted-id","NOT_SUBMITTED","HTTP 422")));
    }

    private void submit(IOException failure)throws Exception{
        var signer=(ZnackSignatureProvider)(input,context)->new CryptoProSigningResult(new byte[]{0x30,0x02,0x01,0x00},"");
        var api=new ZnackApiClient(){
            @Override public JsonElement permitDocuments(String base,String token,String gtin,String inn){return JsonParser.parseString("{\"result\":{\"documents\":[{\"attr_id\":23557,\"number\":\"SYNTHETIC-DOC\",\"from_date\":\"2026-01-10\",\"status_group\":1}]}}");}
            @Override public String createDocument(String base,String token,JsonObject body)throws IOException{sent.incrementAndGet();throw failure;}
        };
        var auth=new ZnackAuthService(api,signer){
            @Override public String trueApiToken(Settings settings){return "fixture";}
            @Override public String resolvedParticipantInn(Settings settings){return "7701234567";}
        };
        new ZnackIntroductionService(api,auth,signer,repository).submit(settings(),repository.findOrder(order).orElseThrow(),repository.findProduct(GTIN).orElseThrow(),repository.findCodes(order));
    }

    private static Settings settings(){return new Settings("","","fixture-oms","fixture-connection","7701234567","","","","certificate","[]","SYNTHETIC-DOC","10.01.2026","",true,"","[]","fixture",Instant.now());}

    private void assertRetained()throws Exception{
        assertEquals(1,sent.get(),"A failed response must not trigger a second POST");
        var document=repository.findLatestDocument(order).orElseThrow();assertNull(document.externalDocumentId());assertNotNull(document.payloadJson());
        assertEquals(1,repository.findCodes(order).size());assertEquals(KizLegalStatus.RECEIVED,repository.findCodes(order).getFirst().legalStatus());
        assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
        try(var c=Database.getConnection();var p=c.createStatement();var r=p.executeQuery("SELECT COUNT(*) FROM znack_documents WHERE shop_id=1")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
    }
}
