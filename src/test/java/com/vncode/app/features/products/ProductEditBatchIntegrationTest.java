package com.vncode.app.features.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.listing.ListingJson.*;

/** Simulated HTTP; actual readers, adapters, snapshots, batch claims and SQLite readback. */
class ProductEditBatchIntegrationTest {
    @TempDir Path root;
    Shop shop;Api api;ProductBulkEditService service;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace,client_id) VALUES(1,'WB','fixture','WILDBERRIES',NULL),(2,'Ozon','fixture','OZON','22')");}
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    List<ProductKey> configure(Marketplace market,int count){
        shop=market==Marketplace.WILDBERRIES?new Shop(1,"WB","fixture"):new Shop(2,"Ozon",market,"22","fixture");
        api=new Api(market,count);service=new ProductBulkEditService(new ProductEditRepository(),(s,keys)->ProductEditApiSession.open(s,keys,api,true));
        return api.info.keySet().stream().map(id->new ProductKey(shop.getId(),market,id,market==Marketplace.WILDBERRIES?Integer.toString(Integer.parseInt(id)+100000):"article-"+id)).toList();
    }
    @Test void wbOneThousandProductEditsUseTenWritesAndPageReadback()throws Exception{large(Marketplace.WILDBERRIES);}
    @Test void ozonOneThousandProductEditsUseTenPartialAttributeTasksWithoutPrices()throws Exception{large(Marketplace.OZON);}
    void large(Marketplace market)throws Exception{
        var keys=configure(market,1000);long started=System.nanoTime();
        var events=new ArrayList<ProductBulkEditService.Progress>();
        var p=service.preview(shop,keys,new ProductBulkEdit("brand",List.of("Updated"),null,Map.of()),events::add);assertTrue(p.publishable());
        var sent=service.apply(shop,p.id(),true,events::add);assertTrue(sent.items().stream().allMatch(i->i.state()==CopyState.REMOTE_PROCESSING));
        assertEquals(10,api.writes);assertEquals(100,api.maxItems);
        var done=service.reconcile(shop,p.id(),events::add);assertTrue(done.items().stream().allMatch(i->i.state()==CopyState.RECONCILED));
        assertTrue(events.stream().anyMatch(v->v.step().equals("READBACK")&&v.completed()==1000));
        assertTrue(api.dictionaryReads<=3,"One dictionary per phase/category");
        if(market==Marketplace.WILDBERRIES)assertTrue(api.reads<75,"Reads scale with pages and batches");
        else assertEquals(10,api.taskReads,"Shared task is read once per poll, not once per product");
        service.apply(shop,p.id(),true);assertEquals(10,api.writes,"Completed edits never resend");
        var evidence=new LinkedHashMap<String,Object>();evidence.put("marketplace",market.name());evidence.put("products",1000);evidence.put("simulatedApi",true);evidence.put("liveSellerMutations",false);evidence.put("writes",api.writes);evidence.put("reads",api.reads);evidence.put("dictionaryReads",api.dictionaryReads);evidence.put("taskReads",api.taskReads);evidence.put("maxItems",api.maxItems);evidence.put("verified",1000);evidence.put("elapsedMs",(System.nanoTime()-started)/1_000_000.0);
        Files.writeString(Path.of("target/product-edits-"+market.name().toLowerCase(Locale.ROOT)+"-1000.json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
    }
    @Test void responseLostAfterBatchCommitIsReadBackWithoutResend()throws Exception{
        var keys=configure(Marketplace.WILDBERRIES,105);api.loseResponse=true;
        var p=service.preview(shop,keys,new ProductBulkEdit("brand",List.of("Updated"),null,Map.of()));
        assertTrue(service.apply(shop,p.id(),true).items().stream().allMatch(i->i.state()==CopyState.RECONCILE_REQUIRED));
        new ProductEditRepository().recoverIncomplete();
        assertTrue(service.reconcile(shop,p.id()).items().stream().allMatch(i->i.state()==CopyState.RECONCILED));
        service.apply(shop,p.id(),true);assertEquals(2,api.writes);
    }
    @Test void oneRejectedOzonTaskMemberDoesNotRejectOrResendItsSiblings()throws Exception{
        var keys=configure(Marketplace.OZON,105);api.rejected="100003";
        var p=service.preview(shop,keys,new ProductBulkEdit("brand",List.of("Updated"),null,Map.of()));service.apply(shop,p.id(),true);
        var result=service.reconcile(shop,p.id());assertEquals(104,result.items().stream().filter(i->i.state()==CopyState.RECONCILED).count());assertEquals(1,result.items().stream().filter(i->i.state()==CopyState.FAILED).count());
        service.apply(shop,p.id(),true);assertEquals(2,api.writes);assertEquals(2,api.taskReads);assertTrue(result.items().stream().filter(i->i.state()==CopyState.FAILED).findFirst().orElseThrow().issues().getFirst().field().contains("Rejected brand"));
    }
    @Test void secondBatchChangedDuringFirstWriteIsBlockedBeforeItsHttpWrite()throws Exception{
        var keys=configure(Marketplace.WILDBERRIES,105);api.changeLater=true;
        var p=service.preview(shop,keys,new ProductBulkEdit("brand",List.of("Updated"),null,Map.of()));
        assertThrows(IllegalStateException.class,()->service.apply(shop,p.id(),true));assertEquals(1,api.writes);
        var result=service.result(shop,p.id());assertEquals(100,result.items().stream().filter(i->i.state()==CopyState.REMOTE_PROCESSING).count());assertEquals(5,result.items().stream().filter(i->i.state()==CopyState.NEEDS_DATA).count());
    }
    @Test void conflictOnSecondClaimRollsBackTheWholeBatch()throws Exception{
        var keys=configure(Marketplace.WILDBERRIES,3);var p=service.preview(shop,keys,new ProductBulkEdit("brand",List.of("Updated"),null,Map.of()));
        var other=service.preview(shop,List.of(keys.get(1)),new ProductBulkEdit("brand",List.of("Other"),null,Map.of()));var repo=new ProductEditRepository();assertTrue(repo.claim(other.id(),other.items().getFirst()));
        assertThrows(IllegalStateException.class,()->service.apply(shop,p.id(),true));assertEquals(0,api.writes);assertTrue(repo.items(p.id()).stream().allMatch(i->i.state()==CopyState.AWAITING_CONFIRMATION));
    }
    @Test void knownRateLimitKeepsTheUnsentBatchRetryable()throws Exception{
        var keys=configure(Marketplace.WILDBERRIES,3);api.rateLimit=true;var p=service.preview(shop,keys,new ProductBulkEdit("brand",List.of("Updated"),null,Map.of()));
        assertThrows(ListingRateLimitException.class,()->service.apply(shop,p.id(),true));assertTrue(service.result(shop,p.id()).items().stream().allMatch(i->i.state()==CopyState.AWAITING_CONFIRMATION));assertEquals(0,api.writes);
        api.rateLimit=false;service.apply(shop,p.id(),true);assertEquals(1,api.writes);
    }
    @Test void licenseDeniedWhileWaitingForSendReleasesEveryUnsentClaim()throws Exception{
        var keys=configure(Marketplace.WILDBERRIES,3);var p=service.preview(shop,keys,new ProductBulkEdit("brand",List.of("Updated"),null,Map.of()));
        var prior=com.vncode.app.features.license.LicenseRuntime.service();
        try(var denied=com.vncode.app.features.license.LicenseDomainFixture.denied(root.resolve("denied"))){
            api.beforeSend=()->com.vncode.app.features.license.LicenseRuntime.install(denied);
            assertThrows(com.vncode.app.features.license.LicenseGate.LicenseDeniedException.class,()->service.apply(shop,p.id(),true));assertEquals(0,api.writes);
            assertTrue(new ProductEditRepository().items(p.id()).stream().allMatch(i->i.state()==CopyState.AWAITING_CONFIRMATION));
        }finally{com.vncode.app.features.license.LicenseRuntime.install(prior);}
    }
    @Test void cachedNumericArticleDoesNotHideAnotherCardWithThatNmid()throws Exception{
        var keys=configure(Marketplace.WILDBERRIES,2);api.info.get("100000").addProperty("vendorCode","100001");
        var p=service.preview(shop,keys,new ProductBulkEdit("brand",List.of("Updated"),null,Map.of()));service.apply(shop,p.id(),true);
        assertTrue(service.reconcile(shop,p.id()).items().stream().allMatch(i->i.state()==CopyState.RECONCILED));assertEquals(1,api.writes);
    }
    @Test void pendingReadbackPreservesTheOriginalOzonProductIdentity()throws Exception{
        var keys=configure(Marketplace.OZON,1);service=new ProductBulkEditService(new ProductEditRepository(),(s,k)->ProductEditApiSession.open(s,k,api,false));
        var p=service.preview(shop,keys,new ProductBulkEdit("brand",List.of("Updated"),null,Map.of()));service.apply(shop,p.id(),true);api.pending=true;
        var waiting=service.reconcile(shop,p.id()).items().getFirst();assertEquals(CopyState.REMOTE_PROCESSING,waiting.state());assertEquals("100000",waiting.submission().identities().get("article-100000"));
        api.pending=false;api.info.get("100000").addProperty("id",999999);
        assertThrows(IOException.class,()->service.reconcile(shop,p.id()));assertEquals(1,api.writes);
    }
    @Test void ozonWritesBrand()throws Exception{ozonRequestedAttributeIsWrittenAndUnrelatedValuesRemain("brand");}
    @Test void ozonWritesGender()throws Exception{ozonRequestedAttributeIsWrittenAndUnrelatedValuesRemain("gender");}
    @Test void ozonWritesMaterial()throws Exception{ozonRequestedAttributeIsWrittenAndUnrelatedValuesRemain("material");}
    @Test void ozonWritesTnved()throws Exception{ozonRequestedAttributeIsWrittenAndUnrelatedValuesRemain("tnved");}
    void ozonRequestedAttributeIsWrittenAndUnrelatedValuesRemain(String field)throws Exception{
        var keys=configure(Marketplace.OZON,2);String value=switch(field){case "gender"->"Мужской";case "material"->"хлопок 100%";case "tnved"->"6110209100";default->"Updated";};
        var p=service.preview(shop,keys,new ProductBulkEdit(field,field.equals("material")?List.of("хлопок 80%","полиэстер 20%"):List.of(value),null,Map.of()));assertTrue(p.publishable());service.apply(shop,p.id(),true);
        assertTrue(service.reconcile(shop,p.id()).items().stream().allMatch(i->i.state()==CopyState.RECONCILED));assertEquals(1,api.writes);
        for(var card:api.attrs.values()){if(field.equals("material"))assertEquals("80% хлопок, 20% полиэстер",card.getAsJsonArray("attributes").asList().stream().map(JsonElement::getAsJsonObject).filter(a->text(a,"id").equals("4604")).findFirst().orElseThrow().getAsJsonArray("values").get(0).getAsJsonObject().get("value").getAsString());assertEquals(2,card.getAsJsonArray("barcodes").size());if(!field.equals("brand"))assertEquals("Old",card.getAsJsonArray("attributes").asList().stream().map(JsonElement::getAsJsonObject).filter(a->text(a,"id").equals("85")).findFirst().orElseThrow().getAsJsonArray("values").get(0).getAsJsonObject().get("value").getAsString());}
    }
    static final class Api implements ListingTransport {
        Runnable beforeSend=()->{};
        public JsonElement call(String path,JsonElement body,boolean mutation,Runnable guard)throws IOException{if(mutation)beforeSend.run();guard.run();return call(path,body,mutation);}
        final Marketplace market;final Map<String,JsonObject> info=new LinkedHashMap<>(),attrs=new LinkedHashMap<>();
        final Map<String,List<String>> tasks=new HashMap<>();long version=1;int reads,writes,maxItems,dictionaryReads,taskReads;boolean loseResponse,changeLater,rateLimit,pending;String rejected="";
        Api(Marketplace market,int count){this.market=market;for(int n=0;n<count;n++){
            String id=Integer.toString(100000+n),article="article-"+id;
            if(market==Marketplace.WILDBERRIES){var card=JsonParser.parseString(ProductSnapshotReaderTest.WB).getAsJsonObject().getAsJsonArray("cards").get(0).getAsJsonObject().deepCopy();card.addProperty("nmID",Integer.parseInt(id));card.addProperty("vendorCode",article);card.addProperty("updatedAt",Instant.ofEpochSecond(version).toString());var sizes=card.getAsJsonArray("sizes");while(sizes.size()>1)sizes.remove(1);sizes.get(0).getAsJsonObject().addProperty("chrtID",200000+n);info.put(id,card);}
            else{info.put(id,JsonParser.parseString("{\"id\":"+id+",\"offer_id\":\""+article+"\",\"sku\":"+(300000+n)+",\"barcodes\":[\"legacy-a\",\"legacy-b\"]}").getAsJsonObject());attrs.put(id,JsonParser.parseString("{\"id\":"+id+",\"offer_id\":\""+article+"\",\"name\":\"Keep\",\"description_category_id\":9,\"type_id\":8,\"dimension_unit\":\"mm\",\"weight_unit\":\"g\",\"attributes\":[{\"id\":85,\"values\":[{\"value\":\"Old\"}]}],\"barcodes\":[\"legacy-a\",\"legacy-b\"]}").getAsJsonObject());}
        }}
        public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException{
            if(mutation){
                if(rateLimit)throw new ListingRateLimitException(new okhttp3.Headers.Builder().add("Retry-After","0").build());
                writes++;var items=market==Marketplace.WILDBERRIES?body.getAsJsonArray():body.getAsJsonObject().getAsJsonArray("items");maxItems=Math.max(maxItems,items.size());
                var members=new ArrayList<String>();
                for(var e:items){var item=e.getAsJsonObject();if(market==Marketplace.WILDBERRIES){assertEquals("content/v2/cards/update",path);var card=info.get(text(item,"nmID"));item.entrySet().forEach(v->card.add(v.getKey(),v.getValue().deepCopy()));card.addProperty("updatedAt",Instant.ofEpochSecond(++version).toString());}
                    else{assertEquals("v1/product/attributes/update",path);assertFalse(item.has("price"));assertFalse(item.has("barcodes"));String id=text(item,"offer_id").substring(8);members.add(id);if(!id.equals(rejected)){var fields=attrs.get(id).getAsJsonArray("attributes");for(var changed:item.getAsJsonArray("attributes")){String attrId=text(changed.getAsJsonObject(),"id");for(int n=fields.size()-1;n>=0;n--)if(text(fields.get(n).getAsJsonObject(),"id").equals(attrId))fields.remove(n);fields.add(changed.deepCopy());}}}
                }
                if(changeLater&&writes==1){var changed=info.get("100104");changed.addProperty("brand","External edit");changed.addProperty("updatedAt",Instant.ofEpochSecond(++version).toString());}
                if(loseResponse)throw new IOException("Synthetic lost response after remote commit");
                if(market==Marketplace.WILDBERRIES)return JsonParser.parseString("{\"error\":false}");tasks.put(Integer.toString(writes),members);return JsonParser.parseString("{\"task_id\":"+writes+"}");
            }
            reads++;var request=body==null?new JsonObject():body.getAsJsonObject();
            if(path.equals("content/v2/get/cards/trash"))return JsonParser.parseString("{\"cards\":[],\"cursor\":{\"total\":0}}");
            if(path.equals("content/v2/get/cards/list")){
                var settings=obj(request,"settings");String search=text(obj(settings,"filter"),"textSearch");var cursor=obj(settings,"cursor");String after=text(cursor,"updatedAt");long afterId=cursor.has("nmID")?cursor.get("nmID").getAsLong():0;
                var rows=info.values().stream().filter(c->search.isBlank()||text(c,"nmID").equals(search)||text(c,"vendorCode").equals(search)).filter(c->after.isBlank()||Instant.parse(text(c,"updatedAt")).isAfter(Instant.parse(after))||text(c,"updatedAt").equals(after)&&c.get("nmID").getAsLong()>afterId).sorted(Comparator.comparing((JsonObject c)->Instant.parse(text(c,"updatedAt"))).thenComparingLong(c->c.get("nmID").getAsLong())).limit(100).toList();
                var result=new JsonObject();var cards=new JsonArray();rows.forEach(c->cards.add(c.deepCopy()));result.add("cards",cards);var next=new JsonObject();next.addProperty("total",cards.size());if(!rows.isEmpty()){next.addProperty("updatedAt",text(rows.getLast(),"updatedAt"));next.addProperty("nmID",rows.getLast().get("nmID").getAsLong());}result.add("cursor",next);return result;
            }
            if(path.startsWith("content/v2/object/charcs/")){dictionaryReads++;return JsonParser.parseString("{\"data\":[]}");}
            if(path.equals("content/v2/cards/error/list"))return JsonParser.parseString("{\"data\":{\"items\":[]}}");
            if(path.equals("v3/product/list")){var result=new JsonObject();var data=new JsonObject();var rows=new JsonArray();if(text(request,"last_id").isBlank())for(var id:info.keySet()){var row=new JsonObject();row.addProperty("product_id",Long.parseLong(id));rows.add(row);}data.add("items",rows);data.addProperty("last_id","end");result.add("result",data);return result;}
            if(path.equals("v3/product/info/list")||path.equals("v4/product/info/attributes")){boolean attributes=path.contains("attributes");var filter=attributes?obj(request,"filter"):request;var result=new JsonObject();var rows=new JsonArray();if(filter.has("product_id"))for(var id:array(filter,"product_id"))rows.add((attributes?attrs:info).get(id.getAsString()).deepCopy());else for(var offer:array(filter,"offer_id"))info.values().stream().filter(c->text(c,"offer_id").equals(offer.getAsString())).forEach(c->rows.add(c.deepCopy()));result.add(attributes?"result":"items",rows);return result;}
            if(path.equals("v1/description-category/attribute")){dictionaryReads++;return JsonParser.parseString("{\"result\":[{\"id\":85,\"name\":\"Бренд\",\"type\":\"string\",\"max_value_count\":1},{\"id\":226,\"name\":\"Пол\",\"type\":\"string\"},{\"id\":4496,\"name\":\"Материал\",\"type\":\"string\",\"dictionary_id\":1503},{\"id\":4604,\"name\":\"Состав материала\",\"type\":\"String\",\"is_collection\":false,\"max_value_count\":0},{\"id\":88,\"name\":\"ТН ВЭД\",\"type\":\"string\"}]}");}
            if(path.equals("v1/product/certificate/list"))return JsonParser.parseString("{\"result\":{\"certificates\":[],\"page_count\":1}}");
            if(path.equals("v1/product/import/info")){taskReads++;var result=new JsonObject();var data=new JsonObject();var rows=new JsonArray();for(String id:tasks.get(text(request,"task_id"))){var item=new JsonObject();item.addProperty("offer_id","article-"+id);item.addProperty("status",id.equals(rejected)?"failed":pending?"pending":"imported");item.add("errors",JsonParser.parseString(id.equals(rejected)?"[{\"code\":\"INVALID\",\"message\":\"Rejected brand\"}]":"[]"));rows.add(item);}data.add("items",rows);result.add("result",data);return result;}
            throw new AssertionError("Unexpected endpoint "+path);
        }
    }
}
