package com.vncode.app.features.sync;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.znack.CatalogRotationKeys;
import com.vncode.app.integration.znack.CatalogReadRevision;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class CatalogKeyRotationTest {
 @TempDir Path root;Shop shop;
 @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);CatalogRotationKeys.save(shop,"synthetic-A");}
 @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
 RegistrationSources paired(){return new RegistrationSources((s,p)->p.accept(List.of(new RegistrationSources.Member("101","201","","A","Blue","M"))),s->new ZnackCatalogSyncService.Result(List.of(),List.of(),"synthetic"));}
 @Test void verifiedDifferentKeyInvalidatesPairedReceiptButPreservesDisplayAndPhysicalIdentity()throws Exception{
  var pair=paired();String run=pair.refresh(shop,s->{}).run();assertTrue(pair.check(shop).ready());String source=RegisteredGtinProof.source(shop);
  var before=new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace());
  CatalogRotationKeys.save(shop,"synthetic-B");
  assertFalse(pair.check(shop).ready(),"A changed Catalog read credential needs a fresh paired source read");
  assertEquals(run,pair.display(shop).run());assertEquals(source,RegisteredGtinProof.source(shop));assertEquals(before,new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()));
 }
 @Test void identicalVerifiedKeyRetainsReadyReceiptAndShortCache()throws Exception{
  var pair=paired();String run=pair.refresh(shop,s->{}).run();var calls=new AtomicInteger();var read=new ZnackCatalogSyncService((s,i,p)->{calls.incrementAndGet();return List.of();});
  read.refresh(shop,false,p->{});CatalogRotationKeys.save(shop,"synthetic-A");assertEquals(run,pair.require(shop).run());read.refresh(shop,false,p->{});assertEquals(1,calls.get());
 }
 @Test void differentVerifiedKeyBypassesShortCache()throws Exception{
  var calls=new AtomicInteger();var read=new ZnackCatalogSyncService((s,i,p)->{calls.incrementAndGet();return List.of();});
  read.refresh(shop,false,p->{});CatalogRotationKeys.save(shop,"synthetic-B");read.refresh(shop,false,p->{});assertEquals(2,calls.get());
 }
 @Test void abaRotationCannotResurrectOldReceiptOrShortCache()throws Exception{
  var pair=paired();pair.refresh(shop,p->{});var calls=new AtomicInteger();var read=new ZnackCatalogSyncService((s,i,p)->{calls.incrementAndGet();return List.of();});
  read.refresh(shop,false,p->{});CatalogRotationKeys.save(shop,"synthetic-B");CatalogRotationKeys.save(shop,"synthetic-A");
  assertFalse(pair.check(shop).ready());read.refresh(shop,false,p->{});assertEquals(2,calls.get());
 }
 @Test void rotationDuringPairedReadCannotCommitAnOldSuccess()throws Exception{
  var pair=new RegistrationSources((s,p)->{},s->{CatalogRotationKeys.save(s,"synthetic-B");return new ZnackCatalogSyncService.Result(List.of(),List.of(),"synthetic");});
  assertThrows(SecurityException.class,()->pair.refresh(shop,p->{}));assertFalse(pair.check(shop).ready());
 }
 @Test void lateOldReadCannotOverwriteTheNewSnapshotOrBeJoined()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
  var oldRow=new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()).getFirst();
  var read=new ZnackCatalogSyncService((s,i,p)->{if(calls.incrementAndGet()==1){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return List.of(oldRow);}return List.of();});
  try(var executor=Executors.newFixedThreadPool(2)){
   var old=executor.submit(()->read.refresh(shop,true,p->{}));assertTrue(entered.await(5,TimeUnit.SECONDS));
   CatalogRotationKeys.save(shop,"synthetic-B");var fresh=executor.submit(()->read.refresh(shop,true,p->{}));
   try{assertTrue(fresh.get(5,TimeUnit.SECONDS).cards().isEmpty(),"Fresh epoch must read independently of the old flight");}finally{release.countDown();}
   assertThrows(ExecutionException.class,()->old.get(5,TimeUnit.SECONDS));assertTrue(new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()).isEmpty());assertEquals(2,calls.get());
  }finally{release.countDown();}
 }
 @Test void removalInvalidatesReadinessWithoutDeletingHistory()throws Exception{
  var pair=paired();String run=pair.refresh(shop,p->{}).run();CatalogRotationKeys.remove(shop);assertFalse(pair.check(shop).ready());assertEquals(run,pair.display(shop).run());
 }
 @Test void abaRotationCannotCompleteAnOldFlightEvenWhenTheSavedKeyMatchesAgain()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  var read=new ZnackCatalogSyncService((s,i,p)->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return List.of();});
  try(var executor=Executors.newSingleThreadExecutor()){
   var old=executor.submit(()->read.refresh(shop,true,p->{}));assertTrue(entered.await(5,TimeUnit.SECONDS));
   try{CatalogRotationKeys.save(shop,"synthetic-B");CatalogRotationKeys.save(shop,"synthetic-A");}finally{release.countDown();}
   var failed=assertThrows(ExecutionException.class,()->old.get(5,TimeUnit.SECONDS));assertEquals("SOURCE_CONFIGURATION_CHANGED",failed.getCause().getMessage());
  }finally{release.countDown();}
 }
 @Test void aJoinedSubscriberRechecksTheEpochAfterReceivingAnAlreadyCompletedOldResult()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var joined=new CountDownLatch(1);var resumeJoin=new CountDownLatch(1);
  var read=new ZnackCatalogSyncService((s,i,p)->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return List.of();});
  try(var executor=Executors.newFixedThreadPool(2)){
   var first=executor.submit(()->read.refresh(shop,true,p->{}));assertTrue(entered.await(5,TimeUnit.SECONDS));
   var second=executor.submit(()->read.refresh(shop,true,p->{if(p.phase().equals("WAITING_SOURCE")){joined.countDown();try{assertTrue(resumeJoin.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}}));
   try{assertTrue(joined.await(5,TimeUnit.SECONDS));release.countDown();first.get(5,TimeUnit.SECONDS);CatalogRotationKeys.save(shop,"synthetic-B");}finally{release.countDown();resumeJoin.countDown();}
   var failed=assertThrows(ExecutionException.class,()->second.get(5,TimeUnit.SECONDS));assertEquals("SOURCE_CONFIGURATION_CHANGED",failed.getCause().getMessage());
  }finally{release.countDown();resumeJoin.countDown();}
 }
 @Test void catalogOnlyReadCannotRestoreNewWriteProofBeforeThePairCompletes()throws Exception{
  var original=new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()).getFirst();
  var read=new ZnackCatalogSyncService((s,i,p)->List.of(original));read.refresh(shop,true,p->{});
  assertEquals("SOURCE_SYNC_REQUIRED",assertThrows(IllegalStateException.class,()->RegisteredGtinProof.requireCurrent(shop,original.gtin())).getMessage());
  assertNull(ZnackAutoSyncService.currentCachedCatalog(shop));
  paired().refresh(shop,p->{});assertDoesNotThrow(()->RegisteredGtinProof.requireCurrent(shop,original.gtin()));
  assertDoesNotThrow(()->RegisteredGtinProof.requireCurrent(shop,List.of(original.gtin())));assertNotNull(ZnackAutoSyncService.currentCachedCatalog(shop));
  CatalogRotationKeys.save(shop,"synthetic-B");assertNull(ZnackAutoSyncService.currentCachedCatalog(shop));
  assertThrows(IllegalStateException.class,()->RegisteredGtinProof.requireCurrent(shop,original.gtin()));
  assertThrows(IllegalStateException.class,()->com.vncode.app.features.kiz.KizPurchaseMappingProof.requireKnownPermit(shop,List.of(original.gtin())));
  assertTrue(RegisteredGtinProof.currentObservation(original,java.time.Instant.now()));
 }
 @Test void initialConnectionPreparationCanSaveAKeyBeforeServiceAndPairCaptureTheirEpoch()throws Exception{
  CatalogRotationKeys.remove(shop);
  var source=new ZnackCatalogSyncService.Source(){
   public void prepare(Shop s)throws Exception{CatalogRotationKeys.save(s,"synthetic-A");}
   public List<GtinSyncModels.RegisteredGtin> read(Shop s,java.util.function.Consumer<com.vncode.app.integration.znack.registration.NationalCatalogGtinSource.CardIssue> i,java.util.function.Consumer<com.vncode.app.integration.znack.registration.NationalCatalogGtinSource.Progress> p){return List.of();}
  };
  assertDoesNotThrow(()->new ZnackCatalogSyncService(source).refresh(shop,true,p->{}));
  CatalogRotationKeys.remove(shop);
  var catalog=new RegistrationSources.CatalogReader(){
   public void prepare(Shop s)throws Exception{CatalogRotationKeys.save(s,"synthetic-A");}
   public ZnackCatalogSyncService.Result read(Shop s){return new ZnackCatalogSyncService.Result(List.of(),List.of(),"synthetic");}
  };
  assertTrue(new RegistrationSources((s,p)->{},catalog).refresh(shop,p->{}).ready());assertTrue(CatalogReadRevision.complete(shop.getId()));
 }
 @Test void failedConnectionPreparationInvalidatesReadinessAndRetainsHistoricalMembership()throws Exception{
  var pair=paired();String run=pair.refresh(shop,p->{}).run();
  var failing=new RegistrationSources((s,p)->fail("Preparation failed before marketplace read"),new RegistrationSources.CatalogReader(){
   public void prepare(Shop s)throws Exception{throw new java.io.IOException("synthetic authentication unavailable");}
   public ZnackCatalogSyncService.Result read(Shop s){throw new AssertionError("Preparation failed before Catalog read");}
  });
  assertThrows(java.io.IOException.class,()->failing.refresh(shop,p->{}));assertFalse(pair.check(shop).ready());assertEquals(run,pair.display(shop).run());
 }
 @Test void aSuccessReceiptWithoutItsCompletedEpochRetainsHistoryAndForcesTheNextReviewToReadBothSources()throws Exception{
  String revision=CatalogReadRevision.current(shop.getId());
  var card=new GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"Fixture","Blue","M",true,true,RegisteredGtinProof.source(shop),java.time.Instant.now(),new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of()));
  var observed=CatalogReadRevision.observed(List.of(card),revision);new GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),observed);
  try(var c=Database.getConnection();var sql=c.createStatement()){
   sql.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,synced_at) VALUES(1,101,'Synthetic','Fixture','now')");
   sql.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,201,101,'M')");
   sql.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,14177449,'Цвет','[\"Blue\"]')");
  }
  var market=new AtomicInteger();var catalog=new AtomicInteger();
  var sources=new RegistrationSources((s,sink)->{market.incrementAndGet();sink.accept(List.of(new RegistrationSources.Member("101","201","","Fixture","Blue","M")));},s->{catalog.incrementAndGet();return new ZnackCatalogSyncService.Result(observed,List.of(),"synthetic");});
  var ready=sources.refresh(shop,p->{});assertTrue(ready.ready());assertTrue(CatalogReadRevision.complete(shop.getId()));
  try(var c=Database.getConnection();var p=c.prepareStatement("DELETE FROM app_config WHERE key=?")){p.setString(1,"catalog.read.revision."+shop.getId()+".complete");assertEquals(1,p.executeUpdate());}
  assertFalse(sources.check(shop).ready(),"A crash between receipt success and completed epoch must require paired recovery");
  assertEquals("SOURCE_SYNC_REQUIRED",sources.check(shop).reason());assertEquals(ready.run(),sources.display(shop).run());
  var review=new ZnackGtinReviewService(sources,s->{throw new AssertionError("Paired recovery must refresh both sources");});
  assertFalse(review.prepare(shop).isBlank());assertEquals(2,market.get());assertEquals(2,catalog.get());
  assertTrue(CatalogReadRevision.complete(shop.getId()));assertTrue(sources.check(shop).ready());assertNotEquals(ready.run(),sources.check(shop).run());assertEquals(revision,CatalogReadRevision.current(shop.getId()));
 }
 @Test void staleDirectNonemptyAndEmptyResultsCannotOverwriteFreshDataAfterTheNewPairCompletes()throws Exception{
  var repository=new GtinSyncRepository();var physical=repository.catalog(shop.getId(),shop.getMarketplace()).getFirst();
  var old=CatalogReadRevision.observed(List.of(physical),CatalogReadRevision.current(shop.getId()));
  var oldEmpty=CatalogReadRevision.observed(List.<GtinSyncModels.RegisteredGtin>of(),CatalogReadRevision.current(shop.getId()));
  CatalogRotationKeys.save(shop,"synthetic-B");var fresh=CatalogReadRevision.observed(List.of(physical),CatalogReadRevision.current(shop.getId()));
  repository.saveCatalog(shop.getId(),shop.getMarketplace(),fresh);paired().refresh(shop,p->{});assertTrue(CatalogReadRevision.complete(shop.getId()));
  assertThrows(IllegalStateException.class,()->repository.mergeCatalog(shop.getId(),shop.getMarketplace(),old));
  assertThrows(IllegalStateException.class,()->repository.saveCatalog(shop.getId(),shop.getMarketplace(),old));
  assertThrows(IllegalStateException.class,()->repository.saveCatalog(shop.getId(),shop.getMarketplace(),oldEmpty));
  assertThrows(IllegalStateException.class,()->repository.saveCatalog(shop.getId(),shop.getMarketplace(),List.of()));
  assertEquals(fresh,repository.catalog(shop.getId(),shop.getMarketplace()));assertDoesNotThrow(()->RegisteredGtinProof.requireCurrent(shop,physical.gtin()));
 }
 @Test void aLateDirectProjectionCannotOverwriteNewEpochProductAndPermitMetadata()throws Exception{
  var physical=new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()).getFirst();
  var old=CatalogReadRevision.observed(List.of(projectionCard(physical,"OLD","6109100000")),CatalogReadRevision.current(shop.getId()));
  CatalogRotationKeys.save(shop,"synthetic-B");
  var fresh=CatalogReadRevision.observed(List.of(projectionCard(physical,"NEW","6112110000")),CatalogReadRevision.current(shop.getId()));
  new GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),fresh);ZnackCatalogSyncService.project(shop,fresh);paired().refresh(shop,p->{});
  var repository=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(shop.getId(),shop.getName()));
  var before=repository.findActiveProduct(physical.gtin()).orElseThrow();assertEquals("NEW",before.certificateNumber());
  assertThrows(IllegalStateException.class,()->ZnackCatalogSyncService.project(shop,old));assertEquals(before,repository.findActiveProduct(physical.gtin()).orElseThrow());
 }
 private GtinSyncModels.RegisteredGtin projectionCard(GtinSyncModels.RegisteredGtin physical,String document,String tnved){
  var fields=new LinkedHashMap<>(physical.details().fields());fields.put("tnved",List.of(tnved));fields.put("title",List.of(document));
  var permit=new com.vncode.app.features.catalogsync.CatalogProductData.Document("DECLARATION",document,"2020-01-01","",true,"",Map.of("registryStatus","active"));
  var details=new com.vncode.app.features.catalogsync.CatalogProductData(fields,List.of(),List.of(permit));
  return new GtinSyncModels.RegisteredGtin(physical.gtin(),physical.article(),physical.color(),physical.size(),physical.tradeUnit(),physical.published(),physical.source(),physical.syncedAt(),details);
 }
 @Test void rotationKeepsExistingStockResolutionMappingsAndPhysicalCardContent()throws Exception{
  var repository=new GtinSyncRepository();String gtin=GtinWriteTestFixture.GTIN;
  try(var c=Database.getConnection();var sql=c.createStatement()){
   sql.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,synced_at) VALUES(1,101,'Fixture','fixture')");
   sql.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'M')");
   sql.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,14177449,'Цвет','[\"Black\"]')");
   sql.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'M-SKU'),(1,11,'"+gtin+"')");
  }
  var original=repository.catalog(shop.getId(),shop.getMarketplace()).getFirst();
  var physical=new GtinSyncModels.RegisteredGtin(gtin,"Fixture","Black","M",true,true,original.source(),original.syncedAt(),original.details());
  var observed=CatalogReadRevision.observed(List.of(physical),CatalogReadRevision.current(shop.getId()));repository.saveCatalog(shop.getId(),shop.getMarketplace(),observed);
  var key=new GtinSyncModels.ProductKey(shop.getId(),shop.getMarketplace(),"101","11");repository.saveMapping(key,gtin);
  var snapshot=new GtinSyncModels.ProductSnapshot(key,"Fixture","Black","M",List.of(),"synthetic-physical-fingerprint",java.time.Instant.now());
  var job=repository.createJob(List.of(new GtinSyncModels.Preview(snapshot,GtinSyncModels.Operation.ADD,"",gtin,new GtinSyncModels.Capability(true,false,""))));var jobBefore=repository.jobItems(job);
  var product=new com.vncode.app.features.kiz.KizPurchaseMappingProof.Product("101","M-SKU",gtin);
  var stockBefore=com.vncode.app.features.kiz.KizPurchaseMappingProof.resolve(shop,List.of(product));assertEquals(gtin,stockBefore.get(product));
  var evidenceBefore=com.vncode.app.integration.listing.ListingIdentity.verifiedGtinEvidence(shop,new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),"101","11"),List.of(gtin),"Fixture","Black","M","").orElseThrow();
  CatalogRotationKeys.save(shop,"synthetic-B");
  assertEquals(stockBefore,com.vncode.app.features.kiz.KizPurchaseMappingProof.resolve(shop,List.of(product)));assertEquals(gtin,repository.mapping(key));
  var reread=CatalogReadRevision.observed(List.of(physical),CatalogReadRevision.current(shop.getId()));repository.saveCatalog(shop.getId(),shop.getMarketplace(),reread);paired().refresh(shop,p->{});
  assertEquals(physical.details(),reread.getFirst().details());assertEquals(physical.source(),reread.getFirst().source());
  var evidenceAfter=com.vncode.app.integration.listing.ListingIdentity.verifiedGtinEvidence(shop,new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),"101","11"),List.of(gtin),"Fixture","Black","M","").orElseThrow();
  assertEquals(evidenceBefore,evidenceAfter);assertEquals(stockBefore,com.vncode.app.features.kiz.KizPurchaseMappingProof.resolve(shop,List.of(product)));
  assertEquals(jobBefore,repository.jobItems(job));
 }

}
