package com.vncode.app.ui.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.models.Shop;
import com.vncode.app.features.gs1.Gs1ReadinessService;
import com.vncode.app.features.gs1.Gs1Repository;
import com.vncode.app.features.gs1.Gs1AgreementService;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.OperatorAgreement;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class Gs1AgreementPaneTest {
    @TempDir Path directory;
    Gs1Pane pane;

    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setUp()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        fx(()->{pane=new Gs1Pane();new Scene(pane,1000,800);field("shop",new Shop(1,"Fixture","fixture"));field("inn","7707083893");field("environment","production");render();return null;});
    }
    @AfterEach void tearDown()throws Exception{fx(()->{pane.dispose();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}

    @Test void unverifiedAgreementOffersAnExplicitReviewActionWithoutStartingRemoteWork()throws Exception{
        fx(()->{
            var review=(Button)pane.lookup("#gs1ReviewAgreements");
            assertNotNull(review,"An unknown operator-agreement check must offer a real read/review action inside VN Code");
            assertTrue(review.isVisible());assertEquals(0,pane.activeTaskCount(),"Rendering must not authenticate or sign");return null;
        });
    }
    @Test void onlyFreshSameShopAndCertificateReadbackCanMarkAnAgreementReady()throws Exception{
        Shop owner=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        fx(()->{
            install(owner);String scope=RegisteredGtinProof.current(owner).source();
            var signed=new OperatorAgreement("11","Synthetic agreement","agreement.pdf","2026-11-17",2,1,4);
            field("agreementEvidence",new Gs1Pane.AgreementEvidence(owner.getId(),scope,Instant.now(),List.of(signed)));
            assertEquals(Gs1ReadinessService.State.READY,agreementState());
            field("agreementEvidence",new Gs1Pane.AgreementEvidence(9,scope,Instant.now(),List.of(signed)));
            assertEquals(Gs1ReadinessService.State.UNKNOWN,agreementState());
            field("agreementEvidence",new Gs1Pane.AgreementEvidence(owner.getId(),scope,Instant.now().minusSeconds(61),List.of(signed)));
            assertEquals(Gs1ReadinessService.State.UNKNOWN,agreementState());
            field("agreementEvidence",new Gs1Pane.AgreementEvidence(owner.getId(),scope,Instant.now(),List.of(signed)));
            GtinWriteTestFixture.saveSettings(owner,"B".repeat(40),com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);install(owner);
            assertEquals(Gs1ReadinessService.State.UNKNOWN,agreementState());
            assertEquals(0,pane.activeTaskCount());return null;
        });
    }
    @Test void sellerSignatureStillWaitsForTheOperatorsCountersignature()throws Exception{
        Shop owner=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        fx(()->{
            install(owner);String scope=RegisteredGtinProof.current(owner).source();
            field("agreementEvidence",new Gs1Pane.AgreementEvidence(owner.getId(),scope,Instant.now(),List.of(new OperatorAgreement("11","Synthetic","agreement.pdf","2026-11-17",2,1,1))));
            assertEquals(Gs1ReadinessService.State.ACTION_REQUIRED,agreementState(),"CRM seller-signed is not the operator's final EDO status");
            field("agreementEvidence",new Gs1Pane.AgreementEvidence(owner.getId(),scope,Instant.now(),List.of(new OperatorAgreement("11","Synthetic","agreement.pdf","2026-11-17",1,1,null))));
            assertEquals(Gs1ReadinessService.State.ACTION_REQUIRED,agreementState());return null;
        });
    }
    @Test void aCompleteEmptyReadReportsNoPendingDocumentsAndIsClearedOnShopChange()throws Exception{
        Shop owner=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        fx(()->{
            install(owner);field("agreementEvidence",new Gs1Pane.AgreementEvidence(owner.getId(),RegisteredGtinProof.current(owner).source(),Instant.now(),List.of()));
            assertEquals(Gs1ReadinessService.State.READY,agreementState());render();
            assertNotNull(pane.lookup("#gs1ReviewAgreements"),"Signed and empty states retain the ability to inspect the actual documents");
            pane.setShop(null);assertEquals(Gs1ReadinessService.State.UNKNOWN,agreementState());assertEquals(0,pane.activeTaskCount());return null;
        });
    }
    @Test void anAgreementReadFailureHasItsOwnSafeScopedCodeAndFreshSuccessClearsIt()throws Exception{
        Shop owner=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        AtomicReference<Exception> failure=new AtomicReference<>(new java.io.IOException("GS1_AGREEMENT_HTTP_403"));
        AtomicInteger reads=new AtomicInteger();
        var signed=new OperatorAgreement("11","Synthetic agreement","agreement.pdf","2026-11-17",2,1,4);
        var service=readOnlyAgreements(()->{reads.incrementAndGet();if(failure.get()!=null)throw failure.get();return List.of(signed);});
        fx(()->{
            install(owner);field("agreements",service);
            field("agreementEvidence",new Gs1Pane.AgreementEvidence(owner.getId(),RegisteredGtinProof.current(owner).source(),Instant.now(),List.of(signed)));
            assertEquals(Gs1ReadinessService.State.READY,agreementState());invoke("refreshAgreements",new Class<?>[]{boolean.class},false);return null;
        });
        await(()->agreementErrorUnchecked().equals("GS1_AGREEMENT_HTTP_403"));
        fx(()->{assertEquals(Gs1ReadinessService.State.UNKNOWN,agreementState(),"A failed recheck must not retain the previous green proof");return null;});
        failure.set(null);fx(()->{invoke("refreshAgreements",new Class<?>[]{boolean.class},false);return null;});
        await(()->agreementStateUnchecked()==Gs1ReadinessService.State.READY&&agreementErrorUnchecked().isEmpty());
        assertEquals(2,reads.get());
        failure.set(new java.io.IOException("Untrusted response containing seller@example.invalid"));
        fx(()->{invoke("refreshAgreements",new Class<?>[]{boolean.class},false);return null;});
        await(()->agreementErrorUnchecked().equals("NETWORK_OR_RESPONSE"));
        fx(()->{
            assertFalse(agreementErrorUnchecked().contains("@"));
            GtinWriteTestFixture.saveSettings(owner,"B".repeat(40),com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);install(owner);
            assertEquals("",agreementErrorUnchecked(),"An error observed for the old certificate must not be reused for the new scope");
            pane.setShop(null);assertEquals("",agreementErrorUnchecked());return null;
        });
    }
    @Test void manualCheckAllWaitsForTheLocalContextThenReadsAgreementsOnce()throws Exception{
        Shop owner=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);AtomicInteger requested=new AtomicInteger(),reads=new AtomicInteger();
        var service=readOnlyAgreements(()->{reads.incrementAndGet();return List.of();});
        fx(()->{
            install(owner);field("agreements",service);field("currentContext",null);pane.setOnSyncRequested(requested::incrementAndGet);
            invoke("checkAll",new Class<?>[]{});invoke("checkAll",new Class<?>[]{});
            assertEquals(2,requested.get());assertEquals(0,reads.get());assertEquals(Boolean.TRUE,value("agreementCheckPending"));
            pane.refreshFromCache();return null;
        });
        await(()->reads.get()==1&&agreementStateUnchecked()==Gs1ReadinessService.State.READY);
        fx(()->{assertEquals(Boolean.FALSE,value("agreementCheckPending"));assertEquals("",agreementErrorUnchecked());return null;});
    }
    @Test void changingShopsCancelsAnAgreementCheckWaitingForContext()throws Exception{
        Shop owner=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);AtomicInteger reads=new AtomicInteger();
        var service=readOnlyAgreements(()->{reads.incrementAndGet();return List.of();});
        fx(()->{
            install(owner);field("agreements",service);field("currentContext",null);pane.setOnSyncRequested(()->{});
            invoke("checkAll",new Class<?>[]{});assertEquals(Boolean.TRUE,value("agreementCheckPending"));
            pane.setShop(null);assertEquals(Boolean.FALSE,value("agreementCheckPending"));invoke("dispatchAgreementCheck",new Class<?>[]{});
            assertEquals(0,reads.get());assertEquals(0,pane.activeTaskCount());return null;
        });
    }
    @Test void openingRecoveryWithoutAnAccountNeverStartsASignatureOrMailTask()throws Exception{
        fx(()->{pane.setShop(null);pane.openRecovery(Gs1Pane.RecoveryAction.MEMBERSHIP);pane.openRecovery(Gs1Pane.RecoveryAction.PREFIX);assertEquals(0,pane.activeTaskCount());return null;});
    }
    @Test void operatorSignatureCheckpointsNeverAppearAsSendableGs1Letters()throws Exception{
        Shop owner=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var repository=new Gs1Repository();
        var mail=repository.createRequest("7700000000","RENEWAL","mail@gs1ru.org","Synthetic renewal","Reviewed mail");
        repository.createRequest("7700000000","OPERATOR_AGREEMENT:synthetic","True API","Synthetic agreement","Exact-document checkpoint");
        fx(()->{install(owner);var method=Gs1Pane.class.getDeclaredMethod("reloadRequests");method.setAccessible(true);method.invoke(pane);return null;});
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean loaded=false;
        while(System.nanoTime()<end){loaded=fx(()->requests().getItems().stream().anyMatch(row->row.id().equals(mail.id())));if(loaded)break;Thread.sleep(10);}
        assertTrue(loaded);fx(()->{assertEquals(List.of(mail.id()),requests().getItems().stream().map(Gs1Repository.Request::id).toList());return null;});
    }
    @Test void aMalformedDictionaryReportedAfterReadingCannotEscapeTheUiErrorHandler()throws Exception{
        Shop owner=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var reported=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        fx(()->{
            install(owner);var method=Gs1Pane.class.getDeclaredMethod("execute",java.util.concurrent.Callable.class,java.util.function.Consumer.class,java.util.function.Consumer.class,boolean.class);method.setAccessible(true);
            java.util.function.Consumer<Object> review=result->new Gs1JoinDialog(pane,"7700000000",Gs1JoinFormValidationContractTest.validForm(),
                    java.util.Map.of("participation",List.of(new com.vncode.app.integration.gs1.NationalCatalogGs1Client.Choice("DUPLICATE","A"),new com.vncode.app.integration.gs1.NationalCatalogGs1Client.Choice("DUPLICATE","B"))),
                    query->List.of(),(form,sign)->fail("Malformed dictionary cannot submit a form"));
            method.invoke(pane,(java.util.concurrent.Callable<Object>)()->"synthetic read completed",review,(java.util.function.Consumer<Throwable>)reported::set,true);return null;
        });
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(reported.get()==null&&System.nanoTime()<end)Thread.sleep(10);
        assertNotNull(reported.get(),"An exception while building the review UI must reach the existing visible failure path");
        assertInstanceOf(Gs1JoinDialog.InvalidChecklistException.class,reported.get());
        String expected=com.vncode.app.shared.I18nService.getInstance().tr("gs1.checklist.invalid")
                .replace("{0}",com.vncode.app.shared.I18nService.getInstance().tr("gs1.field.participation"))+" [GS1_CHECKLIST_AMBIGUOUS]";
        assertEquals(expected,reported.get().getMessage());assertNotNull(reported.get().getCause());assertEquals("GS1_CHECKLIST_AMBIGUOUS",reported.get().getCause().getMessage());
        fx(()->{assertEquals(0,pane.activeTaskCount());assertNotNull(pane.lookup("#gs1CheckAllButton"));var status=Gs1Pane.class.getDeclaredField("status");status.setAccessible(true);assertEquals(expected,((javafx.scene.control.Label)status.get(pane)).getText(),"The seller must see the affected dictionary and recovery, not only an internal generic code");return null;});
    }
    @SuppressWarnings("unchecked")private ListView<Gs1Repository.Request> requests()throws Exception{var field=Gs1Pane.class.getDeclaredField("requests");field.setAccessible(true);return (ListView<Gs1Repository.Request>)field.get(pane);}
    private void install(Shop owner)throws Exception{
        var method=Gs1Pane.class.getDeclaredMethod("context",Shop.class);method.setAccessible(true);
        field("shop",owner);field("currentContext",method.invoke(null,owner));field("inn","7700000000");field("environment","production");
    }
    private Gs1ReadinessService.State agreementState()throws Exception{
        var method=Gs1Pane.class.getDeclaredMethod("readiness");method.setAccessible(true);
        return ((Gs1ReadinessService.Snapshot)method.invoke(pane)).check(Gs1ReadinessService.Kind.AGREEMENT).state();
    }
    private Gs1ReadinessService.State agreementStateUnchecked(){try{return agreementState();}catch(Exception failure){throw new AssertionError(failure);}}
    private String agreementErrorUnchecked(){try{return (String)invoke("agreementError",new Class<?>[]{});}catch(Exception failure){throw new AssertionError(failure);}}
    private Object value(String name)throws Exception{var field=Gs1Pane.class.getDeclaredField(name);field.setAccessible(true);return field.get(pane);}
    private Object invoke(String name,Class<?>[] parameters,Object... arguments)throws Exception{var method=Gs1Pane.class.getDeclaredMethod(name,parameters);method.setAccessible(true);return method.invoke(pane,arguments);}
    private void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<end){if(fx(condition::getAsBoolean))return;Thread.sleep(10);}
        fail("Expected scoped agreement read result was not reached");
    }
    /** Exercise the real durable service/context boundary without authenticating or signing. */
    private Gs1AgreementService readOnlyAgreements(Callable<List<OperatorAgreement>> read)throws Exception{
        Class<?> backend=Class.forName(Gs1AgreementService.class.getName()+"$Backend"),factory=Class.forName(Gs1AgreementService.class.getName()+"$BackendFactory");
        Object source=java.lang.reflect.Proxy.newProxyInstance(backend.getClassLoader(),new Class<?>[]{backend},(proxy,method,arguments)->{
            if(method.getName().equals("list"))return read.call();
            throw new AssertionError("A readiness check cannot request a PDF, sign, or submit a document: "+method.getName());
        });
        Object opener=java.lang.reflect.Proxy.newProxyInstance(factory.getClassLoader(),new Class<?>[]{factory},(proxy,method,arguments)->source);
        var constructor=Gs1AgreementService.class.getDeclaredConstructor(Gs1Repository.class,factory);constructor.setAccessible(true);
        return constructor.newInstance(new Gs1Repository(),opener);
    }
    private void field(String name,Object value)throws Exception{var field=Gs1Pane.class.getDeclaredField(name);field.setAccessible(true);field.set(pane,value);}
    private void render()throws Exception{var method=Gs1Pane.class.getDeclaredMethod("render");method.setAccessible(true);method.invoke(pane);pane.applyCss();pane.layout();}
    static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
}
