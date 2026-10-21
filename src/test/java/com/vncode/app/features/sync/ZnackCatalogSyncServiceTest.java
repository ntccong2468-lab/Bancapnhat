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
 ZnackRepository seedProjection()throws Exception{
  var repository=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));
  for(String gtin:List.of(GtinWriteTestFixture.GTIN,"00000000000024")){
   repository.upsertProducts(List.of(new ZnackModels.Product(gtin,"Synthetic","6112110000","CONFORMITY_DECLARATION","OLD","2025-01-01",null)));
   repository.updateProductDocuments(gtin,List.of(new ZnackModels.GoodsDocument("CONFORMITY_DECLARATION","OLD","2025-01-01")));
   repository.updateProductLabelMetadata(gtin,"old","M");
  }
  return repository;
 }
 GtinSyncModels.RegisteredGtin metadataRow(String gtin,boolean documents){
  var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("title",List.of("Synthetic"),"gender",List.of("Мужской")),List.of(),documents?List.of(new com.vncode.app.features.catalogsync.CatalogProductData.Document("DECLARATION","NEW","2026-01-01","",true,"",Map.of())):List.of());
  return new GtinSyncModels.RegisteredGtin(gtin,"Synthetic article","Blue","52",true,true,RegisteredGtinProof.source(shop),Instant.now(),details);
 }
 @Test void aProjectionFailureCannotCommitHalfTheDocumentsAndLabels()throws Exception{
  seedProjection();
  try(var c=Database.getConnection();var s=c.createStatement()){
   s.execute("CREATE TRIGGER reject_metadata BEFORE UPDATE OF gender ON znack_products WHEN NEW.gtin='00000000000024' BEGIN SELECT RAISE(ABORT,'synthetic metadata failure'); END");
  }
  var rows=List.of(metadataRow(GtinWriteTestFixture.GTIN,true),metadataRow("00000000000024",true));
  assertThrows(RuntimeException.class,()->ZnackCatalogSyncService.project(shop,rows));
  try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT gender,size,certificate_number,permit_documents_json FROM znack_products")){
   int count=0;while(r.next()){count++;assertEquals("old",r.getString(1));assertEquals("M",r.getString(2));assertEquals("OLD",r.getString(3));assertTrue(r.getString(4).contains("OLD"));}assertEquals(2,count);
  }
  try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DROP TRIGGER reject_metadata");}
  ZnackCatalogSyncService.project(shop,rows);
  try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM znack_products WHERE gender='Мужской' AND size='52' AND certificate_number='NEW'")){assertEquals(2,r.getInt(1));}
 }
 @Test void missingPermitObservationPreservesSavedDocumentsWhileRefreshingLabels()throws Exception{
  var repository=seedProjection();ZnackCatalogSyncService.project(shop,List.of(metadataRow(GtinWriteTestFixture.GTIN,false)));
  assertEquals("OLD",repository.findActiveProduct(GtinWriteTestFixture.GTIN).orElseThrow().certificateNumber());
  try(var c=Database.getConnection();var p=c.prepareStatement("SELECT gender,size,permit_documents_json FROM znack_products WHERE gtin=?")){
   p.setString(1,GtinWriteTestFixture.GTIN);try(var r=p.executeQuery()){assertEquals("Мужской",r.getString(1));assertEquals("52",r.getString(2));assertTrue(r.getString(3).contains("OLD"));}
  }
 }
 @Test void aChangedSigningIdentityCannotReceiveALateMetadataBatch()throws Exception{
  var repository=seedProjection();var expected=repository.getSettings();
  GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
  var row=new ZnackRepository.CatalogMetadata(GtinWriteTestFixture.GTIN,true,List.of(new ZnackModels.GoodsDocument("CONFORMITY_DECLARATION","NEW","2026-01-01")),"Мужской","52");
  assertThrows(ZnackRepository.StaleSignerException.class,()->repository.updateCatalogMetadata(List.of(row),expected));
  assertEquals("OLD",repository.findActiveProduct(GtinWriteTestFixture.GTIN).orElseThrow().certificateNumber());
 }
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
  assertEquals("AUTHENTICATING",run.step(),"A failed login must retain the exact failed phase");
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
