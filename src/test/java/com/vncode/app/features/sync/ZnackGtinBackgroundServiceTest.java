package com.vncode.app.features.sync;
import com.vncode.app.features.catalogsync.CatalogSyncModels.*;
import com.vncode.app.features.gtinsync.GtinSyncModels;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ZnackGtinBackgroundServiceTest {
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
 String previousDirectory;
 @org.junit.jupiter.api.BeforeEach void isolateData()throws Exception{
  previousDirectory=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",directory.toString());
  com.vncode.app.config.Database.initDatabase();
 }
 @org.junit.jupiter.api.AfterEach void restoreData(){
  if(previousDirectory==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previousDirectory);
 }
 final Shop shop=new Shop(1,"Fixture","fixture");
 static class Operations implements ZnackGtinBackgroundService.Operations {
  List<SyncRunRepository.Item> items=new ArrayList<>();int executions,links;List<Integer> batches=new ArrayList<>();boolean unknown;
  Operations(int count){for(int i=0;i<count;i++)items.add(new SyncRunRepository.Item("i"+i,"run","p"+i,"v"+i,"sku","article","Black","52","04610689132457","AWAITING_CONFIRMATION","REVIEW","","","{}"));}
  public String prepare(Shop s)throws Exception{return "run";}
  public List<SyncRunRepository.Item> page(Shop s,String run,int offset){return List.copyOf(items.subList(Math.min(offset,items.size()),Math.min(offset+300,items.size())));}
  public List<Selection> select(Shop s,String run,List<SyncRunRepository.Item> rows){batches.add(rows.size());return rows.stream().map(i->new Selection(new GtinSyncModels.ProductKey(s.getId(),s.getMarketplace(),i.productId(),i.variantId()),i.gtin())).toList();}
  Job current;
  public Job preview(Shop s,List<Selection> selection){var rows=selection.stream().map(i->new Row(i.key().productId(),i.key(),null,List.of(),List.of(),null,Map.of(),"","",State.READY,"")).toList();return current=new Job(UUID.randomUUID().toString(),s.getId(),s.getMarketplace().name(),"fixture",Set.of(Field.GTIN),Instant.now(),false,false,rows);}
  public Job execute(Shop s,String id)throws Exception{executions++;if(unknown)throw new java.io.IOException("uncertain send");return new Job(current.id(),current.shopId(),current.marketplace(),current.owner(),current.fields(),current.createdAt(),true,false,current.rows().stream().map(r->r.next(State.SUCCEEDED,"","")).toList());}
  public void link(Shop s,String run,Job j){assertTrue(j.confirmed());assertEquals(Set.of(Field.GTIN),j.fields());links++;}
 }
 @Test void emptyOrUnmatchedSelectionNeverReportsAnUpdateAsSuccessful() {
  var marked=new java.util.concurrent.atomic.AtomicInteger();
  var operations=new Operations(3){public void noEligible(Shop s,String run){marked.incrementAndGet();}};
  var wrong=new ProductKey(1,shop.getMarketplace(),"absent","absent");
  var error=assertThrows(IllegalStateException.class,()->new ZnackGtinBackgroundService(operations).run(shop,Set.of(wrong),p->{throw new AssertionError("No successful progress is expected");}));
  assertEquals("GTIN_NO_ELIGIBLE_PUBLISHED_CARDS",error.getMessage());assertEquals(1,marked.get());assertEquals(0,operations.executions);assertEquals(0,operations.links);
  operations.items.clear();assertThrows(IllegalStateException.class,()->new ZnackGtinBackgroundService(operations).run(shop,Set.of(),p->{}));assertEquals(2,marked.get());
 }
 @Test void processesEveryPageInBoundedChildrenAndReportsActualProgress()throws Exception{
  var operations=new Operations(650);var pulses=new ArrayList<ZnackGtinBackgroundService.Progress>();var result=new ZnackGtinBackgroundService(operations).run(shop,Set.of(),pulses::add);
  assertEquals(650,result.progress().total());assertEquals(650,result.progress().succeeded());assertEquals(650,operations.executions);assertEquals(650,operations.links);assertTrue(operations.batches.stream().allMatch(n->n==1));assertEquals(651,pulses.size());assertEquals(1,pulses.get(1).succeeded());assertEquals(650,pulses.getLast().completed());
 }
 @Test void oneInvalidVariantDoesNotPreventValidVariantsFromBeingWritten()throws Exception{
  var invalid=new ArrayList<String>();
  var operations=new Operations(3){
   public List<Selection> select(Shop s,String run,List<SyncRunRepository.Item> rows){if(rows.getFirst().productId().equals("p0"))throw new IllegalArgumentException("GTIN_VARIANT_CONFLICT");return super.select(s,run,rows);}
   public void invalid(Shop s,String run,SyncRunRepository.Item row,Exception error){invalid.add(row.productId());}
  };
  var result=new ZnackGtinBackgroundService(operations).run(shop,Set.of(),p->{});
  assertEquals(List.of("p0"),invalid);assertEquals(3,result.progress().completed());assertEquals(2,result.progress().succeeded());assertEquals(1,result.progress().blocked());assertEquals(2,operations.executions);
 }
 @Test void explicitSelectionCannotWriteOtherVariantsOrShops()throws Exception{
  var operations=new Operations(650);var key=new ProductKey(1,shop.getMarketplace(),"p450","v450");var result=new ZnackGtinBackgroundService(operations).run(shop,Set.of(key),p->{});assertEquals(1,result.progress().total());assertEquals(1,operations.executions);
  assertThrows(SecurityException.class,()->new ZnackGtinBackgroundService(operations).run(shop,Set.of(new ProductKey(2,shop.getMarketplace(),"p450","v450")),p->{}));assertEquals(1,operations.executions);
 }
 @Test void uncertainWriteIsNeverAutomaticallyReplayed()throws Exception{
  var operations=new Operations(2);operations.unknown=true;assertThrows(java.io.IOException.class,()->new ZnackGtinBackgroundService(operations).run(shop,Set.of(),p->{}));assertEquals(1,operations.executions);assertEquals(0,operations.links);
 }
 @Test void failedSourceSigningStopsBeforeAnyGtinWriteAndReportsTheActualCause() {
  for(var code:com.vncode.app.integration.znack.signature.CryptoProErrorCode.values()){
   var crypto=new com.vncode.app.integration.znack.signature.CryptoProException(code,"local signing failure");
   var operations=new Operations(2){
    public String prepare(Shop s)throws Exception{throw new java.io.IOException("Catalog certificate signing failed",crypto);}
    public List<SyncRunRepository.Item> page(Shop s,String r,int offset){throw new AssertionError("Source preparation failed");}
   };
   var phases=new ArrayList<String>();
   var error=assertThrows(ZnackGtinBackgroundService.PreparationException.class,
       ()->new ZnackGtinBackgroundService(operations).run(shop,Set.of(),p->fail("No write progress expected"),phases::add));
   assertSame(crypto,error.getCause().getCause());assertEquals(List.of("PREPARING"),phases);
   assertEquals(0,operations.executions);assertEquals(0,operations.links);assertTrue(operations.batches.isEmpty());
   var failure=SyncFailure.of(error,0);assertEquals("CRYPTOPRO_"+code.name(),failure.code());assertTrue(failure.actionRequired());
   var i18n=com.vncode.app.shared.I18nService.getInstance();var previous=i18n.getCurrentLanguage();
   try{for(var language:com.vncode.app.shared.AppLanguage.values()){
    i18n.setLanguage(language);String text=com.vncode.app.shared.FriendlyErrorService.format(error);
    assertTrue(text.contains(i18n.tr("gtin.background.prepare_failed")));assertFalse(text.contains("gtin.background.prepare_failed"));
    assertTrue(text.contains("[CRYPTOPRO_"+code.name()+"]"));
   }}finally{i18n.setLanguage(previous);}
  }
 }
 @Test void blockedValidationIsReportedAndNeverSent()throws Exception{
  var recorded=new java.util.concurrent.atomic.AtomicInteger();
  var operations=new Operations(3){
   public Job preview(Shop s,List<Selection> selection){var job=super.preview(s,selection);return new Job(job.id(),job.shopId(),job.marketplace(),job.owner(),job.fields(),job.createdAt(),false,false,job.rows().stream().map(r->r.next(State.BLOCKED,"","SOURCE_AMBIGUOUS")).toList());}
   public void blocked(Shop s,String run,List<SyncRunRepository.Item> rows,Job job){recorded.addAndGet(job.rows().size());}
  };
  var result=new ZnackGtinBackgroundService(operations).run(shop,Set.of(),p->{});assertEquals(3,result.progress().blocked());assertEquals(3,recorded.get());assertEquals(0,operations.executions);assertEquals(0,operations.links);
 }

}
