package com.vncode.app.features.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.listingcopy.CopyState;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual reader, payload adapter, SQLite journal and remote readback together. */
class ProductBulkEditWbIntegrationTest {
    @TempDir Path root;
    Shop shop=new Shop(1,"WB",Marketplace.WILDBERRIES,null,"fixture");
    ProductKey key=new ProductKey(1,Marketplace.WILDBERRIES,"123","101");
    AtomicReference<JsonObject> remote=new AtomicReference<>();AtomicInteger writes=new AtomicInteger();
    ProductBulkEditService service;WbProductSnapshotReader reader;boolean rejected,deferredRejection;Instant errorTime;
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'WB','fixture','WILDBERRIES')");}
        remote.set(JsonParser.parseString(ProductSnapshotReaderTest.WB).getAsJsonObject().getAsJsonArray("cards").get(0).getAsJsonObject());
        ListingTransport transport=(path,body,mutation)->{
            if(path.equals("content/v2/get/cards/list")){var r=new JsonObject();var a=new JsonArray();a.add(remote.get().deepCopy());r.add("cards",a);return r;}
            if(path.startsWith("content/v2/object/charcs/"))return JsonParser.parseString("""
                {"data":[{"charcID":723,"name":"Состав","charcType":1},
                  {"charcID":724,"name":"Пол","charcType":1},{"charcID":725,"name":"ТН ВЭД","charcType":1}]}
                """);
            if(path.equals("content/v2/cards/update")){
                assertTrue(mutation);writes.incrementAndGet();
                if(rejected)return JsonParser.parseString("{\"error\":true,\"errorText\":\"Invalid TN VED; token=fixture-secret\"}");
                if(!deferredRejection){var patch=body.getAsJsonArray().get(0).getAsJsonObject();
                    assertEquals(123,patch.get("nmID").getAsInt());assertEquals(2,patch.getAsJsonArray("sizes").size());
                    assertEquals("04610689132457",patch.getAsJsonArray("sizes").get(0).getAsJsonObject().getAsJsonArray("skus").get(0).getAsString());
                    var actual=remote.get().deepCopy();patch.entrySet().forEach(e->actual.add(e.getKey(),e.getValue().deepCopy()));remote.set(actual);
                }return JsonParser.parseString("{\"error\":false}");
            }
            if(path.equals("content/v2/cards/error/list"))return JsonParser.parseString("{\"data\":{\"items\":[{\"updatedAt\":\""+(errorTime==null?Instant.now():errorTime)+"\",\"vendorCodes\":[\"ARTICLE\"],\"errors\":{\"ARTICLE\":[\"Rejected material\"]}}]}}");
            throw new AssertionError("Unexpected endpoint "+path);
        };
        reader=new WbProductSnapshotReader(s->transport,s->(p,b,m)->{throw new AssertionError("Editing product fields must not read prices");});
        var adapter=new WbListingAdapter(s->transport,i->{throw new AssertionError("No image writes");});
        service=new ProductBulkEditService(new ProductEditRepository(),reader::readForSynchronization,s->adapter);
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void editsBrand()throws Exception{allFiveEditorsWriteTheRealPayloadAndVerifyItThroughTheSameReader("brand");}
    @Test void editsGender()throws Exception{allFiveEditorsWriteTheRealPayloadAndVerifyItThroughTheSameReader("gender");}
    @Test void editsMaterial()throws Exception{allFiveEditorsWriteTheRealPayloadAndVerifyItThroughTheSameReader("material");}
    @Test void editsTnved()throws Exception{allFiveEditorsWriteTheRealPayloadAndVerifyItThroughTheSameReader("tnved");}
    @Test void editsDocuments()throws Exception{allFiveEditorsWriteTheRealPayloadAndVerifyItThroughTheSameReader("documents");}
    void allFiveEditorsWriteTheRealPayloadAndVerifyItThroughTheSameReader(String field)throws Exception{
        var document=new ProductDocument("","DECLARATION","NEW-DOC",LocalDate.of(2026,1,1),LocalDate.of(2028,1,1),"","",null,Marketplace.WILDBERRIES,DocumentState.UNVERIFIED,"",null);
        String value=switch(field){case "brand"->"New brand";case "gender"->"Мужской";case "tnved"->"6110209100";default->"хлопок 100%";};
        var edit=new ProductBulkEdit(field,field.equals("documents")?List.of():List.of(value),field.equals("documents")?List.of(document):null,Map.of());
        var events=new ArrayList<ProductBulkEditService.Progress>();var preview=service.preview(shop,List.of(key),edit,events::add);
        assertTrue(preview.publishable());var sent=service.apply(shop,preview.id(),true,events::add);
        assertEquals(CopyState.REMOTE_PROCESSING,sent.items().getFirst().state());
        assertEquals(CopyState.RECONCILED,service.reconcile(shop,preview.id(),events::add).items().getFirst().state());
        assertEquals(1,writes.get());assertTrue(events.stream().anyMatch(p->p.step().equals("SENDING")&&p.completed()==1));
        if(field.equals("brand"))assertEquals(value,remote.get().get("brand").getAsString());
        if(field.equals("documents"))assertEquals(2,remote.get().getAsJsonObject("documents").getAsJsonArray("items").size(),"Existing documents must survive");
        service.apply(shop,preview.id(),true);assertEquals(1,writes.get(),"Reopening a completed edit cannot resend it");
    }
    @Test void explicitRejectionIsDurableAndDoesNotWaitForever()throws Exception{
        rejected=true;var preview=service.preview(shop,List.of(key),new ProductBulkEdit("brand",List.of("New"),null,Map.of()));
        var item=service.apply(shop,preview.id(),true).items().getFirst();assertEquals(CopyState.FAILED,item.state());
        assertTrue(item.issues().getFirst().field().contains("Invalid TN VED"));assertFalse(item.issues().getFirst().field().contains("fixture-secret"));
        service.apply(shop,preview.id(),true);service.reconcile(shop,preview.id());assertEquals(1,writes.get());
    }
    @Test void asynchronousRejectionIsDetectedEvenWhileTheOldProductStillExists()throws Exception{
        deferredRejection=true;var preview=service.preview(shop,List.of(key),new ProductBulkEdit("brand",List.of("New"),null,Map.of()));
        service.apply(shop,preview.id(),true);var item=service.reconcile(shop,preview.id()).items().getFirst();
        assertEquals(CopyState.FAILED,item.state());assertTrue(item.issues().getFirst().field().contains("Rejected material"));assertEquals(1,writes.get());
    }
    @Test void aPendingJobFromThePreviousVersionUsesItsOriginalDispatchJournal()throws Exception{
        deferredRejection=true;var preview=service.preview(shop,List.of(key),new ProductBulkEdit("brand",List.of("New"),null,Map.of()));
        service.apply(shop,preview.id(),true);
        try(var c=Database.getConnection();var q=c.prepareStatement("UPDATE product_edit_items SET payload=json_remove(payload,'$.submission.submittedAt') WHERE job_id=?")){q.setString(1,preview.id());q.executeUpdate();}
        var item=service.reconcile(shop,preview.id()).items().getFirst();assertEquals(CopyState.FAILED,item.state());
        assertNotNull(item.submission().submittedAt());assertEquals(1,writes.get());
    }
    @Test void anOlderUnrelatedErrorCannotRejectTheCurrentUpdate()throws Exception{
        deferredRejection=true;errorTime=Instant.now().minusSeconds(3600);
        var preview=service.preview(shop,List.of(key),new ProductBulkEdit("brand",List.of("New"),null,Map.of()));
        service.apply(shop,preview.id(),true);assertEquals(CopyState.REMOTE_PROCESSING,service.reconcile(shop,preview.id()).items().getFirst().state());
        assertEquals(1,writes.get());
    }
    @Test void registrationMultiFieldCorrectionUsesTheSamePriceFreeRevisionAsItsWrite()throws Exception{
        var fresh=reader.readForSynchronization(shop,key);
        var preview=service.previewPatch(shop,fresh,Map.of("brand",List.of("Corrected brand"),"attribute:724",List.of("Мужской")),null);
        service.apply(shop,preview.id(),true);
        assertEquals(CopyState.RECONCILED,service.reconcile(shop,preview.id()).items().getFirst().state());
        assertEquals("Corrected brand",remote.get().get("brand").getAsString());assertEquals(1,writes.get());
    }
}
