package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OzonAcceptanceKizTest {
    @TempDir Path directory;
    MockWebServer server;Shop shop;boolean multi,pending,rejected,foreign,optional;
    final Map<String,JsonObject> sent=new ConcurrentHashMap<>();
    final Set<String> shipped=ConcurrentHashMap.newKeySet(),accepted=ConcurrentHashMap.newKeySet();
    final List<String> trace=new CopyOnWriteArrayList<>();
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Ozon','OZON','fixture','fixture')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'04600000000001','Fixture','2026-08-18T00:00:00Z')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'04600000000001',2,'COMPLETED','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
            for(int i=1;i<=2;i++)s.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES("+i+",1,1,'010460000000000121FIXTURE"+i+"','fixture','04600000000001','AVAILABLE','IN_CIRCULATION','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
            s.execute("INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at) VALUES(1,'101','04600000000001','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
        }
        shop=new Shop(1,"Ozon",Marketplace.OZON,"fixture","fixture");server=new MockWebServer();
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
            try{return respond(request);}catch(Throwable failed){return new MockResponse().setResponseCode(500);}
        }});server.start();
    }
    @AfterEach void close()throws Exception{server.shutdown();System.clearProperty("vncode.appdata.dir");}
    @Test void exactPassedKizIsSubmittedBeforeOzonAssembly()throws Exception {
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertTrue(trace.indexOf("/v6/fbs/posting/product/exemplar/set")<trace.indexOf("/v4/posting/fbs/ship"));
        assertTrue(trace.indexOf("/v5/fbs/posting/product/exemplar/status")<trace.indexOf("/v4/posting/fbs/ship"));
        assertEquals(1,sent.size());assertEquals(1,shipped.size());
        assertEquals("ACCEPTED",new OzonExemplarJobRepository().find(1,"PARENT").stage().name());
    }
    @Test void optionalMarksAreAlsoAssignedBeforeAcceptanceLikeTheRealOzonResponse()throws Exception {
        optional=true;assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertEquals(1,sent.size());assertTrue(trace.indexOf("/v6/fbs/posting/product/exemplar/set")<trace.indexOf("/v4/posting/fbs/ship"));
    }
    @Test void sellerExemptionSkipsOptionalMarksButNeverMandatoryMarks()throws Exception {
        var mandatory=OzonJson.requirePostingDetail(JsonParser.parseString(posting("PARENT")).getAsJsonObject(),"PARENT");
        new OzonProductKizPolicyRepository().setRequired(1,"101",false);
        assertTrue(OzonAcceptanceKizService.requiredBeforeAccept(shop,mandatory));
        optional=true;var optionalPosting=OzonJson.requirePostingDetail(JsonParser.parseString(posting("PARENT")).getAsJsonObject(),"PARENT");
        assertFalse(OzonAcceptanceKizService.requiredBeforeAccept(shop,optionalPosting));
    }
    @Test void processingKizNeverShipsOrResubmitsTheSameSet()throws Exception {
        pending=true;
        assertEquals("OZON_ACCEPT_KIZ_PENDING",assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true)).getMessage());
        assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true));
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());assertTrue(shipped.isEmpty());
    }
    @Test void rejectedKizCannotBeReportedAsAnAcceptedOrder()throws Exception {
        rejected=true;
        assertEquals("OZON_ACCEPT_KIZ_REJECTED",assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true)).getMessage());
        assertTrue(shipped.isEmpty());
    }
    @Test void acceptanceResumesWhenPendingMarksPassWithoutSendingThemAgain()throws Exception {
        pending=true;
        assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true));
        pending=false;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertEquals(1,trace.stream().filter(p->p.equals("/v4/posting/fbs/ship")).count());
        assertEquals(1,shipped.size());
    }
    @Test void mandatoryMultiUnitOrderSplitsBeforeAssigningKizToEachNativeChild()throws Exception {
        multi=true;var result=service().shipPerUnit(shop,"PARENT",true);
        assertEquals(List.of("CHILD-A","CHILD-B"),result.resultingPostings());assertEquals(Set.of("CHILD-A","CHILD-B"),sent.keySet());
        assertTrue(trace.indexOf("/v1/posting/fbs/split")<trace.indexOf("/v6/fbs/posting/product/exemplar/set"));
        assertEquals(2,shipped.size());assertEquals("COMPLETE",new OzonUnitShipRepository().find(shop,"PARENT").state());
        service().shipPerUnit(shop,"PARENT",true);
        assertEquals(1,trace.stream().filter(p->p.equals("/v1/posting/fbs/split")).count());
        assertEquals(2,trace.stream().filter(p->p.equals("/v4/posting/fbs/ship")).count());
    }
    @Test void foreignSplitChildStopsBeforeAnyKizOrShipMutation()throws Exception {
        multi=true;foreign=true;assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true));assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
    }
    private OzonShipService service(){return new OzonShipService(new OzonPostingRepository(),new OzonProductGtinMappingRepository(),new OzonExemplarJobRepository(),(id,credentials)->new OzonApiClient(id,credentials,server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));}
    private MockResponse respond(RecordedRequest request) {
        String path=request.getPath();trace.add(path);var body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();String number=OzonJson.text(body,"posting_number");
        if(path.equals("/v3/posting/fbs/get"))return json(posting(number));
        if(path.equals("/v1/posting/fbs/split"))return json("{\"parent_posting\":{\"posting_number\":\"PARENT\",\"products\":[]},\"postings\":[{\"posting_number\":\"CHILD-A\"},{\"posting_number\":\"CHILD-B\"}]}");
        if(path.equals("/v6/fbs/posting/product/exemplar/create-or-get"))return json("{\"result\":{\"products\":[{\"product_id\":101,\"exemplars\":[{\"exemplar_id\":7001}]}]}}");
        if(path.equals("/v5/fbs/posting/product/exemplar/validate"))return json("{\"products\":[{\"exemplars\":[{\"valid\":true}]}]}");
        if(path.equals("/v6/fbs/posting/product/exemplar/set")){sent.put(number,body);return json("{}");}
        if(path.equals("/v5/fbs/posting/product/exemplar/status")) {
            var raw=sent.get(number).deepCopy();raw.addProperty("status",pending?"validation_in_process":"ship_available");
            for(var p:raw.getAsJsonArray("products"))for(var e:p.getAsJsonObject().getAsJsonArray("exemplars"))for(var m:e.getAsJsonObject().getAsJsonArray("marks")){
                var mark=m.getAsJsonObject();mark.addProperty("check_status",pending?"processing":rejected?"failed":"passed");var errors=new JsonArray();if(rejected)errors.add("NOT_IN_CIRCULATION");mark.add("error_codes",errors);
            }
            if(!pending&&!rejected)accepted.add(number);return json(raw.toString());
        }
        if(path.equals("/v4/posting/fbs/ship")) {assertTrue(accepted.contains(number));shipped.add(number);return json("{\"result\":[\""+number+"\"]}");}
        return new MockResponse().setResponseCode(404);
    }
    private String posting(String number) {
        String status=shipped.contains(number)?"awaiting_deliver":"awaiting_packaging";
        return "{\"result\":{\"posting_number\":\""+number+"\",\"order_id\":\""+(foreign&&number.equals("CHILD-B")?"FOREIGN":"ORDER")+"\",\"delivery_method\":{\"warehouse_id\":\"WH\"},\"status\":\""+status+"\",\"products\":[{\"sku\":101,\"offer_id\":\"fixture\",\"name\":\"Fixture\",\"quantity\":"+(multi&&number.equals("PARENT")?2:1)+"}],"+(optional?"\"requirements\":{},\"optional\":{\"products_with_possible_mandatory_mark\":[101]},":"\"requirements\":{\"products_requiring_mandatory_mark\":[\"101\"]},")+"\"available_actions\":"+(accepted.contains(number)?"[\"ship\"]":"[]")+",\"ship_available\":"+accepted.contains(number)+"}}";
    }
    private static MockResponse json(String value){return new MockResponse().setHeader("Content-Type","application/json").setBody(value);}
}
