package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite approval/batch/recovery boundaries; never performs an API request or signature. */
class Wb1326PurchasePersistenceTest {
    @TempDir Path directory;
    ZnackRepository repository;
    ZnackPurchaseBatchStore store;
    Settings settings;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Synthetic batch','fixture'),(2,'Other synthetic','fixture')");}
        repository=new ZnackRepository(new ShopContext(1,"Synthetic batch"));store=new ZnackPurchaseBatchStore(repository);
        settings=new Settings("","","fixture-oms","fixture-connection","7700000001","7700000001","7700000001","","fixture-cert","[]","FIXTURE","01.01.2026","fixture-pdf",true);
        repository.saveSettings(settings);products(15,"6103100000");
    }
    @AfterEach void cleanup(){ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");}

    @Test void tenDistinctGtinsShareOneRemoteOrderAndEveryLocalPipelineKeepsItsIdentity(){
        var ids=approved(11);var first=store.prepare(ids.getFirst()).orElseThrow();assertEquals(10,first.lines().size());
        assertEquals(1,store.prepare(ids.getLast()).orElseThrow().lines().size());
        submit(first);store.acceptRemoteOrder(first.id(),"fixture-remote");
        for(var line:first.lines()){
            var order=repository.findOrder(line.orderId()).orElseThrow();assertEquals("fixture-remote",order.externalOrderId());
            var pipeline=repository.findPipeline(line.pipelineId()).orElseThrow();assertEquals(line.orderId(),pipeline.orderId());
            assertEquals(PurchaseStage.POLLING_ORDER,pipeline.stage());assertEquals(line.pipelineId(),repository.findPipelineByRequestKey(line.requestKey()).orElseThrow().id());
        }
        assertEquals(11,repository.findOrders().size());
    }
    @Test void delayedPreparationUsesApprovedMethodNotCurrentPreference(){
        var ids=approved(2);new ZnackPurchasePreferences(1).saveMethod(ZnackPurchaseMethod.COMMISSION);
        var batch=store.prepare(ids.getFirst()).orElseThrow();assertEquals(ZnackPurchaseMethod.PRODUCTION,batch.method());
        assertTrue(batch.settings().autoIntroduction());
    }
    @Test void explicitMethodSnapshotWinsOverChangedGlobalPreference(){
        new ZnackPurchasePreferences(1).saveMethod(ZnackPurchaseMethod.COMMISSION);
        var ids=store.enqueueApproved(settings,ZnackPurchaseMethod.PRODUCTION,List.of(request(1,1)));
        assertEquals(ZnackPurchaseMethod.PRODUCTION,store.prepare(ids.getFirst()).orElseThrow().method());
    }
    @Test void productGroupAndMethodCannotBeMixedInOneBatch(){
        repository.upsertProducts(List.of(new Product(gtin(2),"Synthetic shoe","6403990000","CONFORMITY_DECLARATION","FIXTURE","01.01.2026",null)));
        var first=store.enqueueApproved(settings,ZnackPurchaseMethod.PRODUCTION,List.of(request(1,1),request(2,1)));
        var third=store.enqueueApproved(settings,ZnackPurchaseMethod.COMMISSION,List.of(request(3,1)));
        assertEquals(1,store.prepare(first.getFirst()).orElseThrow().lines().size());
        assertEquals(ZnackProductGroup.FOOTWEAR,store.prepare(first.getLast()).orElseThrow().group());
        assertEquals(ZnackPurchaseMethod.COMMISSION,store.prepare(third.getFirst()).orElseThrow().method());
    }
    @Test void unrelatedPdfAndPermitPreferencesDoNotSplitRemoteIdentityButRemainPerMember(){
        Settings a=settings.withDefaultGoodsDocument("CONFORMITY_DECLARATION","FIXTURE-A","01.01.2026");
        Settings b=settings.withDefaultGoodsDocument("CONFORMITY_DECLARATION","FIXTURE-B","02.01.2026");
        var one=store.enqueueApproved(a,List.of(request(1,1))).getFirst();var two=store.enqueueApproved(b,List.of(request(2,1))).getFirst();
        var batch=store.prepare(one).orElseThrow();assertEquals(2,batch.lines().size());
        for(var line:batch.lines())assertEquals(line.pipelineId()==one?a:b,store.contextForOrder(line.orderId()).settings());
    }
    @Test void changedRemoteAuthorityCannotBeSilentlySubstitutedBetweenApprovalAndEnqueue(){
        Settings changed=new Settings("","","other-oms","fixture-connection","7700000001","7700000001","7700000001","","fixture-cert","[]","FIXTURE","01.01.2026","fixture-pdf",true);
        repository.saveSettings(changed);
        assertThrows(ZnackRepository.SettingsConflictException.class,()->store.enqueueApproved(settings,List.of(request(1,1))));
        assertTrue(repository.findOrders().isEmpty());assertTrue(repository.findActivePipelines().isEmpty());
    }
    @Test void requestReplayDoesNotChangeApprovalOrCreateAnotherPipeline(){
        var request=request(1,2);var one=store.enqueueApproved(settings,List.of(request)).getFirst();
        new ZnackPurchasePreferences(1).saveMethod(ZnackPurchaseMethod.COMMISSION);
        assertEquals(one,store.enqueueApproved(settings,List.of(request)).getFirst());
        assertEquals(ZnackPurchaseMethod.PRODUCTION,store.contextForPipeline(one).method());
        assertThrows(IllegalArgumentException.class,()->store.enqueueApproved(settings,List.of(new ZnackPurchaseBatchStore.Approved(gtin(1),3,request.requestKey()))));
    }
    @Test void canonicalPayloadCannotExceedOrChangeApprovedQuantity(){
        var batch=store.prepare(approved(2).getFirst()).orElseThrow();var wrong=payload(batch);wrong.getAsJsonArray("products").get(0).getAsJsonObject().addProperty("quantity",999);
        assertThrows(IllegalArgumentException.class,()->store.savePayload(batch.id(),wrong.toString()));
        store.savePayload(batch.id(),payload(batch).toString());
        assertThrows(IllegalStateException.class,()->store.savePayload(batch.id(),payload(batch).toString()+" "));
    }
    @Test void submissionClaimAndUncertainOutcomeRemainDurableAcrossNewStoreInstances(){
        var batch=store.prepare(approved(2).getFirst()).orElseThrow();store.savePayload(batch.id(),payload(batch).toString());
        assertTrue(store.beginSubmission(batch.id()));assertFalse(store.beginSubmission(batch.id()));
        store.markReconciling(batch.id(),"Synthetic response unknown");
        var restarted=new ZnackPurchaseBatchStore(repository);assertEquals(ZnackPurchaseBatchStore.State.RECONCILING,restarted.findByPipeline(batch.lines().getFirst().pipelineId()).orElseThrow().state());
        assertFalse(restarted.beginSubmission(batch.id()));
        assertThrows(IllegalStateException.class,()->restarted.hold(batch.id(),Instant.now(),"unknown is not a quota rejection"));
        assertTrue(restarted.prepareCredentialRecovery(settings,batch.id(),Instant.now()).isEmpty());
        assertEquals(2,repository.findOrders().size());
    }
    @Test void cancelledMemberInvalidatesAllOrNothingEmissionClaim() throws Exception {
        var batch=store.prepare(approved(2).getFirst()).orElseThrow();store.savePayload(batch.id(),payload(batch).toString());
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_purchase_pipelines SET stage='FAILED' WHERE id=?")){p.setLong(1,batch.lines().getLast().pipelineId());p.executeUpdate();}
        assertFalse(store.beginSubmission(batch.id()));assertNull(store.find(batch.id()).orElseThrow().attemptedAt());
    }
    @Test void accountQuotaIsSharedAcrossShopsWithoutBlockingReadingAnExistingPaidOrder(){
        var first=store.prepare(approved(1).getFirst()).orElseThrow();submit(first);store.acceptRemoteOrder(first.id(),"paid-before-quota");
        var queued=store.prepare(store.enqueueApproved(settings,List.of(request(2,1))).getFirst()).orElseThrow();submit(queued);
        store.recordQuotaRejection(queued.id(),Instant.now().plusSeconds(30),"3350 fixture");
        var otherRepository=new ZnackRepository(new ShopContext(2,"Other synthetic"));otherRepository.saveSettings(settings);
        var otherStore=new ZnackPurchaseBatchStore(otherRepository);assertTrue(otherStore.capacityBlocked(first.accountKey()));
        assertEquals("paid-before-quota",store.findByOrder(first.lines().getFirst().orderId()).orElseThrow().externalOrderId());
        assertFalse(store.beginSubmission(queued.id()));assertEquals(0,store.find(queued.id()).orElseThrow().recoveryAttempt());
    }
    @Test void rejectedBatchRecoveryCreatesSingleGtinChildrenOnceWithoutChangingRootApproval(){
        var root=store.prepare(approved(2).getFirst()).orElseThrow();submit(root);store.recordKnownNotCreated(root.id(),"1090 definite fixture");
        var children=store.prepareCredentialRecovery(settings,root.id(),Instant.now().plusSeconds(1));assertEquals(2,children.size());
        assertEquals(children.stream().map(ZnackPurchaseBatchStore.Batch::id).toList(),store.prepareCredentialRecovery(settings,root.id(),Instant.now().plusSeconds(2)).stream().map(ZnackPurchaseBatchStore.Batch::id).toList());
        for(var child:children){assertEquals(1,child.lines().size());assertEquals(root.id(),child.rootBatchId());assertEquals(root.id(),child.parentBatchId());assertEquals(1,child.recoveryAttempt());
            var line=child.lines().getFirst();var old=root.lines().stream().filter(x->x.pipelineId()==line.pipelineId()).findFirst().orElseThrow();
            assertNotEquals(old.orderId(),line.orderId());assertEquals(old.requestKey(),line.requestKey());assertEquals(old.quantity(),line.quantity());
            assertEquals(child.id(),store.findByPipeline(line.pipelineId()).orElseThrow().id());assertNull(repository.findOrder(old.orderId()).orElseThrow().externalOrderId());
        }
        assertEquals(ZnackPurchaseBatchStore.State.REJECTED,store.find(root.id()).orElseThrow().state());
        assertEquals(4,repository.findOrders().size());
    }
    @Test void credentialRecoveryDelayAndThreeAttemptCapCannotBeBypassedWithHold(){
        var current=store.prepare(approved(1).getFirst()).orElseThrow();
        for(int attempt=0;attempt<3;attempt++){
            submit(current);store.recordKnownNotCreated(current.id(),"1090 fixture");var rejected=store.find(current.id()).orElseThrow();
            assertThrows(IllegalStateException.class,()->store.hold(rejected.id(),Instant.now(),"not a quota"));
            if(attempt>0){assertTrue(rejected.nextAttemptAt().isAfter(Instant.now().plusSeconds(attempt==1?290:890)));assertTrue(store.prepareCredentialRecovery(settings,rejected.id(),Instant.now()).isEmpty());}
            current=store.prepareCredentialRecovery(settings,rejected.id(),rejected.nextAttemptAt().plusMillis(1)).getFirst();assertEquals(attempt+1,current.recoveryAttempt());
        }
        submit(current);store.recordKnownNotCreated(current.id(),"1090 fixture");
        assertEquals(ZnackPurchaseBatchStore.State.FAILED,store.find(current.id()).orElseThrow().state());assertTrue(store.prepareCredentialRecovery(settings,current.id(),Instant.now().plusSeconds(3600)).isEmpty());
    }
    @Test void foreignOwnerCannotReplaceEvenARejectedUnpaidProfile(){
        var root=store.prepare(approved(1).getFirst()).orElseThrow();submit(root);store.recordKnownNotCreated(root.id(),"1090 fixture");
        var foreign=new Settings("","","fixture-oms","new-connection","7800000002","7800000002","7800000002","","other-cert","[]","FIXTURE","01.01.2026","fixture-pdf",true);
        assertTrue(store.prepareCredentialRecovery(foreign,root.id(),Instant.now().plusSeconds(1)).isEmpty());assertEquals(1,repository.findOrders().size());
    }
    @Test void acceptedOrIssuedOrdersNeverBecomeCredentialReplacementCandidates(){
        var root=store.prepare(approved(1).getFirst()).orElseThrow();submit(root);store.acceptRemoteOrder(root.id(),"already-paid");
        assertTrue(store.prepareCredentialRecovery(settings,root.id(),Instant.now().plusSeconds(3600)).isEmpty());
        assertThrows(IllegalStateException.class,()->store.recordKnownNotCreated(root.id(),"cannot erase a paid order"));
        assertEquals("already-paid",repository.findOrder(root.lines().getFirst().orderId()).orElseThrow().externalOrderId());
    }
    @Test void repeatedSameGtinRequestsStayQueuedWithoutOrphanDrafts(){
        var a=store.enqueueApproved(settings,List.of(request(1,1))).getFirst();var b=store.enqueueApproved(settings,List.of(request(1,2))).getFirst();
        assertTrue(store.prepare(b).isEmpty());assertTrue(repository.findOrders().isEmpty());
        var first=store.prepare(a).orElseThrow();assertEquals(1,first.lines().size());assertTrue(store.prepare(b).isEmpty());assertEquals(1,repository.findOrders().size());
    }
    static String gtin(int n){String base=String.format(java.util.Locale.ROOT,"046%010d",n);int sum=0;for(int i=0;i<13;i++)sum+=(base.charAt(i)-'0')*(i%2==0?3:1);return base+(10-sum%10)%10;}
    private void products(int n,String tnVed){var list=new ArrayList<Product>();for(int i=1;i<=n;i++)list.add(new Product(gtin(i),"Synthetic "+i,tnVed,"CONFORMITY_DECLARATION","FIXTURE","01.01.2026",null));repository.upsertProducts(list);}
    private ZnackPurchaseBatchStore.Approved request(int gtin,int quantity){return new ZnackPurchaseBatchStore.Approved(gtin(gtin),quantity,UUID.randomUUID().toString());}
    private List<Long> approved(int count){var list=new ArrayList<ZnackPurchaseBatchStore.Approved>();for(int i=1;i<=count;i++)list.add(request(i,i));return store.enqueueApproved(settings,list);}
    private void submit(ZnackPurchaseBatchStore.Batch batch){store.savePayload(batch.id(),payload(batch).toString());assertTrue(store.beginSubmission(batch.id()));}
    static JsonObject payload(ZnackPurchaseBatchStore.Batch batch){var root=new JsonObject();root.addProperty("productGroup",batch.group().apiCode());var attrs=new JsonObject();attrs.addProperty("releaseMethodType",batch.method().name());root.add("attributes",attrs);var products=new JsonArray();for(var line:batch.lines()){var p=new JsonObject();p.addProperty("gtin",line.gtin());p.addProperty("quantity",line.quantity());p.addProperty("serialNumberType","OPERATOR");p.addProperty("templateId",batch.group().templateId());p.addProperty("cisType","UNIT");products.add(p);}root.add("products",products);return root;}
}
