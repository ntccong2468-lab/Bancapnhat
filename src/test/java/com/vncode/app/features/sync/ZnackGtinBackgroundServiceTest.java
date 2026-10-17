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
 final Shop shop=new Shop(1,"Fixture","fixture");
 static class Operations implements ZnackGtinBackgroundService.Operations {
  List<SyncRunRepository.Item> items=new ArrayList<>();int executions,links;List<Integer> batches=new ArrayList<>();boolean unknown;
  Operations(int count){for(int i=0;i<count;i++)items.add(new SyncRunRepository.Item("i"+i,"run","p"+i,"v"+i,"sku","article","Black","52","04610689132457","AWAITING_CONFIRMATION","REVIEW","","","{}"));}
  public String prepare(Shop s){return "run";}
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
  assertEquals(650,result.progress().total());assertEquals(650,result.progress().succeeded());assertEquals(7,operations.executions);assertEquals(7,operations.links);assertTrue(operations.batches.stream().allMatch(n->n<=100));assertEquals(650,pulses.getLast().completed());
 }
 @Test void explicitSelectionCannotWriteOtherVariantsOrShops()throws Exception{
  var operations=new Operations(650);var key=new ProductKey(1,shop.getMarketplace(),"p450","v450");var result=new ZnackGtinBackgroundService(operations).run(shop,Set.of(key),p->{});assertEquals(1,result.progress().total());assertEquals(1,operations.executions);
  assertThrows(SecurityException.class,()->new ZnackGtinBackgroundService(operations).run(shop,Set.of(new ProductKey(2,shop.getMarketplace(),"p450","v450")),p->{}));assertEquals(1,operations.executions);
 }
 @Test void uncertainWriteIsNeverAutomaticallyReplayed()throws Exception{
  var operations=new Operations(2);operations.unknown=true;assertThrows(java.io.IOException.class,()->new ZnackGtinBackgroundService(operations).run(shop,Set.of(),p->{}));assertEquals(1,operations.executions);assertEquals(0,operations.links);
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
