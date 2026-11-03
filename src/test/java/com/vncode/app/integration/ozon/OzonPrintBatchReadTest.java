package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production preparation/SQLite and local HTTP: no remote seller writes. */
class OzonPrintBatchReadTest {
 @TempDir Path root;
 MockWebServer server;Shop shop;
 @BeforeEach void setup()throws Exception{
  System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
  try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Ozon','OZON','fixture','fixture')");}
  server=new MockWebServer();server.start();shop=new Shop(1,"Ozon",Marketplace.OZON,"fixture","fixture");
  new OzonProductKizPolicyRepository().setRequired(1,"101",false);
 }
 @AfterEach void cleanup()throws Exception{server.shutdown();System.clearProperty("vncode.appdata.dir");}
 OzonExemplarService service(){return new OzonExemplarService(new OzonPostingRepository(),new OzonProductGtinMappingRepository(),new OzonExemplarJobRepository(),
   (id,credentials)->new OzonApiClient(id,credentials,server.url("/"),new OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(5)).build(),new OzonApiRateLimiter(java.time.Duration.ZERO),new OzonRetryPolicy(1,java.time.Duration.ofMillis(1),java.time.Duration.ofMillis(1))));}
 JsonObject row(int n){
  var j=JsonParser.parseString("{\"posting_number\":\"POST-"+n+"\",\"order_id\":\""+(10000+n)+"\",\"order_number\":\"ORDER-"+n+"\",\"status\":\"awaiting_deliver\",\"in_process_at\":\""+Instant.now().minusSeconds(86400)+"\",\"requirements\":{},\"optional\":{},\"products\":[{\"sku\":101,\"offer_id\":\"ARTICLE\",\"name\":\"Synthetic\",\"quantity\":1}]}").getAsJsonObject();return j;
 }
 List<String> seed(int count)throws Exception{
  var selected=new ArrayList<OzonPostingDto>();for(int i=0;i<count;i++){var r=row(i);var wrapper=new JsonObject();wrapper.add("result",r);selected.add(OzonJson.requirePostingDetail(wrapper,"POST-"+i));}
  new OzonPostingRepository().upsertPage(1,selected);return selected.stream().map(OzonPostingDto::postingNumber).toList();
 }
 MockResponse response(JsonArray rows,boolean next,String cursor){var j=new JsonObject();j.add("postings",rows);j.addProperty("has_next",next);j.addProperty("cursor",cursor);return new MockResponse().setHeader("Content-Type","application/json").setBody(j.toString());}
 @Test void thousandFreshPrintRequirementsUseTenListRequestsInsteadOfOneThousandDetails()throws Exception{
  var numbers=seed(1000);var calls=new AtomicInteger();
  server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest r){
   assertEquals("/v4/posting/fbs/list",r.getPath());var body=JsonParser.parseString(r.getBody().readUtf8()).getAsJsonObject();
   assertEquals(100,body.get("limit").getAsInt());var orders=body.getAsJsonObject("filter").getAsJsonArray("order_numbers");assertEquals(100,orders.size());
   var rows=new JsonArray();for(var order:orders)rows.add(row(Integer.parseInt(order.getAsString().substring(6))));calls.incrementAndGet();return response(rows,false,"");
  }});
  var result=service().stageForPrintBatch(shop,numbers,null);
  assertEquals(1000,result.results().size());assertTrue(result.failed().isEmpty());assertEquals(10,calls.get());assertEquals(10,server.getRequestCount());
  assertTrue(result.results().stream().allMatch(r->r.stage().equals("NOT_REQUIRED")));
 }
 @Test void incompleteListRequirementsUseExactGetForOnlyTheUnverifiedPosting()throws Exception{
  var numbers=seed(2);var rows=new JsonArray();rows.add(row(0));var incomplete=row(1);incomplete.remove("optional");rows.add(incomplete);
  server.enqueue(response(rows,false,""));server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"result\":"+row(1)+"}"));
  var result=service().stageForPrintBatch(shop,numbers,null);assertEquals(2,result.results().size());assertEquals(2,server.getRequestCount());
  assertEquals("/v4/posting/fbs/list",server.takeRequest().getPath());var detail=server.takeRequest();assertEquals("/v3/posting/fbs/get",detail.getPath());assertEquals("POST-1",JsonParser.parseString(detail.getBody().readUtf8()).getAsJsonObject().get("posting_number").getAsString());
 }
 @Test void nativeOrderSelectionPaginatesBeforeReservingAnyMarks()throws Exception{
  var numbers=seed(2);var first=new JsonArray();first.add(row(1));var second=new JsonArray();second.add(row(0));
  server.enqueue(response(first,true,"next-page"));server.enqueue(response(second,false,""));
  var result=service().stageForPrintBatch(shop,numbers,null);assertEquals(numbers,result.results().stream().map(OzonPreparationResult::postingNumber).toList());
  server.takeRequest();var page=JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();assertEquals("next-page",page.get("cursor").getAsString());assertEquals(2,server.getRequestCount());
 }
 @Test void foreignOrderResponseIsRejectedBeforeLocalPreparation()throws Exception{
  var numbers=seed(1);var rows=new JsonArray();var foreign=row(0);foreign.addProperty("order_number","OTHER");rows.add(foreign);server.enqueue(response(rows,false,""));
  var failure=assertThrows(java.io.IOException.class,()->service().stageForPrintBatch(shop,numbers,null));assertEquals("OZON_PRINT_FOREIGN_ORDER",failure.getMessage());
  assertNull(new OzonExemplarJobRepository().find(1,"POST-0"));assertEquals(1,server.getRequestCount());
 }
 @Test void rateLimitedListRemainsPendingWithoutFallingBackToOneRequestPerPosting()throws Exception{
  var numbers=seed(10);server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","60").setBody("{}"));
  var failure=assertThrows(OzonApiException.class,()->service().stageForPrintBatch(shop,numbers,null));assertEquals(429,failure.statusCode());assertEquals(1,server.getRequestCount());
 }
}
