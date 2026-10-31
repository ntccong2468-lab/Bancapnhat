package com.vncode.app.integration.ozon;
import com.vncode.app.config.Database;
import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OzonFbsPrintReportTest {
 @TempDir Path dir;
 @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
 @Test void reportKeepsSeparateLocalPrintAndRemoteKizOutcomes()throws Exception{
  System.setProperty("vncode.appdata.dir",dir.toString());Database.initDatabase();
  try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','11','secret-fixture')");}
  var shop=new Shop(1,"Fixture",Marketplace.OZON,"11","secret-fixture");var reports=new SyncRunRepository();
  new OzonPostingRepository().upsertDetail(1,new OzonPostingDto("P-1","","","awaiting_packaging","","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of()));
  new OzonExemplarJobRepository().findOrCreate(1,"P-1");
  var failed=new OzonFbsPrintReport(shop,List.of("P-1","P-2"));failed.finish(shop,false);
  assertEquals("NEEDS_ACTION",reports.find(failed.id()).orElseThrow().state());assertEquals(2,reports.items(failed.id(),0,100).size());assertTrue(reports.items(failed.id(),0,100).stream().allMatch(i->i.error().equals("FBS_PRINT_NOT_COMPLETED")));
  var waiting=new OzonFbsPrintReport(shop,List.of("P-4","P-5"));waiting.waitingForStock(shop);
  assertEquals("WAITING_REMOTE",reports.find(waiting.id()).orElseThrow().state());
  assertTrue(reports.items(waiting.id(),0,100).stream().allMatch(i->i.state().equals("WAITING_REMOTE")&&i.error().equals("KIZ_PURCHASE_PENDING")));
  var done=new OzonFbsPrintReport(shop,List.of("P-3"));done.finish(shop,true);
  var item=reports.items(done.id(),0,100).getFirst();assertEquals("SUCCEEDED",item.state());assertEquals("FBS_PDF_CREATED",item.step());assertTrue(item.evidence().contains("\"remoteKizConfirmedByThisTask\":false"));assertFalse(item.evidence().contains("secret-fixture"));
  var partial=new OzonFbsPrintReport(shop,List.of("P-6","P-7"));partial.finishAvailable(shop,Set.of("P-6"),Map.of("P-7","Nhãn chưa sẵn sàng"));
  assertEquals("PARTIAL",reports.find(partial.id()).orElseThrow().state());
  var rows=reports.items(partial.id(),0,100);assertEquals("SUCCEEDED",rows.stream().filter(r->r.productId().equals("P-6")).findFirst().orElseThrow().state());
  var bad=rows.stream().filter(r->r.productId().equals("P-7")).findFirst().orElseThrow();assertEquals("NEEDS_ACTION",bad.state());assertTrue(bad.evidence().contains("Nhãn chưa sẵn sàng"));
  var limited=new OzonFbsPrintReport(shop,List.of("P-8"));limited.finishAvailable(shop,Set.of(),Map.of("P-8","Ozon 429"),Set.of("P-8"));
  assertEquals("WAITING_REMOTE",reports.find(limited.id()).orElseThrow().state());assertEquals("WAITING_REMOTE",reports.items(limited.id(),0,100).getFirst().state());
 }
}
