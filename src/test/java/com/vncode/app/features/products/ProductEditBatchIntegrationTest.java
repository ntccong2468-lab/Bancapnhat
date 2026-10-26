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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    @ParameterizedTest
    @CsvSource({"brand,1","brand,19","brand,20","material,20","gender,20","tnved,20","documents,20"})
    void wbProductPageBatchUsesCardIdentitiesForAllFiveEditors(String field,int count)throws Exception{
        configure(Marketplace.WILDBERRIES,count);
        try(var c=Database.getConnection();var insert=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,brand,synced_at) VALUES(1,?,?,?,'Old','now')")){
            for(var card:api.info.values()){
                insert.setString(1,text(card,"nmID"));insert.setString(2,text(card,"title"));insert.setString(3,text(card,"vendorCode"));insert.addBatch();
            }
            insert.executeBatch();
        }
        var keys=new ProductRepository().freezeProductKeys(new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,100));
        assertEquals(count,keys.size());assertTrue(keys.stream().allMatch(k->k.variantId().isBlank()));
        ListingTransport transport=(path,body,mutation)->path.startsWith("content/v2/object/charcs/")?JsonParser.parseString("""
            {"data":[{"charcID":723,"name":"Состав","charcType":1},
              {"charcID":724,"name":"Пол","charcType":1},{"charcID":725,"name":"ТН ВЭД","charcType":1}]}
            """):api.call(path,body,mutation);
        service=new ProductBulkEditService(new ProductEditRepository(),(s,k)->ProductEditApiSession.open(s,k,transport));
        String value=switch(field){case "gender"->"Мужской";case "material"->"хлопок 100%";case "tnved"->"6112110000";default->"Updated";};
        var doc=new ProductDocument("","DECLARATION","NEW-DOC",java.time.LocalDate.of(2026,1,1),java.time.LocalDate.of(2028,1,1),"","",null,Marketplace.WILDBERRIES,DocumentState.UNVERIFIED,"",null);
        var edit=new ProductBulkEdit(field,field.equals("documents")?List.of():List.of(value),field.equals("documents")?List.of(doc):null,Map.of());
        var events=new ArrayList<ProductBulkEditService.Progress>();
        var preview=service.preview(shop,keys,edit,events::add);assertTrue(preview.publishable());assertEquals(0,api.writes);
        service.apply(shop,preview.id(),true,events::add);
        var result=service.reconcile(shop,preview.id(),events::add);
        assertEquals(count,result.items().stream().filter(i->i.state()==CopyState.RECONCILED).count());assertEquals(1,api.writes);
        assertTrue(events.stream().anyMatch(p->p.succeeded()==count&&p.failed()==0&&p.remaining()==0));
        for(var card:api.info.values()){
            assertEquals(1,card.getAsJsonArray("sizes").size());
            assertEquals("04610689132457",card.getAsJsonArray("sizes").get(0).getAsJsonObject().getAsJsonArray("skus").get(0).getAsString());
            if(field.equals("brand"))assertEquals(value,text(card,"brand"));
            if(field.equals("documents"))assertEquals(2,card.getAsJsonObject("documents").getAsJsonArray("items").size());
        }
        service.apply(shop,preview.id(),true);assertEquals(1,api.writes);
    }
    void large(Marketplace market)throws Exception{
        var keys=configure(market,1000);long started=System.nanoTime();
        if(market==Marketplace.WILDBERRIES)keys=keys.stream().map(k->new ProductKey(k.shopId(),k.marketplace(),k.cardId(),"")).toList();
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
    @Test void ozonExistingCertificateBindsWithoutUploadingAndWaitsForReadback()throws Exception{
        var keys=configure(Marketplace.OZON,2);var remote=new CertificateFixture();remote.bound.add("article-100001");var preview=certificatePreview(keys,remote);assertTrue(preview.publishable());
        assertEquals(0,remote.binds);service.apply(shop,preview.id(),true);assertEquals(0,remote.binds);
        assertTrue(service.reconcile(shop,preview.id()).items().stream().allMatch(i->i.state()==CopyState.REMOTE_PROCESSING));assertEquals(2,remote.binds);
        assertTrue(service.reconcile(shop,preview.id()).items().stream().allMatch(i->i.state()==CopyState.RECONCILED));
        service.apply(shop,preview.id(),true);service.reconcile(shop,preview.id());assertEquals(2,remote.binds);assertEquals(0,api.writes);
    }
    @ParameterizedTest @ValueSource(strings={"rejected","expired","id","number","type","date"})
    void ozonCertificateInvalidOrChangedBeforeApplyCannotBind(String mismatch)throws Exception{
        var keys=configure(Marketplace.OZON,2);var remote=new CertificateFixture();
        switch(mismatch){case "rejected","expired"->remote.certificate.addProperty("status_code",mismatch);case "id"->remote.certificate.addProperty("certificate_id",78);case "number"->remote.certificate.addProperty("certificate_number","OTHER");case "type"->remote.certificate.addProperty("type_code","certificate");case "date"->remote.certificate.addProperty("expire_date","2029-01-01T00:00:00Z");default->throw new AssertionError();}
        var preview=certificatePreview(keys,remote);assertFalse(preview.publishable());assertThrows(IllegalStateException.class,()->service.apply(shop,preview.id(),true));assertEquals(0,remote.binds);assertEquals(0,api.writes);
    }
    @Test void ozonExplicitCertificateBindRejectionIsFinal()throws Exception{
        var keys=configure(Marketplace.OZON,2);var remote=new CertificateFixture();remote.accept=false;var preview=certificatePreview(keys,remote);service.apply(shop,preview.id(),true);
        var result=service.reconcile(shop,preview.id());assertTrue(result.items().stream().allMatch(i->i.state()==CopyState.FAILED));assertTrue(result.items().stream().flatMap(i->i.issues().stream()).allMatch(i->i.code().equals("MARKETPLACE_REJECTED")));
        service.reconcile(shop,preview.id());assertEquals(2,remote.binds);assertTrue(remote.bound.isEmpty());
    }
    @ParameterizedTest @CsvSource({"true,false","false,true"})
    void ozonLostCertificateBindResponseIsReconciledWithoutAnotherBind(boolean lost,boolean malformed)throws Exception{
        var keys=configure(Marketplace.OZON,2);var remote=new CertificateFixture();remote.loseResponse=lost;remote.malformedAcknowledgement=malformed;var preview=certificatePreview(keys,remote);service.apply(shop,preview.id(),true);
        assertTrue(service.reconcile(shop,preview.id()).items().stream().allMatch(i->i.state()==CopyState.RECONCILE_REQUIRED));new ProductEditRepository().recoverIncomplete();
        assertTrue(service.reconcile(shop,preview.id()).items().stream().allMatch(i->i.state()==CopyState.RECONCILED));assertEquals(2,remote.binds);
    }
    @Test void ozonCertificateNeedsItsExactIdInReadbackAndCanBeOnTheSecondPage()throws Exception{
        var keys=configure(Marketplace.OZON,2);var remote=new CertificateFixture();remote.readbackId=78;remote.pages=2;var preview=certificatePreview(keys,remote);service.apply(shop,preview.id(),true);service.reconcile(shop,preview.id());
        assertTrue(service.reconcile(shop,preview.id()).items().stream().allMatch(i->i.state()==CopyState.REMOTE_PROCESSING));remote.readbackId=77;
        assertTrue(service.reconcile(shop,preview.id()).items().stream().allMatch(i->i.state()==CopyState.RECONCILED));assertEquals(2,remote.binds);
    }
    @Test void ozonCertificateChangedAfterPreviewIsBlockedBeforeBinding()throws Exception{
        var keys=configure(Marketplace.OZON,2);var remote=new CertificateFixture();var preview=certificatePreview(keys,remote);assertTrue(preview.publishable());remote.certificate.addProperty("status_code","rejected");
        assertThrows(IllegalStateException.class,()->service.apply(shop,preview.id(),true));assertEquals(0,remote.binds);
    }
    @Test void ozonCertificateRejectedDuringReadbackIsFailedWithoutRebinding()throws Exception{
        var keys=configure(Marketplace.OZON,2);var remote=new CertificateFixture();var preview=certificatePreview(keys,remote);service.apply(shop,preview.id(),true);service.reconcile(shop,preview.id());remote.certificate.addProperty("status_code","rejected");
        assertTrue(service.reconcile(shop,preview.id()).items().stream().allMatch(i->i.state()==CopyState.FAILED));service.reconcile(shop,preview.id());assertEquals(2,remote.binds);
    }
    @Test void ozonNewDocumentStillUploadsTheFileAndChecksItsBinding()throws Exception{
        var keys=configure(Marketplace.OZON,2);var remote=new CertificateFixture();remote.allowCreate=true;remote.certificate.addProperty("certificate_number","NEW-DOC");
        service=new ProductBulkEditService(new ProductEditRepository(),(s,k)->ProductEditApiSession.open(s,k,remote,true));
        var file=root.resolve("certificate.pdf");Files.writeString(file,"%PDF-1.7\nSynthetic certificate fixture");
        var document=new ProductDocument("","declaration","NEW-DOC",java.time.LocalDate.of(2026,1,1),java.time.LocalDate.of(2028,1,1),"","",file.toUri(),Marketplace.OZON,DocumentState.UNVERIFIED,"",null);
        var preview=service.preview(shop,keys,new ProductBulkEdit("documents",List.of(),List.of(document),Map.of()));assertTrue(preview.publishable());service.apply(shop,preview.id(),true);service.reconcile(shop,preview.id());
        assertEquals(2,remote.creates);assertEquals(0,remote.binds);assertTrue(service.reconcile(shop,preview.id()).items().stream().allMatch(i->i.state()==CopyState.RECONCILED));service.apply(shop,preview.id(),true);assertEquals(2,remote.creates);
    }
    private ProductBulkEditService.ProductEditPreview certificatePreview(List<ProductKey> keys,CertificateFixture remote)throws Exception{
        service=new ProductBulkEditService(new ProductEditRepository(),(s,k)->ProductEditApiSession.open(s,k,remote,true));
        var doc=new ProductDocument("77","declaration","EXISTING-DOC",java.time.LocalDate.of(2026,1,1),java.time.LocalDate.of(2028,1,1),"","",null,Marketplace.OZON,DocumentState.UNVERIFIED,"https://api-seller.ozon.ru/v1/product/certificate/list",Instant.now());
        return service.preview(shop,keys,new ProductBulkEdit("documents",List.of(),List.of(doc),Map.of()));
    }
    private final class CertificateFixture implements ListingTransport {
        final JsonObject certificate=JsonParser.parseString("{\"certificate_id\":77,\"certificate_number\":\"EXISTING-DOC\",\"type_code\":\"declaration\",\"status_code\":\"approved\",\"issue_date\":\"2026-01-01T00:00:00Z\",\"expire_date\":\"2028-01-01T00:00:00Z\"}").getAsJsonObject();
        final Set<String> bound=new HashSet<>();int binds,creates,readbackId=77,pages=1;boolean accept=true,loseResponse,allowCreate,malformedAcknowledgement;
        public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException{
            if(path.equals("v1/product/certificate/info")){assertFalse(mutation);assertEquals("EXISTING-DOC",text(body.getAsJsonObject(),"certificate_number"));var result=new JsonObject();result.add("result",certificate.deepCopy());return result;}
            if(path.equals("v1/product/certificate/list")){var docs=new JsonArray();if(body.getAsJsonObject().get("page").getAsInt()==pages&&bound.contains(text(body.getAsJsonObject(),"offer_id"))){var value=certificate.deepCopy();value.addProperty("certificate_id",readbackId);docs.add(value);}var data=new JsonObject();data.add("certificates",docs);data.addProperty("page_count",pages);var result=new JsonObject();result.add("result",data);return result;}
            if(path.equals("v1/product/certificate/bind")){assertTrue(mutation);assertEquals(77,body.getAsJsonObject().get("certificate_id").getAsInt());for(var sku:body.getAsJsonObject().getAsJsonArray("skus")){assertTrue(sku.getAsString().matches("30000[01]"));if(accept)bound.add("article-"+(sku.getAsInt()-200000));}binds++;if(loseResponse)throw new IOException("Synthetic lost bind acknowledgement");return malformedAcknowledgement?new JsonObject():JsonParser.parseString("{\"result\":"+accept+"}");}
            if(path.equals("v2/product/certification/options"))return JsonParser.parseString("{\"option\":{}}");
            if(path.equals("v2/product/certification/params"))return JsonParser.parseString("{\"params\":[]}");
            if(path.equals("v2/product/certificate/create")){
                if(!allowCreate)throw new AssertionError("An uploaded certificate must be reused");assertTrue(mutation);creates++;var params=body.getAsJsonObject().getAsJsonObject("params");assertEquals("NEW-DOC",text(params,"number"));assertEquals(1,params.getAsJsonArray("files").size());assertTrue(new String(Base64.getDecoder().decode(text(params.getAsJsonArray("files").get(0).getAsJsonObject(),"file_content")),java.nio.charset.StandardCharsets.UTF_8).startsWith("%PDF-1.7"));for(var sku:params.getAsJsonArray("skus"))bound.add("article-"+(sku.getAsInt()-200000));return JsonParser.parseString("{\"status\":\"COMPLETED\",\"certificate_id\":\"77\"}");
            }
            return api.call(path,body,mutation);
        }
    }
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
            if(path.equals("v3/product/list")){var result=new JsonObject();var data=new JsonObject();var rows=new JsonArray();boolean archived=text(obj(request,"filter"),"visibility").equals("ARCHIVED");if(!archived&&text(request,"last_id").isBlank())for(var id:info.keySet()){var row=new JsonObject();row.addProperty("product_id",Long.parseLong(id));rows.add(row);}data.add("items",rows);data.addProperty("total_items",archived?0:info.size());data.addProperty("last_id","end");result.add("result",data);return result;}
            if(path.equals("v3/product/info/list")||path.equals("v4/product/info/attributes")){boolean attributes=path.contains("attributes");var filter=attributes?obj(request,"filter"):request;var result=new JsonObject();var rows=new JsonArray();if(filter.has("product_id"))for(var id:array(filter,"product_id"))rows.add((attributes?attrs:info).get(id.getAsString()).deepCopy());else for(var offer:array(filter,"offer_id"))info.values().stream().filter(c->text(c,"offer_id").equals(offer.getAsString())).forEach(c->rows.add(c.deepCopy()));result.add(attributes?"result":"items",rows);return result;}
            if(path.equals("v1/description-category/attribute")){dictionaryReads++;return JsonParser.parseString("{\"result\":[{\"id\":85,\"name\":\"Бренд\",\"type\":\"string\",\"max_value_count\":1},{\"id\":226,\"name\":\"Пол\",\"type\":\"string\"},{\"id\":4496,\"name\":\"Материал\",\"type\":\"string\",\"dictionary_id\":1503},{\"id\":4604,\"name\":\"Состав материала\",\"type\":\"String\",\"is_collection\":false,\"max_value_count\":0},{\"id\":88,\"name\":\"ТН ВЭД\",\"type\":\"string\"}]}");}
            if(path.equals("v1/product/certificate/list"))return JsonParser.parseString("{\"result\":{\"certificates\":[],\"page_count\":1}}");
            if(path.equals("v1/product/import/info")){taskReads++;var result=new JsonObject();var data=new JsonObject();var rows=new JsonArray();for(String id:tasks.get(text(request,"task_id"))){var item=new JsonObject();item.addProperty("offer_id","article-"+id);item.addProperty("status",id.equals(rejected)?"failed":pending?"pending":"imported");item.add("errors",JsonParser.parseString(id.equals(rejected)?"[{\"code\":\"INVALID\",\"message\":\"Rejected brand\"}]":"[]"));rows.add(item);}data.add("items",rows);result.add("result",data);return result;}
            throw new AssertionError("Unexpected endpoint "+path);
        }
    }
}
