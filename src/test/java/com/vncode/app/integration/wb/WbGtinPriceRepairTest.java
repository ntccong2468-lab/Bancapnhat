package com.vncode.app.integration.wb;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Headers;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WbGtinPriceRepairTest {
    @TempDir Path dir; Shop shop; Fixture fixture; WbGtinPriceRepair service;
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",dir.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'test','WILDBERRIES','token')");}
        shop=new Shop(1,"test",Marketplace.WILDBERRIES,null,"token");fixture=new Fixture();service=new WbGtinPriceRepair(shop,fixture);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void batchPrefetchRejectsMissingDuplicateAndForeignProducts()throws Exception {
        for(String fault:List.of("missing","duplicate","foreign")){
            ListingTransport bad=(path,body,mutation)->{var items=new JsonArray();if(!fault.equals("missing"))items.add(priceRow(fault.equals("foreign")?"999":"101",100));if(fault.equals("duplicate"))items.add(priceRow("101",100));return priceResponse(items);};
            assertThrows(IOException.class,()->new WbGtinPriceRepair(shop,bad).prefetch(List.of("101"),()->{}),fault);
        }
    }
    @Test void batchRateLimitAndCancellationStopBeforeAnyMutation()throws Exception {
        var calls=new AtomicInteger();ListingTransport api=(path,body,mutation)->{assertFalse(mutation);calls.incrementAndGet();throw new ListingRateLimitException(new Headers.Builder().add("Retry-After","10").build());};
        var batch=new WbGtinPriceRepair(shop,api);assertEquals("WB_PRICE_READBACK_PENDING",assertThrows(IOException.class,()->batch.verifyReady(List.of("101"),()->{})).getMessage());assertThrows(ListingRateLimitException.class,()->batch.prefetch(List.of("101"),()->{}));assertEquals(1,calls.get());
        assertThrows(IllegalStateException.class,()->batch.prefetch(List.of("101"),()->{throw new IllegalStateException("cancelled");}));assertEquals(1,calls.get());
    }
    @Test void batchesAtMostThousandAndChecksFreshPriceBeforeDispatch()throws Exception {
        var calls=new AtomicInteger();var amount=new AtomicInteger(100);ListingTransport api=(path,body,mutation)->{
            assertFalse(mutation);assertEquals("api/v2/list/goods/filter",path);var ids=body.getAsJsonObject().getAsJsonArray("nmList");assertTrue(ids.size()<=1000);calls.incrementAndGet();var list=new JsonArray();for(var id:ids)list.add(priceRow(id.getAsString(),amount.get()));return priceResponse(list);
        };
        var batch=new WbGtinPriceRepair(shop,api);var ids=new ArrayList<String>();for(int i=1;i<=1001;i++)ids.add(Integer.toString(i));batch.prefetch(ids,()->{});assertEquals(2,calls.get());
        assertEquals(WbGtinPriceRepair.State.READY,batch.repair("101","batch",Set.of(11L),()->{},()->{}).state());batch.verifyReady(List.of("101"),()->{});
        amount.set(200);assertEquals("WB_PRICE_CHANGED",assertThrows(IOException.class,()->batch.verifyReady(List.of("101"),()->{})).getMessage());
    }
    private static JsonObject priceRow(String id,int price){var size=new JsonObject();size.addProperty("sizeID",11);size.addProperty("price",price);var sizes=new JsonArray();sizes.add(size);var row=new JsonObject();row.addProperty("nmID",Long.parseLong(id));row.addProperty("currencyIsoCode4217","RUB");row.addProperty("editableSizePrice",false);row.add("sizes",sizes);return row;}
    private static JsonObject priceResponse(JsonArray rows){var data=new JsonObject();data.add("listGoods",rows);var result=new JsonObject();result.add("data",data);result.addProperty("error",false);return result;}
    @Test void averagesAllSizesAndDoesNotPublishBeforeActualReadback()throws Exception {
        fixture.prices.put(11L,100);fixture.prices.put(12L,201);
        assertEquals(WbGtinPriceRepair.State.PENDING,service.repair("101","job-1",Set.of(11L,12L),()->{},()->{}).state());
        assertEquals(1,fixture.writes.get());assertEquals("api/v2/upload/task/size",fixture.mutationPath);var entries=fixture.sent.getAsJsonObject().getAsJsonArray("data");assertEquals(2,entries.size());
        assertEquals(Set.of(11L,12L),entries.asList().stream().map(e->e.getAsJsonObject().get("sizeID").getAsLong()).collect(java.util.stream.Collectors.toSet()));
        for(var entry:entries){var item=entry.getAsJsonObject();assertEquals(151,item.get("price").getAsInt());assertFalse(item.has("discount"));}
        assertEquals(WbGtinPriceRepair.State.PENDING,service.repair("101","job-1",Set.of(11L,12L),()->{},()->{}).state());assertEquals(1,fixture.writes.get());
        fixture.prices.replaceAll((k,v)->151);
        assertEquals(WbGtinPriceRepair.State.READY,service.repair("101","job-1",Set.of(11L,12L),()->{},()->{}).state());assertEquals(1,fixture.writes.get());
    }
    @Test void uncertainSendIsNeverAutomaticallyRepeatedAndReadbackMayResolveIt()throws Exception {
        fixture.prices.put(11L,100);fixture.prices.put(12L,200);fixture.timeout=true;
        assertEquals(WbGtinPriceRepair.State.PENDING,service.repair("101","job-2",Set.of(11L,12L),()->{},()->{}).state());
        assertEquals(WbGtinPriceRepair.State.PENDING,new WbGtinPriceRepair(shop,fixture).repair("101","job-2",Set.of(11L,12L),()->{},()->{}).state());assertEquals(1,fixture.writes.get());
        fixture.prices.replaceAll((k,v)->150);assertEquals(WbGtinPriceRepair.State.READY,service.repair("101","job-2",Set.of(11L,12L),()->{},()->{}).state());assertEquals(1,fixture.writes.get());
    }
    @Test void unchangedPricesSkipWriteAndDistinctSizeIdsAreRequired()throws Exception {
        fixture.prices.put(11L,100);fixture.prices.put(12L,100);
        assertEquals(WbGtinPriceRepair.State.READY,service.repair("101","job-3",Set.of(11L,12L),()->{},()->{}).state());assertEquals(0,fixture.writes.get());
        assertEquals("WB_PRICE_SIZE_INCOMPLETE",service.repair("101","missing",Set.of(11L,12L,13L),()->{},()->{}).code());assertEquals(0,fixture.writes.get());
        fixture.foreign=true;assertThrows(IOException.class,()->service.repair("101","job-4",Set.of(11L,12L),()->{},()->{}));assertEquals(0,fixture.writes.get());
    }
    @Test void baseOrUnknownTokenStopsBeforePriceReadsAndWrites()throws Exception {
        fixture.prices.put(11L,100);fixture.prices.put(12L,200);
        var paced=new WbGtinPriceRepair(shop,fixture,true);paced.prefetch(List.of("101"),()->{});
        var result=paced.repair("101","slow-token",Set.of(11L,12L),()->{},()->{});
        assertEquals(WbGtinPriceRepair.State.FAILED,result.state());
        assertEquals("WB_PRICE_TOKEN_LIMIT",result.code());assertEquals(0,fixture.reads.get());assertEquals(0,fixture.writes.get());
        assertEquals("WB_PRICE_TOKEN_LIMIT",assertThrows(IOException.class,()->paced.verifyReady(List.of("101"),()->{})).getMessage());assertEquals(0,fixture.reads.get());
    }
    @Test void rejectedRateLimitCanRetryButChangedSellerPriceBlocksOverwrite()throws Exception {
        fixture.prices.put(11L,100);fixture.prices.put(12L,200);fixture.rate=true;
        assertEquals(WbGtinPriceRepair.State.RATE_LIMIT,service.repair("101","job-5",Set.of(11L,12L),()->{},()->{}).state());assertEquals(1,fixture.writes.get());
        Thread.sleep(20);fixture.prices.put(12L,300);
        assertEquals(WbGtinPriceRepair.State.FAILED,service.repair("101","job-5",Set.of(11L,12L),()->{},()->{}).state());assertEquals(1,fixture.writes.get());
    }
    @Test void rateLimitIsKnownUnsentAndCanRetryTheSameUnchangedPlan()throws Exception {
        fixture.prices.put(11L,100);fixture.prices.put(12L,200);fixture.rate=true;
        assertEquals(WbGtinPriceRepair.State.RATE_LIMIT,service.repair("101","retry",Set.of(11L,12L),()->{},()->{}).state());Thread.sleep(20);
        assertEquals(WbGtinPriceRepair.State.PENDING,service.repair("101","retry",Set.of(11L,12L),()->{},()->{}).state());assertEquals(2,fixture.writes.get());
    }
    @Test void differentJobDoesNotResubmitWhileEarlierPriceTaskIsUnresolved()throws Exception {
        fixture.prices.put(11L,100);fixture.prices.put(12L,200);
        assertEquals(WbGtinPriceRepair.State.PENDING,service.repair("101","old",Set.of(11L,12L),()->{},()->{}).state());
        assertEquals("WB_PRICE_OTHER_REQUEST_PENDING",service.repair("101","new",Set.of(11L,12L),()->{},()->{}).code());assertEquals(1,fixture.writes.get());
    }
    @Test void partialPriceFailureDoesNotPermitGtinWrite()throws Exception {
        fixture.prices.put(11L,100);fixture.prices.put(12L,200);
        assertEquals(WbGtinPriceRepair.State.PENDING,service.repair("101","partial",Set.of(11L,12L),()->{},()->{}).state());
        fixture.taskStatus=5;fixture.prices.put(11L,150);
        assertEquals(WbGtinPriceRepair.State.FAILED,service.repair("101","partial",Set.of(11L,12L),()->{},()->{}).state());assertEquals(1,fixture.writes.get());
    }
    @Test void explicitRejectionEndsTheJobWithoutRepeatingTheMutation()throws Exception {
        fixture.prices.put(11L,100);fixture.prices.put(12L,200);fixture.reject=true;
        assertEquals(WbGtinPriceRepair.State.FAILED,service.repair("101","job-6",Set.of(11L,12L),()->{},()->{}).state());
        assertEquals(WbGtinPriceRepair.State.FAILED,service.repair("101","job-6",Set.of(11L,12L),()->{},()->{}).state());assertEquals(1,fixture.writes.get());
    }
    @Test void concurrentSameJobCreatesOnlyOnePriceRequest()throws Exception {
        fixture.prices.put(11L,100);fixture.prices.put(12L,200);
        var workers=Executors.newFixedThreadPool(2);
        try {var a=workers.submit(()->service.repair("101","same",Set.of(11L,12L),()->{},()->{}));var b=workers.submit(()->new WbGtinPriceRepair(shop,fixture).repair("101","same",Set.of(11L,12L),()->{},()->{}));
            assertEquals(WbGtinPriceRepair.State.PENDING,a.get().state());assertEquals(WbGtinPriceRepair.State.PENDING,b.get().state());assertEquals(1,fixture.writes.get());
        } finally {workers.shutdownNow();}
    }
    @Test void atomicDispatchClaimAcrossIndependentJvmsNeverSubmitsTwoJobs()throws Exception {
        service.init();assertTrue(service.preparePlan("101","a",150,"fixture"));
        assertTrue(new WbGtinPriceRepair(shop,fixture).preparePlan("101","b",150,"fixture"));
        Path readyA=dir.resolve("ready-a"),readyB=dir.resolve("ready-b"),go=dir.resolve("go");
        Process a=child("a",readyA,go),b=child("b",readyB,go);
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while((!java.nio.file.Files.exists(readyA)||!java.nio.file.Files.exists(readyB))&&System.nanoTime()<until)Thread.sleep(20);
            assertTrue(java.nio.file.Files.exists(readyA)&&java.nio.file.Files.exists(readyB),"Both independent processes must be ready");
            java.nio.file.Files.writeString(go,"go");assertTrue(a.waitFor(20,TimeUnit.SECONDS));assertTrue(b.waitFor(20,TimeUnit.SECONDS));
            String outA=new String(a.getInputStream().readAllBytes()),outB=new String(b.getInputStream().readAllBytes());
            assertEquals(0,a.exitValue(),outA);assertEquals(0,b.exitValue(),outB);
            assertTrue(outA.contains("CLAIMED=true")^outB.contains("CLAIMED=true"),outA+" | "+outB);
        } finally {a.destroyForcibly();b.destroyForcibly();}
    }
    @Test void sameAnchorCannotBeReplannedOrClaimedTwiceAcrossIndependentJvms()throws Exception {
        service.init();assertTrue(service.preparePlan("101","same",150,"fixture"));
        Path readyA=dir.resolve("same-ready-a"),readyB=dir.resolve("same-ready-b"),go=dir.resolve("same-go");
        Process a=child("same",readyA,go),b=child("same",readyB,go);
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while((!java.nio.file.Files.exists(readyA)||!java.nio.file.Files.exists(readyB))&&System.nanoTime()<until)Thread.sleep(20);
            assertTrue(java.nio.file.Files.exists(readyA)&&java.nio.file.Files.exists(readyB));java.nio.file.Files.writeString(go,"go");
            assertTrue(a.waitFor(20,TimeUnit.SECONDS));assertTrue(b.waitFor(20,TimeUnit.SECONDS));
            String outA=new String(a.getInputStream().readAllBytes()),outB=new String(b.getInputStream().readAllBytes());
            assertEquals(0,a.exitValue(),outA);assertEquals(0,b.exitValue(),outB);
            assertTrue(outA.contains("CLAIMED=true")^outB.contains("CLAIMED=true"),outA+" | "+outB);
            assertFalse(service.preparePlan("101","same",150,"fixture"),"A stale caller must not reset the dispatched checkpoint");
        } finally {a.destroyForcibly();b.destroyForcibly();}
    }
    @Test void expiredKnownUnsentRateLimitCanBeSupersededWithoutDeletingReceipt()throws Exception {
        service.init();String owner=ListingIdentity.fingerprint(shop);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_gtin_price_repairs(shop_id,nm_id,anchor,owner,state,target,before_json,task_id,not_before) VALUES(1,'101','old',?,'RATE_LIMIT',150,'old-snapshot','',?)")){
            p.setString(1,owner);p.setString(2,java.time.Instant.now().minusSeconds(2).toString());p.executeUpdate();
        }
        assertTrue(service.preparePlan("101","new",150,"new-snapshot"));assertTrue(service.claimProductDispatch("101","new",150,"new-snapshot"));
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT state,before_json FROM wb_gtin_price_repairs WHERE shop_id=1 AND nm_id='101' AND anchor='old'");var r=p.executeQuery()){
            assertTrue(r.next());assertEquals("SUPERSEDED",r.getString(1));assertEquals("old-snapshot",r.getString(2));
        }
    }
    private Process child(String anchor,Path ready,Path go)throws IOException {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        return new ProcessBuilder(java,"-cp",cp,RaceChild.class.getName(),dir.toString(),anchor,ready.toString(),go.toString()).redirectErrorStream(true).start();
    }
    public static class RaceChild {
        public static void main(String[] args)throws Exception {
            System.setProperty("vncode.appdata.dir",args[0]);Shop owner=new Shop(1,"test",Marketplace.WILDBERRIES,null,"token");
            var instance=new WbGtinPriceRepair(owner,(path,body,mutation)->{throw new IOException("no remote calls");});
            java.nio.file.Files.writeString(Path.of(args[2]),"ready");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!java.nio.file.Files.exists(Path.of(args[3]))&&System.nanoTime()<until)Thread.sleep(10);
            System.out.println("CLAIMED="+instance.claimProductDispatch("101",args[1],150,"fixture"));
        }
    }
    private static class Fixture implements ListingTransport {
        final Map<Long,Integer> prices=new TreeMap<>();final AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger();JsonElement sent;String mutationPath;boolean timeout,rate,reject,foreign;int taskStatus=3;
        @Override public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException {
            if(mutation){writes.incrementAndGet();mutationPath=path;sent=body.deepCopy();if(timeout)throw new IOException("timeout");if(rate){rate=false;throw new ListingRateLimitException(new Headers.Builder().add("Retry-After","0").build());}if(reject)throw new ListingRejectedException("rejected");return JsonParser.parseString("{\"error\":false,\"data\":{\"id\":55}}");}
            reads.incrementAndGet();
            if(path.startsWith("api/v2/list/goods/filter"))return JsonParser.parseString("{\"error\":false,\"data\":{\"listGoods\":[{\"nmID\":101,\"currencyIsoCode4217\":\"RUB\",\"editableSizePrice\":true}]}}");
            if(path.startsWith("api/v2/list/goods/size/nm")){var result=new JsonObject();var data=new JsonObject();var rows=new JsonArray();prices.forEach((id,price)->{var p=new JsonObject();p.addProperty("nmID",foreign?102:101);p.addProperty("sizeID",id);p.addProperty("price",price);p.addProperty("currencyIsoCode4217","RUB");rows.add(p);});data.add("listGoods",rows);result.addProperty("error",false);result.add("data",data);return result;}
            if(path.startsWith("api/v2/history/tasks"))return JsonParser.parseString("{\"error\":false,\"data\":{\"status\":"+taskStatus+"}}");
            throw new IOException("unexpected endpoint");
        }
    }
}
