package com.vncode.app.integration.wb;
import com.vncode.app.config.Database;
import com.vncode.app.models.Order;
import com.vncode.app.shared.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class WbSupplyImagesTest {
 @TempDir Path root;
 @AfterEach void clear(){WbSupplyWorkflow.clearImageCache();System.clearProperty("vncode.appdata.dir");}
 @Test void printingImageCacheSharesTheEncodedBudgetAcrossShops(){
  var cache=EncodedImageCache.shared();cache.clear();byte[] bytes=new byte[8*1024*1024];for(int shop=1;shop<=10;shop++)cache.put("synthetic-"+shop,bytes);
  assertEquals(32L*1024*1024,cache.retainedBytes());assertEquals(cache.retainedBytes(),com.vncode.app.features.fbo.FboProductImageService.retainedBytes());assertNull(cache.get("synthetic-1"));assertNotNull(cache.get("synthetic-10"));assertEquals(67108864,cache.budgetBytes()+ThumbnailLoader.memoryBudget());
 }
 @Test void supplyPrintingNeverFetchesUnapprovedImageHost()throws Exception {
  System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var server=new MockWebServer()){
   server.enqueue(new MockResponse().setResponseCode(404));var order=new Order();order.setImageUrl(server.url("/private-image").toString());new WbSupplyWorkflow().ensureOrderImages(List.of(order));assertEquals(0,server.getRequestCount());assertNull(order.getImage());
  }
 }
}
