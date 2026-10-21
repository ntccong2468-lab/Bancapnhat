package com.vncode.app.features.sync;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogSyncModels.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.*;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic seller HTTP responses, real planner/adapters/durable queue/readback and SQLite. */
class GtinBulkPipelineTest {
    @TempDir Path directory;String previous;
    @BeforeEach void init()throws Exception{previous=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();}
    @AfterEach void restore(){if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);}
    @Test void wbTenThousandGtinUseFourWholeCardWritesAndReadBackEveryMember()throws Exception{large(Marketplace.WILDBERRIES);}
    @Test void ozonTenThousandGtinUseOneHundredDedicatedBarcodeWrites()throws Exception{large(Marketplace.OZON);}
    @Test void theSecondOzonPartitionReselectsRetainedTargetsWithoutLosingItsApprovedScope()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);var api=new Api(Marketplace.OZON,105);var selected=seed(shop,api);
        try(var session=new GtinBulkSession(shop,api,ignored->{},GtinBulkPipelineTest::freshSyntheticSource)){
            session.prepare(selected.stream().map(Selection::key).toList());var plan=session.catalog.preview(shop,selected,new Options(Set.of(Field.GTIN),Map.of()));assertEquals(105,plan.readyCount());
            var done=session.catalog.run(shop,plan.id(),true);assertEquals(105,done.rows().stream().filter(row->row.state()==State.SUCCEEDED).count(),()->done.rows().stream().filter(row->row.state()!=State.SUCCEEDED).limit(3).toList().toString());assertEquals(2,api.writes);assertEquals(105,api.applied.size());
            for(int i=0;i<105;i++)assertTrue(api.info.get(Integer.toString(100000+i)).getAsJsonArray("barcodes").asList().contains(new JsonPrimitive("legacy-"+i)));
            assertTrue(api.reads<30,"Reselecting the next retained partition must not trigger per-product HTTP reads");
        }
    }
    private void large(Marketplace marketplace)throws Exception {
        var shop=GtinWriteTestFixture.shop(marketplace);var api=new Api(marketplace,10_000);var selections=seed(shop,api);var progress=new ArrayList<JobItem>();long started=System.nanoTime();
        try(var session=new GtinBulkSession(shop,api,id->{},GtinBulkPipelineTest::freshSyntheticSource)){
            session.prepare(selections.stream().map(Selection::key).toList());
            var plan=session.catalog.preview(shop,selections,new Options(Set.of(Field.GTIN),Map.of()));assertEquals(10_000,plan.readyCount());assertEquals(0,api.writes);
            var result=session.catalog.run(shop,plan.id(),true,progress::add);
            assertTrue(result.rows().stream().allMatch(r->r.state()==State.SUCCEEDED),()->"Unfinished: "+result.rows().stream().filter(r->r.state()!=State.SUCCEEDED).limit(3).toList());
            assertEquals(marketplace==Marketplace.WILDBERRIES?4:100,api.writes);assertEquals(10_000,api.applied.size());assertTrue(api.maximumBodyBytes<=10_000_000);assertTrue(api.maximumItems<=(marketplace==Marketplace.WILDBERRIES?3000:100));
            assertTrue(api.reads<=(marketplace==Marketplace.WILDBERRIES?240:300),()->"Read requests must scale with pages/batches, not GTIN²: "+api.reads);
            assertTrue(progress.stream().anyMatch(i->i.status()==Status.AWAITING_VERIFICATION));assertEquals(10_000,progress.stream().filter(i->i.status()==Status.SUCCEEDED).count());
            int before=api.writes;session.catalog.run(shop,plan.id(),false);assertEquals(before,api.writes,"Reopening a completed job cannot replay its writes");
            var report=new LinkedHashMap<String,Object>();report.put("marketplace",marketplace.name());report.put("gtins",10_000);report.put("liveSellerMutations",false);report.put("simulatedApi",true);report.put("realApplicationPlannerAdaptersAndSqlite",true);report.put("writes",api.writes);report.put("reads",api.reads);report.put("maximumRequestItems",api.maximumItems);report.put("maximumRequestBytes",api.maximumBodyBytes);report.put("verified",10_000);report.put("elapsedMs",(System.nanoTime()-started)/1_000_000.0);report.put("quotaWaitSimulatedSeparately",true);
            Path output=Path.of("target","gtin-bulk-"+marketplace.name().toLowerCase(Locale.ROOT)+"-10000.json");Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }
    @Test void siblingsOfOneWbCardShareOneUpdateWithoutLosingAnyOriginalSize()throws Exception {
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var api=new Api(Marketplace.WILDBERRIES,3);var first=api.info.get("100000");var sizes=first.getAsJsonArray("sizes");
        for(int i=1;i<3;i++)sizes.add(api.info.remove(Integer.toString(100000+i)).getAsJsonArray("sizes").get(0).deepCopy());
        var selections=new ArrayList<Selection>();var registered=new ArrayList<RegisteredGtin>();for(int i=0;i<3;i++){var key=new ProductKey(shop.getId(),shop.getMarketplace(),"100000",Integer.toString(200000+i));selections.add(new Selection(key,gtin(i)));registered.add(new RegisteredGtin(gtin(i),"article-0","Blue","52",true,true,RegisteredGtinProof.source(shop),Instant.now(),new CatalogProductData(Map.of("@catalog_id",List.of(Integer.toString(91+i)),"@catalog_status",List.of("published"),"tnved",List.of("6112110000")),List.of(),List.of())));}new GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),registered);
        try(var session=new GtinBulkSession(shop,api,id->{},GtinBulkPipelineTest::freshSyntheticSource)){session.prepare(selections.stream().map(Selection::key).toList());var plan=session.catalog.preview(shop,selections,new Options(Set.of(Field.GTIN),Map.of()));var done=session.catalog.run(shop,plan.id(),true);assertEquals(1,api.writes);assertEquals(3,done.rows().stream().filter(r->r.state()==State.SUCCEEDED).count());assertEquals(3,api.applied.size());assertEquals(3,first.getAsJsonArray("sizes").size());}
    }
    @Test void responseLossIsReconciledForEveryMemberWithoutReplayingAnyBatch()throws Exception {
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);var api=new Api(Marketplace.OZON,230);api.loseResponse=true;var selected=seed(shop,api);
        String id;
        try(var session=new GtinBulkSession(shop,api,n->{},GtinBulkPipelineTest::freshSyntheticSource)){session.prepare(selected.stream().map(Selection::key).toList());var plan=session.catalog.preview(shop,selected,new Options(Set.of(Field.GTIN),Map.of()));id=plan.id();var done=session.catalog.run(shop,id,true);assertTrue(done.rows().stream().allMatch(r->r.state()==State.SUCCEEDED));assertEquals(3,api.writes);}
        new GtinSyncRepository().recoverInterrupted();
        try(var reopened=new GtinBulkSession(shop,api,n->{},GtinBulkPipelineTest::freshSyntheticSource)){reopened.catalog.run(shop,id,false);assertEquals(3,api.writes);}
    }
    @Test void ozonPartialRejectionDoesNotRejectItsSuccessfulSiblings()throws Exception {
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);var api=new Api(Marketplace.OZON,105);api.rejected.add("100003");var selected=seed(shop,api);
        try(var session=new GtinBulkSession(shop,api,n->{},GtinBulkPipelineTest::freshSyntheticSource)){session.prepare(selected.stream().map(Selection::key).toList());var plan=session.catalog.preview(shop,selected,new Options(Set.of(Field.GTIN),Map.of()));var done=session.catalog.run(shop,plan.id(),true);assertEquals(2,api.writes);assertEquals(104,done.rows().stream().filter(r->r.state()==State.SUCCEEDED).count());var failed=done.rows().stream().filter(r->r.state()==State.BLOCKED).toList();assertEquals(1,failed.size());assertEquals("100003",failed.getFirst().key().productId());assertEquals("OZON_BARCODE_BAD_CODE",failed.getFirst().issue());}
    }
    @Test void workerAdvancesSharedParentOnceAndReportsEveryMembersOwnResult()throws Exception {
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);var api=new Api(Marketplace.OZON,105);api.rejected.add("100003");var selected=seed(shop,api);var reports=new SyncRunRepository();String run=reports.begin(shop,"znack_marketplace","MATCHING");
        reports.addBatch(shop,run,selected.stream().map(s->new SyncRunRepository.Item(UUID.randomUUID().toString(),run,s.key().productId(),s.key().variantId(),"sku","article","Blue","52",s.gtin(),"AWAITING_CONFIRMATION","REVIEW","","","{}")).toList());
        try(var session=new GtinBulkSession(shop,api,n->{},GtinBulkPipelineTest::freshSyntheticSource)){
            session.prepare(selected.stream().map(Selection::key).toList());var plan=session.catalog.preview(shop,selected,new Options(Set.of(Field.GTIN),Map.of()));reports.linkReviewed(shop,run,session.catalog.confirm(shop,plan.id()));
            var worker=new ZnackAutoSyncService((s,issues)->{throw new AssertionError("Existing confirmed parent must not refresh unrelated sources");},(s,k)->{throw new AssertionError("New plans already freeze product attributes");},session.catalog);
            assertFalse(worker.step(shop,"").complete());assertEquals(2,api.writes);assertEquals(104,reports.counts(run).get("SUCCEEDED"));assertEquals(1,reports.counts(run).get("NEEDS_ACTION"));
            var rejected=reports.items(run,0,1000).stream().filter(i->i.state().equals("NEEDS_ACTION")).findFirst().orElseThrow();assertEquals("100003",rejected.productId());assertEquals("OZON_BARCODE_BAD_CODE",rejected.error());assertFalse(reports.events(run,rejected.id(),10).isEmpty());
            assertTrue(worker.step(shop,"").complete());assertEquals("PARTIAL",reports.find(run).orElseThrow().state());assertEquals(2,api.writes);
        }
    }
    @Test void delayedWbReadbackIsPolledByPageAndDoesNotResendAcknowledgedWrites()throws Exception {
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var api=new Api(Marketplace.WILDBERRIES,120);api.apply=false;var selected=seed(shop,api);String id;
        try(var session=new GtinBulkSession(shop,api,n->{},GtinBulkPipelineTest::freshSyntheticSource)){session.prepare(selected.stream().map(Selection::key).toList());var plan=session.catalog.preview(shop,selected,new Options(Set.of(Field.GTIN),Map.of()));id=plan.id();var done=session.catalog.run(shop,id,true);assertTrue(done.rows().stream().allMatch(r->r.state()==State.GTIN_PENDING));assertEquals(1,api.writes);assertEquals(3,api.errorListReads,"One error-list read per poll, never one per SKU");assertTrue(api.reads<25);}
        api.acceptPending();new GtinSyncRepository().recoverInterrupted();
        try(var reopened=new GtinBulkSession(shop,api,n->{},GtinBulkPipelineTest::freshSyntheticSource)){var done=reopened.catalog.run(shop,id,false);assertTrue(done.rows().stream().allMatch(r->r.state()==State.SUCCEEDED));assertEquals(1,api.writes);}
    }
    @Test void explicit429MayRetryButA503ResponseMustOnlyReconcile()throws Exception {
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);var api=new Api(Marketplace.OZON,12);api.rateLimitOnce=true;var selected=seed(shop,api);
        try(var session=new GtinBulkSession(shop,api,n->{},GtinBulkPipelineTest::freshSyntheticSource)){session.prepare(selected.stream().map(Selection::key).toList());var plan=session.catalog.preview(shop,selected,new Options(Set.of(Field.GTIN),Map.of()));var done=session.catalog.run(shop,plan.id(),true);assertEquals(2,api.attempts);assertEquals(1,api.writes);assertTrue(done.rows().stream().allMatch(r->r.state()==State.SUCCEEDED));}
    }
    @Test void ozonChangedColorBeforeDispatchIsBlockedWithoutAWrite()throws Exception {
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);var api=new Api(Marketplace.OZON,2);var selected=seed(shop,api);
        try(var session=new GtinBulkSession(shop,api,n->{},GtinBulkPipelineTest::freshSyntheticSource)){session.prepare(selected.stream().map(Selection::key).toList());var plan=session.catalog.preview(shop,selected,new Options(Set.of(Field.GTIN),Map.of()));
            api.attributes.get("100000").getAsJsonArray("attributes").get(0).getAsJsonObject().getAsJsonArray("values").get(0).getAsJsonObject().addProperty("value","Red");
            var done=session.catalog.run(shop,plan.id(),true);assertEquals(1,done.rows().stream().filter(r->r.state()==State.BLOCKED).count());assertEquals(1,done.rows().stream().filter(r->r.state()==State.SUCCEEDED).count());assertFalse(api.applied.containsKey(gtin(0)));assertTrue(api.applied.containsKey(gtin(1)));
        }
    }
    static void freshSyntheticSource(Shop shop,Set<String> requested){
        // The synthetic Catalog is independent from later marketplace edits. This
        // explicit fixture refresh re-observes only those immutable registry facts.
        var repository=new GtinSyncRepository();var rows=repository.catalogItems(shop.getId(),shop.getMarketplace(),requested);assertEquals(requested,rows.keySet());
        var refreshed=rows.values().stream().map(row->new RegisteredGtin(row.gtin(),row.article(),row.color(),row.size(),row.tradeUnit(),row.published(),row.source(),Instant.now(),row.details())).toList();
        repository.mergeCatalog(shop.getId(),shop.getMarketplace(),refreshed);RegisteredGtinProof.requireCurrent(shop,requested);
    }
    static List<Selection> seed(Shop shop,Api api){
        var selected=new ArrayList<Selection>();var source=new ArrayList<RegisteredGtin>();String proof=RegisteredGtinProof.source(shop);int index=0;
        for(var e:api.info.entrySet()){String id=e.getKey();var key=new ProductKey(shop.getId(),shop.getMarketplace(),id,shop.getMarketplace()==Marketplace.WILDBERRIES?text(e.getValue().getAsJsonArray("sizes").get(0).getAsJsonObject(),"chrtID"):text(e.getValue(),"offer_id"));String code=gtin(index++);selected.add(new Selection(key,code));source.add(new RegisteredGtin(code,"article-"+(index-1),"Blue","52",true,true,proof,Instant.now(),new CatalogProductData(Map.of("@catalog_id",List.of(id),"@catalog_status",List.of("published"),"tnved",List.of("6112110000")),List.of(),List.of())));}
        new GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),source);return selected;
    }
    static String gtin(int index){String base=String.format(Locale.ROOT,"460%09d",index+1);int sum=0;for(int i=0;i<base.length();i++)sum+=(base.charAt(i)-'0')*(i%2==0?1:3);return base+(10-sum%10)%10;}
    static String text(JsonObject value,String key){return value.has(key)?value.get(key).getAsString():"";}

    static final class Api implements ListingTransport {
        final Marketplace market;final LinkedHashMap<String,JsonObject> info=new LinkedHashMap<>(),attributes=new LinkedHashMap<>();final Map<String,String> applied=new HashMap<>();final Set<String> rejected=new HashSet<>();
        final List<JsonElement> waiting=new ArrayList<>();int reads,writes,attempts,maximumItems,maximumBodyBytes,errorListReads;boolean apply=true,loseResponse,rateLimitOnce;long revision=1;
        Api(Marketplace market,int count){this.market=market;for(int i=0;i<count;i++){
            String id=Integer.toString(100000+i);JsonObject card;
            if(market==Marketplace.WILDBERRIES)card=JsonParser.parseString("{\"nmID\":"+id+",\"subjectID\":77,\"vendorCode\":\"article-"+i+"\",\"title\":\"Synthetic product "+i+"\",\"brand\":\"Fixture\",\"description\":\"Keep this description\",\"kizMarked\":true,\"dimensions\":{\"length\":12,\"width\":13,\"height\":14,\"weightBrutto\":0.25},\"characteristics\":[{\"id\":99,\"name\":\"Цвет\",\"value\":[\"Blue\"]},{\"id\":88,\"name\":\"ТН ВЭД\",\"value\":[\"6112110000\"]}],\"sizes\":[{\"chrtID\":"+(200000+i)+",\"techSize\":\"52\",\"wbSize\":\"52\",\"skus\":[\"legacy-"+i+"\"]}],\"documents\":{\"items\":[{\"id\":\"doc-"+i+"\",\"type\":2,\"number\":\"D-"+i+"\"}]}} ").getAsJsonObject();
            else {card=JsonParser.parseString("{\"id\":"+id+",\"offer_id\":\"article-"+i+"\",\"sku\":"+(300000+i)+",\"name\":\"Synthetic product "+i+"\",\"barcodes\":[\"legacy-"+i+"\"],\"is_archived\":false}").getAsJsonObject();var attr=JsonParser.parseString("{\"id\":"+id+",\"offer_id\":\"article-"+i+"\",\"name\":\"Synthetic product "+i+"\",\"description_category_id\":77,\"type_id\":88,\"dimension_unit\":\"mm\",\"weight_unit\":\"g\",\"depth\":100,\"width\":100,\"height\":100,\"weight\":250,\"barcodes\":[\"legacy-"+i+"\"],\"attributes\":[{\"id\":99,\"values\":[{\"value\":\"Blue\"}]},{\"id\":100,\"values\":[{\"value\":\"52\"}]},{\"id\":88,\"values\":[{\"value\":\"6112110000\"}]}]}").getAsJsonObject();attributes.put(id,attr);}
            card.addProperty("updatedAt",Instant.ofEpochSecond(revision).toString());info.put(id,card);
        }}
        @Override public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException {
            if(mutation){attempts++;if(rateLimitOnce){rateLimitOnce=false;throw new ListingRateLimitException(new okhttp3.Headers.Builder().add("Retry-After","0").build());}writes++;maximumBodyBytes=Math.max(maximumBodyBytes,body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);maximumItems=Math.max(maximumItems,market==Marketplace.WILDBERRIES?body.getAsJsonArray().size():body.getAsJsonObject().getAsJsonArray("barcodes").size());if(apply)apply(body);else waiting.add(body.deepCopy());if(loseResponse)throw new IOException("Synthetic response lost after commit");
                if(market==Marketplace.WILDBERRIES)return JsonParser.parseString("{\"error\":false}");var errors=new JsonArray();for(var e:body.getAsJsonObject().getAsJsonArray("barcodes")){var value=e.getAsJsonObject();String id=Long.toString(value.get("sku").getAsLong()-200000);if(rejected.contains(id)){var error=value.deepCopy();error.addProperty("code","BAD_CODE");errors.add(error);}}var result=new JsonObject();result.add("errors",errors);return result;}
            reads++;var request=body.getAsJsonObject();
            if(path.equals("content/v2/get/cards/trash"))return JsonParser.parseString("{\"cards\":[],\"cursor\":{\"total\":0}}");
            if(path.equals("content/v2/get/cards/list")){
                var cursor=request.getAsJsonObject("settings").getAsJsonObject("cursor");String after=cursor.has("updatedAt")?text(cursor,"updatedAt"):"";long afterId=cursor.has("nmID")?cursor.get("nmID").getAsLong():0;
                var candidates=info.values().stream().filter(v->after.isBlank()||Instant.parse(text(v,"updatedAt")).compareTo(Instant.parse(after))>0||text(v,"updatedAt").equals(after)&&Long.parseLong(text(v,"nmID"))>afterId).sorted(Comparator.comparing((JsonObject v)->Instant.parse(text(v,"updatedAt"))).thenComparingLong(v->Long.parseLong(text(v,"nmID")))).limit(100).toList();var cards=new JsonArray();candidates.forEach(v->cards.add(v.deepCopy()));var next=new JsonObject();next.addProperty("total",cards.size());if(!candidates.isEmpty()){var last=candidates.getLast();next.addProperty("updatedAt",text(last,"updatedAt"));next.addProperty("nmID",last.get("nmID").getAsLong());}var result=new JsonObject();result.add("cards",cards);result.add("cursor",next);return result;
            }
            if(path.equals("content/v2/cards/error/list")){errorListReads++;return JsonParser.parseString("{\"data\":{\"items\":[],\"cursor\":{\"next\":false}}}");}
            if(path.equals("v3/product/list")){int start=request.has("last_id")&&!text(request,"last_id").isBlank()?Integer.parseInt(text(request,"last_id")):0;var entries=new ArrayList<>(info.keySet());if("ARCHIVED".equals(text(request.getAsJsonObject("filter"),"visibility")))entries.clear();int end=Math.min(start+1000,entries.size());var rows=new JsonArray();for(var id:entries.subList(start,end)){var row=new JsonObject();row.addProperty("product_id",Long.parseLong(id));rows.add(row);}var result=new JsonObject();var data=new JsonObject();data.add("items",rows);data.addProperty("total_items",entries.size());data.addProperty("last_id",Integer.toString(end));result.add("result",data);return result;}
            if(path.equals("v3/product/info/list")||path.equals("v4/product/info/attributes")){boolean attr=path.contains("attributes");var filter=attr?request.getAsJsonObject("filter"):request;var result=new JsonObject();var rows=new JsonArray();for(var id:filter.getAsJsonArray("product_id")){var value=(attr?attributes:info).get(id.getAsString());if(value!=null)rows.add(value.deepCopy());}result.add(attr?"result":"items",rows);return result;}
            if(path.equals("v1/description-category/attribute"))return JsonParser.parseString("{\"result\":[{\"id\":99,\"name\":\"Цвет товара\"},{\"id\":100,\"name\":\"Размер производителя\"},{\"id\":88,\"name\":\"ТН ВЭД\"}]}");
            throw new AssertionError("GTIN-only bulk work must not request certificates/prices or unknown endpoints: "+path);
        }
        @Override public JsonElement call(String path,JsonElement body,boolean mutation,Runnable guard)throws IOException{guard.run();return call(path,body,mutation);}
        void acceptPending(){for(var request:waiting)apply(request);waiting.clear();}
        void apply(JsonElement body){revision++;if(market==Marketplace.WILDBERRIES){for(var e:body.getAsJsonArray()){var update=e.getAsJsonObject();String id=text(update,"nmID");assertEquals(info.get(id).get("title"),update.get("title"));assertEquals(info.get(id).get("documents"),update.get("documents"));var nativeCard=info.get(id);var value=nativeCard.deepCopy();for(var field:update.entrySet())value.add(field.getKey(),field.getValue().deepCopy());
                // WB POST accepts characteristic ID/value; native GET also supplies names.
                var names=new HashMap<String,String>();for(var attribute:nativeCard.getAsJsonArray("characteristics"))names.put(text(attribute.getAsJsonObject(),"id"),text(attribute.getAsJsonObject(),"name"));
                for(var attribute:value.getAsJsonArray("characteristics")){var detail=attribute.getAsJsonObject();assertEquals(Set.of("id","value"),detail.keySet());String name=names.get(text(detail,"id"));assertNotNull(name);detail.addProperty("name",name);}
                value.addProperty("subjectID",77);value.addProperty("updatedAt",Instant.ofEpochSecond(revision).toString());info.put(id,value);for(var size:value.getAsJsonArray("sizes"))for(var code:size.getAsJsonObject().getAsJsonArray("skus"))if(!code.getAsString().startsWith("legacy-"))applied.put(code.getAsString(),id);}}
            else for(var e:body.getAsJsonObject().getAsJsonArray("barcodes")){var value=e.getAsJsonObject();String id=Long.toString(value.get("sku").getAsLong()-200000);if(rejected.contains(id))continue;String code=text(value,"barcode");info.get(id).getAsJsonArray("barcodes").add(code);attributes.get(id).getAsJsonArray("barcodes").add(code);applied.put(code,id);}
        }
    }
}
