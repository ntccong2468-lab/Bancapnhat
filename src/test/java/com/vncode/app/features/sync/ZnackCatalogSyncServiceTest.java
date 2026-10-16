package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.NationalCatalogGtinSource;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ZnackCatalogSyncServiceTest {
 @TempDir Path root;Shop shop;
 @BeforeEach void init()throws Exception{shop=device(root.resolve("one"));}
 Shop device(Path path)throws Exception{Files.createDirectories(path);System.setProperty("vncode.appdata.dir",path.toString());Database.initDatabase();var s=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);new GtinSyncRepository().saveCatalog(s.getId(),s.getMarketplace(),List.of());return s;}
 @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
 GtinSyncModels.RegisteredGtin row(Shop s){return new GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"Synthetic article","Blue","M",true,true,RegisteredGtinProof.source(s),Instant.now());}
 @Test void publishedCardsWithoutPermitDocumentsStayVisibleAndSupplyTheGtinCache()throws Exception{
  var progress=new ArrayList<NationalCatalogGtinSource.Progress>();
  var service=new ZnackCatalogSyncService((s,issues,p)->{p.accept(new NationalCatalogGtinSource.Progress("READING_CARDS",1,1));return List.of(row(s));});
  var result=service.refresh(shop,true,progress::add);assertEquals(1,result.cards().size());
  assertEquals(1,new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()).size());
  var znack=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));
  assertEquals(1,znack.findProducts().size());assertTrue(znack.findDeletedProducts().isEmpty());
  assertEquals("SUCCEEDED",new SyncRunRepository().find(result.runId()).orElseThrow().state());assertFalse(progress.isEmpty());
 }
 @Test void independentSecondMachineDoesNotNeedFirstMachinesCatalogOrRegistrationCache()throws Exception{
  var calls=new AtomicInteger();var service=new ZnackCatalogSyncService((s,issues,p)->{calls.incrementAndGet();return List.of(row(s));});
  service.refresh(shop,false,p->{});assertEquals(1,calls.get());
  Shop second=device(root.resolve("two"));assertTrue(new GtinSyncRepository().catalog(second.getId(),second.getMarketplace()).isEmpty());
  service.refresh(second,false,p->{});assertEquals(2,calls.get());assertEquals(1,new GtinSyncRepository().catalog(second.getId(),second.getMarketplace()).size());
 }
 @Test void failedSourceEndsReportAndDoesNotErasePreviouslyGoodCatalog()throws Exception{
  new GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),List.of(row(shop)));
  var service=new ZnackCatalogSyncService((s,i,p)->{throw new java.net.SocketTimeoutException("must not persist URL or token");});
  assertThrows(java.net.SocketTimeoutException.class,()->service.refresh(shop,true,p->{}));
  var run=new SyncRunRepository().list(shop.getId(),0,10).getFirst();assertEquals("FAILED",run.state());assertEquals("NETWORK_TIMEOUT",run.error());
  assertEquals(1,new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()).size());
 }
 @Test void aFailureWhileSavingTheFailureReportNeverReplacesTheOriginalSourceError()throws Exception{
  new GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),List.of(row(shop)));
  var original=new java.io.IOException("synthetic source failure");
  var service=new ZnackCatalogSyncService((s,i,p)->{
   try(var c=Database.getConnection();var sql=c.createStatement()){
    sql.execute("CREATE TRIGGER reject_failure_report BEFORE UPDATE ON sync_runs WHEN NEW.state='FAILED' BEGIN SELECT RAISE(ABORT,'synthetic audit failure'); END");
   }
   throw original;
  });
  var thrown=assertThrows(java.io.IOException.class,()->service.refresh(shop,true,p->{}));
  assertSame(original,thrown);assertEquals(1,thrown.getSuppressed().length);
  assertEquals("SYNC_STORAGE",thrown.getSuppressed()[0].getMessage());
  assertEquals(1,new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()).size());
 }
 @Test void partialReadsAreReportedAndNotReusedAsACompleteSnapshot()throws Exception{
  var calls=new AtomicInteger();var service=new ZnackCatalogSyncService((s,issues,p)->{calls.incrementAndGet();issues.accept(new NationalCatalogGtinSource.CardIssue("00000000000024","INVALID_CATALOG_CARD"));return List.of(row(s));});
  var first=service.refresh(shop,false,p->{});assertEquals("PARTIAL",new SyncRunRepository().find(first.runId()).orElseThrow().state());
  assertTrue(new SyncRunRepository().items(first.runId(),0,10).stream().anyMatch(i->i.state().equals("NEEDS_ACTION")&&i.error().equals("INVALID_CATALOG_CARD")));
  service.refresh(shop,false,p->{});assertEquals(2,calls.get());
 }
 @Test void uiAndBackgroundSubscribersReceiveTheSameResultFromOneRemoteRead()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var joined=new CountDownLatch(1);var calls=new AtomicInteger();
  var service=new ZnackCatalogSyncService((s,i,p)->{calls.incrementAndGet();entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return List.of(row(s));});
  try(var executor=Executors.newFixedThreadPool(2)){
   var first=executor.submit(()->service.refresh(shop,true,p->{}));assertTrue(entered.await(5,TimeUnit.SECONDS));
   var second=executor.submit(()->service.refresh(shop,true,p->{if(p.phase().equals("WAITING_SOURCE"))joined.countDown();}));
   try{assertTrue(joined.await(5,TimeUnit.SECONDS));assertFalse(second.isDone());}finally{release.countDown();}
   var a=first.get(10,TimeUnit.SECONDS);var b=second.get(10,TimeUnit.SECONDS);assertEquals(a.runId(),b.runId());assertEquals(a.cards(),b.cards());assertEquals(1,calls.get());
   assertEquals(1,new SyncRunRepository().list(shop.getId(),0,10).size());
  }
 }
 @Test void cancellingOneSubscriberDoesNotCancelTheBackgroundRead()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var joined=new CountDownLatch(1);var calls=new AtomicInteger();
  var service=new ZnackCatalogSyncService((s,i,p)->{calls.incrementAndGet();entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return List.of(row(s));});
  try(var executor=Executors.newFixedThreadPool(2)){
   var first=executor.submit(()->service.refresh(shop,true,p->{}));assertTrue(entered.await(5,TimeUnit.SECONDS));
   var second=executor.submit(()->service.refresh(shop,true,p->{if(p.phase().equals("WAITING_SOURCE"))joined.countDown();}));
   try{assertTrue(joined.await(5,TimeUnit.SECONDS));assertTrue(second.cancel(true));}finally{release.countDown();}
   assertEquals(1,first.get(10,TimeUnit.SECONDS).cards().size());assertEquals(1,calls.get());
  }
 }
 @Test void foreignObservationCannotBecomeMarketplaceWriteAuthority()throws Exception{
  var service=new ZnackCatalogSyncService((s,i,p)->List.of(new GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"A","B","M",true,true,"National Catalog@foreign",Instant.now())));
  assertThrows(SecurityException.class,()->service.refresh(shop,true,p->{}));assertTrue(new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()).isEmpty());
 }
}
