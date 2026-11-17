package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Coordinator projections use the real SQLite batch store; no remote or paid action. */
class OwnerBCoordinatorBatchStateTest {
    @TempDir Path directory;
    ZnackRepository repository;
    ZnackPurchaseBatchStore store;
    Settings settings;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var c=Database.getConnection(); var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Synthetic B coordinator','fixture')");
        }
        repository=new ZnackRepository(new ShopContext(1,"Synthetic B coordinator"));
        store=new ZnackPurchaseBatchStore(repository);
        settings=new Settings("","","fixture-oms","fixture-connection","7700000001","7700000001","7700000001","",
                "fixture-cert","[]","FIXTURE","01.01.2026","",true,"","[]","",Instant.now());
        repository.saveSettings(settings);
        repository.upsertProducts(List.of(
                new Product(gtin(1),"Synthetic one","6103100000","CONFORMITY_DECLARATION","FIXTURE","01.01.2026",null),
                new Product(gtin(2),"Synthetic two","6103100000","CONFORMITY_DECLARATION","FIXTURE","01.01.2026",null)));
    }
    @AfterEach void cleanup() {
        ZnackPurchaseCoordinator.cancelPendingPolls();
        ZnackSigningSession.resetForTests();
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void unsentServiceFailurePreservesEveryApprovedMemberAndSchedulesEveryMember() throws Exception {
        var batch=prepared(2);
        var scheduled=new ArrayList<Long>();
        var orders=new ZnackKizOrderService(null,null,null,repository) {
            @Override public ZnackPurchaseBatchStore.Batch advanceBatch(Settings ignored,long id) throws Exception {
                throw new IOException("Synthetic authentication failure before submission");
            }
        };
        var coordinator=coordinator(orders,scheduled);
        assertThrows(IOException.class,()->coordinator.advance(settings,batch.lines().getFirst().pipelineId()));
        assertEquals(ZnackPurchaseBatchStore.State.PREPARED,store.find(batch.id()).orElseThrow().state());
        assertEquals(batch.lines().stream().map(ZnackPurchaseBatchStore.Line::pipelineId).toList(),scheduled);
        for(var line:batch.lines()) {
            var pipeline=repository.findPipeline(line.pipelineId()).orElseThrow();
            assertEquals(PurchaseStage.CREATING_ORDER,pipeline.stage());
            assertTrue(pipeline.errorMessage().contains("Synthetic authentication"));
        }
    }

    @Test void durableDeferredBatchCannotBecomeLegacyFailedLeader() throws Exception {
        var batch=prepared(2);
        var scheduled=new ArrayList<Long>();
        var orders=new ZnackKizOrderService(null,null,null,repository) {
            @Override public ZnackPurchaseBatchStore.Batch advanceBatch(Settings ignored,long id) throws Exception {
                store.hold(id,Instant.now().plusSeconds(30),"SUZ_AUTH_RATE_LIMIT: synthetic429");
                throw new IOException("Synthetic auth429 diagnostic");
            }
        };
        assertThrows(IOException.class,()->coordinator(orders,scheduled).advance(settings,batch.lines().getFirst().pipelineId()));
        var held=store.find(batch.id()).orElseThrow();
        assertEquals(ZnackPurchaseBatchStore.State.DEFERRED,held.state());
        assertNotNull(held.nextAttemptAt()); assertNull(held.attemptedAt());
        for(var line:held.lines()) assertEquals(PurchaseStage.CREATING_ORDER,repository.findPipeline(line.pipelineId()).orElseThrow().stage());
        assertEquals(2,scheduled.size());
    }

    @Test void methodCapturedBeforePreflightCannotBeChangedByPreferenceUpdateDuringValidation() throws Exception {
        var checks=new AtomicInteger();
        var coordinator=new ZnackPurchaseCoordinator(repository,null,null,null) {
            @Override public void validatePrerequisites(Settings ignored,String gtin,int quantity) {
                checks.incrementAndGet();
                new ZnackPurchasePreferences(1).saveMethod(ZnackPurchaseMethod.COMMISSION);
            }
            @Override void schedule(long id) { }
        };
        var ids=coordinator.admitApproved(settings,List.of(request(1),request(2)),false);
        assertEquals(2,checks.get());
        assertEquals(ZnackPurchaseMethod.COMMISSION,new ZnackPurchasePreferences(1).method());
        for(long id:ids) assertEquals(ZnackPurchaseMethod.PRODUCTION,store.contextForPipeline(id).method());
    }

    @Test void failureInLaterValidationPreventsEveryPipelineAndLocalDraft() {
        var coordinator=new ZnackPurchaseCoordinator(repository,null,null,null) {
            @Override public void validatePrerequisites(Settings ignored,String gtin,int quantity) {
                if(gtin.equals(gtin(2))) throw new IllegalArgumentException("Synthetic second-row invalid");
            }
        };
        assertThrows(IllegalArgumentException.class,()->coordinator.admitApproved(settings,List.of(request(1),request(2)),false));
        assertTrue(repository.findOrders().isEmpty()); assertTrue(repository.findActivePipelines().isEmpty());
    }

    private ZnackPurchaseCoordinator coordinator(ZnackKizOrderService orders,List<Long> scheduled) {
        return new ZnackPurchaseCoordinator(repository,orders,null,null) {
            @Override void schedule(long id) { scheduled.add(id); }
        };
    }
    private ZnackPurchaseBatchStore.Batch prepared(int n) {
        var requests=new ArrayList<ZnackPurchaseBatchStore.Approved>();
        for(int i=1;i<=n;i++) requests.add(request(i));
        var ids=store.enqueueApproved(settings,requests);
        return store.prepare(ids.getFirst()).orElseThrow();
    }
    private ZnackPurchaseBatchStore.Approved request(int n) { return new ZnackPurchaseBatchStore.Approved(gtin(n),n,UUID.randomUUID().toString()); }
    private static String gtin(int n) { return Wb1326PurchasePersistenceTest.gtin(n); }
}
