package com.vncode.app.integration.znack.registration.v3;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;

class RegistrationOrchestratorTest {
    @TempDir Path temp; RegistrationStore store; RegistrationOrchestrator service; Fake remote;
    final Owner owner=new Owner(1,"owner","production","scope");
    final Product product=new Product(10,20,1,"ART","Áo","Brand","Đen","M","46",List.of(),"",List.of());
    final Draft draft=new Draft("6109100000",1,"Áo","Brand",Map.of(1L,"RU"));
    static final String GTIN="04601234567893";
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture')");}
        store=new RegistrationStore();remote=new Fake();service=new RegistrationOrchestrator(store,remote,(a,e)->{},Clock.systemUTC());
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    RegistrationJob ready()throws Exception {
        var j=store.create(owner,product);j=service.resolveGtin(j,GTIN);j=service.saveDraft(j,draft);return service.validate(j);
    }
    @Test void defaultPolicyOnlyAllowsDocumentedCatalogWritesAndStillBlocksWb()throws Exception {
        for(var action:List.of(Action.ALLOCATE_GTIN,Action.SUBMIT,Action.SIGN))assertDoesNotThrow(()->RegistrationContractPolicy.require(action,"production"));
        assertThrows(IllegalStateException.class,()->RegistrationContractPolicy.require(Action.SYNC_WB,"production"));
        assertThrows(IllegalStateException.class,()->RegistrationContractPolicy.require(Action.SUBMIT,"sandbox"));
        var guarded=new RegistrationOrchestrator(store,remote);var sent=guarded.confirm(guarded.review(ready(),Action.SUBMIT));
        remote.stage=RegistrationGateway.RemoteState.PUBLISHED;var published=guarded.reconcile(sent);
        var review=guarded.review(published,Action.SYNC_WB);assertThrows(IllegalStateException.class,()->guarded.confirm(review));
        var renewed=guarded.review(store.get(owner,published.id()),Action.SYNC_WB);
        assertThrows(IllegalStateException.class,()->guarded.confirm(renewed));assertEquals(0,remote.wbWrites);
        assertEquals(1,store.operations(owner,sent.id()).size());
    }
    @Test void consentIsBoundToPayloadRevisionOwnerAndExpiry()throws Exception {
        var j=ready();var review=service.review(j,Action.SUBMIT);var latest=store.get(owner,j.id());
        service.saveDraft(latest,new Draft("6109100000",1,"Tên đã sửa","Brand",Map.of(1L,"RU")));
        assertThrows(IllegalStateException.class,()->service.confirm(review));assertEquals(0,remote.sent);
        var changed=service.validate(store.get(owner,j.id()));var second=service.review(changed,Action.SUBMIT);
        remote.current=new Owner(1,"other","production","scope");assertThrows(SecurityException.class,()->service.confirm(second));
        remote.current=owner;var later=new RegistrationOrchestrator(store,remote,(a,e)->{},Clock.offset(Clock.systemUTC(),Duration.ofMinutes(11)));
        assertThrows(IllegalStateException.class,()->later.confirm(second));
    }
    @Test void timeoutIsDurableUnknownAndRepeatedClickCannotSendAgain()throws Exception {
        var j=ready();var review=service.review(j,Action.SUBMIT);remote.timeout=true;
        var unknown=service.confirm(review);assertEquals(RegistrationState.REMOTE_RESULT_UNKNOWN,unknown.state());
        assertEquals("UNKNOWN",new RegistrationStore().operations(owner,j.id()).getFirst().status());
        assertThrows(IllegalStateException.class,()->service.confirm(review));assertEquals(1,remote.sent);
    }
    @Test void feedAcknowledgementAndSignatureAcknowledgementAreNotPublication()throws Exception {
        var sent=service.confirm(service.review(ready(),Action.SUBMIT));assertEquals(RegistrationState.SUBMITTED,sent.state());
        remote.stage=RegistrationGateway.RemoteState.APPROVED;
        var approved=service.reconcile(sent);var signed=service.confirm(service.review(approved,Action.SIGN));
        assertEquals(RegistrationState.SIGNATURE_SENT,signed.state());assertFalse(signed.state().published());
        remote.stage=RegistrationGateway.RemoteState.MODERATION;
        assertFalse(service.reconcile(signed).state().published());
    }
    @Test void publicationRequiresFreshMatchingProductionReadback()throws Exception {
        var sent=service.confirm(service.review(ready(),Action.SUBMIT));remote.stage=RegistrationGateway.RemoteState.PUBLISHED;
        remote.observedGtin="04601234567886";assertThrows(IllegalStateException.class,()->service.reconcile(sent));
        remote.observedGtin=GTIN;remote.environment="sandbox";assertThrows(IllegalStateException.class,()->service.reconcile(sent));
        remote.environment="production";remote.goodId=99L;assertThrows(IllegalStateException.class,()->service.reconcile(sent));
        remote.goodId=7L;var published=service.reconcile(sent);assertEquals(RegistrationState.PUBLISHED_VERIFIED,published.state());
        assertFalse(published.data().verifiedAt().isBlank());
    }
    @Test void unknownAllocationIsNeverRetriedAndAssignedCodeIsImmediatelySaved()throws Exception {
        var j=service.saveDraft(store.create(owner,product),draft);var review=service.review(j,Action.ALLOCATE_GTIN);
        var allocated=service.confirm(review);assertEquals(GTIN,new RegistrationStore().get(owner,j.id()).data().gtin());
        assertEquals(1,remote.allocated);assertThrows(IllegalStateException.class,()->service.confirm(review));
        assertEquals(RegistrationState.GTIN_RESOLVED,allocated.state());
    }
    @Test void mandatorySchemaAndGtinChecksumAreEnforced(){
        var attrs=List.of(new Attribute(1,"Quốc gia","string",true,false,true,false,List.of("RU"),List.of("string")));
        assertFalse(RegistrationValidation.issues(GTIN,draft,attrs).isEmpty()); // type is required for preset-only fields
        var typed=new Draft("6109100000","6109",1,"Áo","Brand",Map.of(1L,"RU"),Map.of(1L,"string"));
        assertTrue(RegistrationValidation.issues(GTIN,typed,attrs).isEmpty());
        assertFalse(RegistrationValidation.issues("04601234567894",typed,attrs).isEmpty());
        assertFalse(RegistrationValidation.issues(GTIN,typed,List.of()).isEmpty());
    }
    @Test void cancelledReviewCanBeOpenedAgainWithoutLosingTheDraft()throws Exception {
        var j=ready();service.review(j,Action.SUBMIT);
        var pending=store.get(owner,j.id());var renewed=service.review(pending,Action.SUBMIT);
        assertEquals(RegistrationState.SUBMITTED,service.confirm(renewed).state());assertEquals(1,remote.sent);
    }
    @Test void reconciledUnknownSubmissionCanProgressWithoutReplayingTheFeed()throws Exception {
        var j=ready();remote.timeout=true;var unknown=service.confirm(service.review(j,Action.SUBMIT));
        remote.timeout=false;remote.stage=RegistrationGateway.RemoteState.APPROVED;
        var approved=service.reconcile(unknown);var signed=service.confirm(service.review(approved,Action.SIGN));
        assertEquals(RegistrationState.SIGNATURE_SENT,signed.state());assertEquals(1,remote.sent);
        assertEquals("RECONCILED",store.operations(owner,j.id()).getFirst().status());
    }
    @Test void categoryChoicesComeFromCatalogAndRejectChangedOwner()throws Exception {
        var job=store.create(owner,product);assertEquals(1,service.categories(job,"6109100000").getFirst().id());
        remote.current=new Owner(1,"changed","production","scope");assertThrows(SecurityException.class,()->service.categories(job,"6109100000"));
    }
    @Test void explicitRateLimitNeedsFreshConsentButDoesNotBecomeAnUnknownResult()throws Exception {
        var j=ready();remote.failureStatus=429;var rejected=service.confirm(service.review(j,Action.SUBMIT));
        assertEquals(RegistrationState.RATE_LIMITED,rejected.state());assertEquals("REJECTED",store.operations(owner,j.id()).getFirst().status());
        remote.failureStatus=null;var edited=service.saveDraft(rejected,draft);var validated=service.validate(edited);
        assertEquals(RegistrationState.SUBMITTED,service.confirm(service.review(validated,Action.SUBMIT)).state());
        assertEquals(2,remote.sent);assertEquals(1,store.operations(owner,j.id()).size());assertEquals(2,store.operations(owner,j.id()).getFirst().attempts());
    }
    @Test void failedValidationCannotBeShownOrReviewedAsReady()throws Exception {
        var j=store.create(owner,product);j=service.resolveGtin(j,GTIN);j=service.saveDraft(j,draft);remote.invalidDraft=true;
        var failed=service.validate(j);assertEquals(RegistrationState.VALIDATION_FAILED,failed.state());
        assertThrows(IllegalStateException.class,()->service.review(failed,Action.SUBMIT));assertEquals(0,remote.sent);
    }
    @Test void wbSuggestionsUseObservedFieldsAndNeverInventLegalAttributes()throws Exception {
        var job=service.saveDraft(store.create(owner,product),draft);
        remote.schema=List.of(new Attribute(35,"Размер","text",false,false,true,false,List.of()),new Attribute(36,"Цвет","text",false,false,true,false,List.of()),new Attribute(2630,"Страна","text",false,false,true,false,List.of()));
        var proposed=service.suggest(job);assertEquals("M",proposed.values().get(35L));assertEquals("Đen",proposed.values().get(36L));assertFalse(proposed.values().containsKey(2630L));
        assertEquals(job,store.get(owner,job.id()));assertEquals(0,remote.allocated);assertEquals(0,remote.sent);
    }
    RegistrationJob published()throws Exception {
        var submitted=service.confirm(service.review(ready(),Action.SUBMIT));remote.stage=RegistrationGateway.RemoteState.PUBLISHED;return service.reconcile(submitted);
    }
    @Test void wbPreviewSurvivesCheckpointAndDelayedReadbackNeverResubmits()throws Exception {
        var card=published();var first=service.review(card,Action.SYNC_WB);
        var renewed=service.review(store.get(owner,card.id()),Action.SYNC_WB);remote.wbApplied=false;
        var pending=service.confirm(renewed);assertEquals(renewed.content(),remote.lastWbPreview);assertEquals(RegistrationState.WB_SYNC_PENDING,pending.state());
        remote.wbApplied=true;var done=service.reconcile(pending);assertEquals(RegistrationState.WB_SYNC_VERIFIED,done.state());assertEquals(1,remote.wbWrites);
    }
    @Test void wbTimeoutCanBeReadBackWithoutLosingPublicationOrApprovedPreview()throws Exception {
        var card=published();var review=service.review(card,Action.SYNC_WB);remote.wbTimeout=true;
        var unknown=service.confirm(review);assertEquals(RegistrationState.REMOTE_RESULT_UNKNOWN,unknown.state());
        assertFalse(unknown.data().verifiedAt().isBlank());assertEquals(review.content(),unknown.data().wbPreview());
        remote.wbTimeout=false;remote.wbApplied=true;assertEquals(RegistrationState.WB_SYNC_VERIFIED,service.reconcile(unknown).state());assertEquals(1,remote.wbWrites);
    }
    @Test void serverRejectedCardCanBeCorrectedAndReviewedAgainUsingTheSameGtin()throws Exception {
        var sent=service.confirm(service.review(ready(),Action.SUBMIT));remote.stage=RegistrationGateway.RemoteState.REJECTED;
        var rejected=service.reconcile(sent);var correction=service.correct(rejected);
        assertEquals(RegistrationState.NEEDS_CORRECTION,correction.state());assertEquals(GTIN,correction.data().gtin());
        var fixed=service.saveDraft(correction,new Draft("6109100000",1,"Tên được sửa","Brand",Map.of(1L,"RU")));
        service.confirm(service.review(service.validate(fixed),Action.SUBMIT));assertEquals(2,remote.sent);
    }
    @Test void externallyRecoveredAllocationIsAttachedByReadbackWithoutAnotherAllocation()throws Exception {
        var j=service.saveDraft(store.create(owner,product),draft);remote.allocateTimeout=true;
        var unknown=service.confirm(service.review(j,Action.ALLOCATE_GTIN));
        assertEquals(RegistrationState.REMOTE_RESULT_UNKNOWN,unknown.state());
        var attached=service.resolveGtin(unknown,GTIN);assertEquals(RegistrationState.GTIN_RESOLVED,attached.state());
        assertEquals(1,remote.allocated);assertEquals("RECONCILED",store.operations(owner,j.id()).getFirst().status());
    }
    @Test void concurrentConfirmationsProduceOneDurableSend()throws Exception {
        var review=service.review(ready(),Action.SUBMIT);var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();try{service.confirm(review);return true;}catch(IllegalStateException stale){return false;}});
            var b=pool.submit(()->{start.await();try{service.confirm(review);return true;}catch(IllegalStateException stale){return false;}});
            start.countDown();assertNotEquals(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1,remote.sent);assertEquals(1,store.operations(owner,review.jobId()).size());
    }
    final class Fake implements RegistrationGateway {
        Owner current=owner;List<Attribute> schema=List.of();Integer failureStatus;boolean invalidDraft;int sent,allocated,wbWrites; boolean timeout,wbTimeout,allocateTimeout; boolean wbApplied=true;String lastWbPreview=""; String observedGtin=GTIN,environment="production";
        Long goodId=7L;RemoteState stage=RemoteState.DRAFT;
        public Owner owner(){return current;}
        public List<Product> products(){return List.of(product);}
        public List<Attribute> attributes(RegistrationJob job){return schema;}
        public List<Category> categories(RegistrationJob job,String tnved){return List.of(new Category(1,"Danh mục Catalog từ máy chủ"));}
        public void validate(RegistrationJob job){if(invalidDraft)throw new IllegalArgumentException("Thiếu thuộc tính bắt buộc.");}
        public String allocate(RegistrationJob job)throws Exception {allocated++;if(allocateTimeout)throw new java.io.IOException("lost allocation ack");return GTIN;}
        public synchronized String submit(RegistrationJob job)throws Exception {sent++;if(failureStatus!=null)throw new com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException("rejected",failureStatus,"{}");if(timeout)throw new java.io.IOException("connection lost");return "feed-1";}
        public Observation observe(RegistrationJob job){return new Observation(observedGtin,goodId,"feed-1",stage,environment,Instant.now().toString(),"server-read",RegistrationJob.hash(job.data().payload()),"");}
        public Document document(RegistrationJob job){return new Document(7,"<xml>review</xml>");}
        public void sign(RegistrationJob job){}
        public String previewWb(RegistrationJob job){return "{\"add\":\""+GTIN+"\"}";}
        public boolean syncWb(RegistrationJob job)throws Exception {wbWrites++;lastWbPreview=job.data().wbPreview();if(wbTimeout)throw new java.io.IOException("lost WB ack");return wbApplied;}
        public boolean verifyWb(RegistrationJob job){return wbApplied;}
    }
}
