package com.vncode.app.performance;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.features.ozon.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SellerWorkspacePerformanceTest {
 @TempDir Path root;
 @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");System.clearProperty("vncode.runtime.diagnostics");}
 @Test void twentyShopCyclesReleaseReadsAndStayWithinCacheBudget()throws Exception {exercise(4);}
 @Test void twentyOzonShopCyclesReleaseReadsAndStayWithinCacheBudget()throws Exception {exercise(10);}
 void exercise(int ozonShops)throws Exception {
  SellerWorkspaceFixture.create(root,ozonShops);System.setProperty("vncode.appdata.dir",root.toString());
  try(var c=Database.getConnection();var s=c.createStatement()){
   for(String sql:List.of("SELECT count(*) FROM shops","SELECT (SELECT count(*) FROM wb_product_cards)+(SELECT count(*) FROM ozon_products)","SELECT (SELECT count(*) FROM wb_orders)+(SELECT count(*) FROM ozon_postings)"))try(var r=s.executeQuery(sql)){assertTrue(r.next());assertEquals(sql.contains("FROM shops")?10:sql.contains("product")?100000:10000,r.getInt(1));}
  }
  var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var client=new OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(90)).build();
  try(var server=new MockWebServer();var reads=new ShopReadCoordinator(3,64,1)){
   server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public MockResponse dispatch(RecordedRequest request)throws InterruptedException{entered.countDown();release.await(90,TimeUnit.SECONDS);return new MockResponse().setBody("{}");}});
   var network=new ArrayList<CompletableFuture<String>>();for(int i=1;i<=2;i++)network.add(reads.submit(new ShopReadCoordinator.Key(i,"slow-fixture-http","read"),false,()->{try(var slot=MarketplaceRequestBudget.acquire();var response=client.newCall(new Request.Builder().url(server.url("/read")).build()).execute()){return response.body().string();}}));
   try{
    assertTrue(entered.await(5,TimeUnit.SECONDS));var elapsed=new ArrayList<Long>();System.setProperty("vncode.runtime.diagnostics","true");
    for(int cycle=0;cycle<20;cycle++)for(int shop=1;shop<=10;shop++){
     int owner=shop;var marketplace=shop>10-ozonShops?Marketplace.OZON:Marketplace.WILDBERRIES;long start=System.nanoTime();
     var page=reads.submitLocal(new ShopReadCoordinator.Key(shop,"fixture-products",cycle+":"+shop),()->new ProductRepository().loadPage(new ProductQuery(owner,marketplace,"",List.of(),null,false,null,100))).get(3,TimeUnit.SECONDS);
     assertEquals(10000,page.total());assertEquals(100,page.rows().size());assertTrue(page.rows().stream().allMatch(row->row.key().shopId()==owner));elapsed.add(System.nanoTime()-start);
     if(marketplace==Marketplace.OZON){var posting=reads.submitLocal(new ShopReadCoordinator.Key(shop,"fixture-fbs",cycle+":"+shop),()->new OzonFbsSnapshotService().load(new OzonFbsQuery(owner,"awaiting_packaging","","",0,100))).get(3,TimeUnit.SECONDS);assertEquals(334,posting.total());assertEquals(100,posting.postings().size());}
     assertEquals(3,reads.workerLimit());assertTrue(reads.pendingCount()<=64);
    }
    assertEquals(2,MarketplaceRequestBudget.activeCount());assertEquals(2,reads.activeCount());assertEquals(0,reads.pendingCount());
    assertEquals(67108864,ThumbnailLoader.memoryBudget()+com.vncode.app.features.fbo.FboProductImageService.memoryBudget());
    RuntimeDiagnostics.record("fixture-read",elapsed.getFirst());assertFalse(RuntimeDiagnostics.snapshot().isEmpty());
    elapsed.sort(Long::compare);System.out.println("SYNTHETIC_LOCAL_READ "+(ozonShops==10?"10ozon":"6wb4ozon")+" samples="+elapsed.size()+" median_ms="+(elapsed.get(100)/1000000.0)+" p95_ms="+(elapsed.get(189)/1000000.0)+" actualWindowsLowEnd=false");
   }finally{release.countDown();for(var future:network)future.get(5,TimeUnit.SECONDS);}
   assertEquals(0,MarketplaceRequestBudget.activeCount());
  }
 }
 @Test void diagnosticsAreOptInAndBounded(){RuntimeDiagnostics.reset();for(int i=0;i<5000;i++)RuntimeDiagnostics.record("test",1);assertTrue(RuntimeDiagnostics.snapshot().isEmpty());System.setProperty("vncode.runtime.diagnostics","true");for(int i=0;i<5000;i++)RuntimeDiagnostics.record("test",i);var report=RuntimeDiagnostics.snapshot();assertEquals(2048,((Number)((Map<?,?>)report.get("test")).get("samples")).intValue());assertFalse(report.toString().contains("api_key"));}
}
