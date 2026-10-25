package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.registration.NationalCatalogGtinSource;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationSourcesTest {
    @TempDir Path directory;
    Shop shop;final RegistrationSources sources=new RegistrationSources();
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
    }
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    private ZnackCatalogSyncService.Result complete(){return new ZnackCatalogSyncService.Result(List.of(),List.of(),"synthetic");}
    private RegistrationSources reader(RegistrationSources.MarketReader market,RegistrationSources.CatalogReader catalog){return new RegistrationSources(market,catalog);}
    private String ready()throws Exception{return reader((s,page)->page.accept(List.of(new RegistrationSources.Member("101","201","","A","Blue","M"))),s->complete()).refresh(shop,step->{}).run();}
    @Test void savedMembershipSurvivesAWeekAndAnApplicationUpgradeWithoutAnotherApiRead()throws Exception{
        String run=ready();
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_runs SET finished_at=? WHERE id=?")){p.setString(1,Instant.now().minusSeconds(7*86400).toString());p.setString(2,run);p.executeUpdate();}
        try(var ignored=com.vncode.app.shared.LocalDataMigrationGate.prepare(directory,"1.5.16","javafx")){}
        try(var ignored=com.vncode.app.shared.LocalDataMigrationGate.prepare(directory,"1.5.17","javafx")){
            var saved=new RegistrationSources().requireDisplay(shop);assertEquals(run,saved.run());assertFalse(saved.ready());assertTrue(saved.hasSnapshot());
            assertEquals("SOURCE_SYNC_EXPIRED",saved.reason());assertEquals("101",new SyncRunRepository().items(run,0,300).getFirst().productId());
            assertThrows(IllegalStateException.class,()->new RegistrationSources().requireMember(shop,"101","201"));
        }
    }
    @Test void aFailedNewRefreshDoesNotReplaceTheSavedSuccessfulMembership()throws Exception{
        String run=ready();
        assertThrows(java.net.SocketTimeoutException.class,()->reader((s,p)->p.accept(List.of(new RegistrationSources.Member("999","999","","Partial","",""))),s->{throw new java.net.SocketTimeoutException();}).refresh(shop,step->{}));
        var saved=new RegistrationSources().requireDisplay(shop);assertEquals(run,saved.run());assertFalse(saved.ready());assertEquals("NETWORK_TIMEOUT",saved.reason());
        assertEquals(List.of("101"),new SyncRunRepository().items(saved.run(),0,300).stream().map(SyncRunRepository.Item::productId).toList());
        assertThrows(IllegalStateException.class,()->sources.require(shop));
    }
    @Test void displayCannotRestoreAFutureReceiptOrADifferentCertificateScope()throws Exception{
        String run=ready();try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_runs SET finished_at=? WHERE id=?")){p.setString(1,Instant.now().plusSeconds(600).toString());p.setString(2,run);p.executeUpdate();}
        assertFalse(sources.display(shop).hasSnapshot());
        GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);assertFalse(sources.display(shop).hasSnapshot());
    }
    @Test void oldLocalRowsCannotUnlockAListWithoutCompletedPairedReads(){assertFalse(sources.check(shop).ready());assertEquals("SOURCE_SYNC_REQUIRED",sources.check(shop).reason());}
    @Test void zeroProductsAndZeroCardsAreAValidCompletedReadNotAnUnknownState()throws Exception {
        var progress=new ArrayList<String>();var ready=reader((s,p)->assertFalse(sources.check(s).ready()),s->{assertFalse(sources.check(s).ready());return complete();}).refresh(shop,progress::add);
        assertTrue(ready.ready());assertEquals(List.of("READING_MARKETPLACE","READING_ZNACK","SOURCES_COMPLETE"),progress);assertTrue(new SyncRunRepository().items(ready.run(),0,300).isEmpty());
    }
    @Test void halfAReadNeverUnlocksRegistrationAndHasASafeFailureReport()throws Exception {
        assertThrows(java.net.SocketTimeoutException.class,()->reader((s,p)->p.accept(List.of(new RegistrationSources.Member("101","201","","A","Blue","M"))),s->{throw new java.net.SocketTimeoutException("secret response");}).refresh(shop,step->{}));
        var ready=sources.check(shop);assertFalse(ready.ready());assertEquals("READING_ZNACK",ready.step());assertEquals("NETWORK_TIMEOUT",ready.reason());assertFalse(new SyncRunRepository().find(ready.run()).orElseThrow().error().contains("secret"));
    }
    @Test void failedCatalogSigningHasAnActionableReportAndCannotUnlockGtinWrites() {
        var signing=new com.vncode.app.integration.znack.signature.CryptoProException(
                com.vncode.app.integration.znack.signature.CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE,"token=secret-diagnostic");
        assertThrows(java.io.IOException.class,()->reader((s,p)->{},s->{
            throw new java.io.IOException("Catalog certificate signing failed",signing);
        }).refresh(shop,step->{}));
        var ready=sources.check(shop);assertFalse(ready.ready());assertEquals("READING_ZNACK",ready.step());
        assertEquals("CRYPTOPRO_PRIVATE_KEY_UNAVAILABLE",ready.reason());
        var report=new SyncRunRepository().find(ready.run()).orElseThrow();assertEquals("FAILED",report.state());
        assertFalse(report.error().contains("secret-diagnostic"));
    }
    @Test void catalogCardIssuesLeaveThePairPartial()throws Exception {
        var partial=reader((s,p)->{},s->new ZnackCatalogSyncService.Result(List.of(),List.of(new NationalCatalogGtinSource.CardIssue(GtinWriteTestFixture.GTIN,"CATALOG_CARD_INVALID")),"test"));
        assertFalse(partial.refresh(shop,step->{}).ready());assertEquals("SOURCE_CATALOG_PARTIAL",sources.check(shop).reason());
    }
    @Test void failedNewReadInvalidatesAnEarlierSuccess()throws Exception {
        ready();assertTrue(sources.check(shop).ready());
        assertThrows(java.io.IOException.class,()->reader((s,p)->{throw new java.io.IOException();},s->complete()).refresh(shop,step->{}));assertFalse(sources.check(shop).ready());
    }
    @Test void scopeRotationAndCredentialRotationRequireAnotherRead()throws Exception {
        ready();GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        assertEquals("SOURCE_CONFIGURATION_CHANGED",sources.check(shop).reason());
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='other-fixture' WHERE id=1");}
        assertFalse(sources.check(shop).ready());assertEquals("SOURCE_SYNC_REQUIRED",sources.check(new Shop(1,"Fixture","other-fixture")).reason());
    }
    @Test void aDayOldOrFutureReceiptCannotAuthorizeAction()throws Exception {
        String run=ready();
        for(Instant instant:List.of(Instant.now().minusSeconds(86401),Instant.now().plusSeconds(600))){try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_runs SET finished_at=? WHERE id=?")){p.setString(1,instant.toString());p.setString(2,run);p.executeUpdate();}assertEquals("SOURCE_SYNC_EXPIRED",sources.check(shop).reason());}
    }
    @Test void requireMemberRejectsOldAndForeignProductKeys()throws Exception {
        ready();assertDoesNotThrow(()->sources.requireMember(shop,"101","201"));assertEquals("SOURCE_PRODUCT_NOT_OBSERVED",assertThrows(IllegalArgumentException.class,()->sources.requireMember(shop,"102","201")).getMessage());
    }
    @Test void duplicateNativeVariantsMakeTheReadFail() {
        var member=new RegistrationSources.Member("101","201","","","","");
        assertThrows(IllegalStateException.class,()->reader((s,p)->{p.accept(List.of(member));p.accept(List.of(member));},s->complete()).refresh(shop,step->{}));assertFalse(sources.check(shop).ready());
    }
    @Test void certificateChangeDuringReadCannotCommitSuccess() {
        assertThrows(SecurityException.class,()->reader((s,p)->GtinWriteTestFixture.saveSettings(s,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API),s->complete()).refresh(shop,step->{}));assertFalse(sources.check(shop).ready());
    }
    @Test void catalogReadDoesNotRunAfterMarketplaceFailure(){
        var called=new java.util.concurrent.atomic.AtomicBoolean();assertThrows(java.io.IOException.class,()->reader((s,p)->{throw new java.io.IOException();},s->{called.set(true);return complete();}).refresh(shop,step->{}));assertFalse(called.get());
    }
}
