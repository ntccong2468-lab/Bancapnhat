package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingTransport;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class WbRegistrationSnapshotBatchTest {
    @TempDir Path directory;Shop shop;
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=new Shop(1,"WB",Marketplace.WILDBERRIES,null,"fixture");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'WB','WILDBERRIES',NULL,'fixture')");}
    }
    @AfterEach void cleanup(){Thread.interrupted();System.clearProperty("vncode.appdata.dir");}
    ProductKey key(int id){return new ProductKey(1,Marketplace.WILDBERRIES,Integer.toString(id),Integer.toString(id*10));}
    static JsonObject card(int id){
        var card=new JsonObject();card.addProperty("nmID",id);card.addProperty("subjectID",10);card.addProperty("title","Product "+id);card.addProperty("vendorCode","Article "+id);card.addProperty("brand","Brand");
        card.add("dimensions",new JsonObject());card.add("characteristics",new JsonArray());var sizes=new JsonArray();var size=new JsonObject();size.addProperty("chrtID",id*10);size.addProperty("techSize","M");size.add("skus",new Gson().toJsonTree(List.of("barcode-"+id)));sizes.add(size);card.add("sizes",sizes);return card;
    }
    static JsonObject page(List<JsonObject> cards,int cursorId){
        var result=new JsonObject();var rows=new JsonArray();cards.forEach(rows::add);result.add("cards",rows);var cursor=new JsonObject();cursor.addProperty("total",cards.size());cursor.addProperty("updatedAt","2026-01-01T00:00:00Z");cursor.addProperty("nmID",cursorId);result.add("cursor",cursor);return result;
    }
    void noPersistentSnapshots()throws Exception {try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM product_snapshots")){assertTrue(r.next());assertEquals(0,r.getInt(1));}}
    @ParameterizedTest @ValueSource(ints={300,10_000})
    void bulkReadUsesPagesRatherThanOneHttpRequestPerCard(int count)throws Exception {
        var selected=new ArrayList<ProductKey>();for(int i=1;i<=count;i++)selected.add(key(i));var calls=new AtomicInteger();
        ListingTransport transport=(path,body,mutation)->{
            assertFalse(mutation);assertEquals("content/v2/get/cards/list",path);var settings=body.getAsJsonObject().getAsJsonObject("settings");
            assertFalse(settings.getAsJsonObject("filter").has("textSearch"));assertTrue(settings.getAsJsonObject("sort").get("ascending").getAsBoolean());assertEquals(100,settings.getAsJsonObject("cursor").get("limit").getAsInt());
            int current=calls.getAndIncrement(),start=current*100;assertTrue(start<count,"Do not issue per-card or redundant complete scans");
            if(current>0)assertEquals(start,settings.getAsJsonObject("cursor").get("nmID").getAsInt());
            var cards=new ArrayList<JsonObject>();for(int i=start+1;i<=Math.min(start+100,count);i++)cards.add(card(i));return page(cards,Math.min(start+100,count));
        };
        try(var checks=com.vncode.app.integration.listing.ListingIdentity.batchChecks(shop);var batch=new WbRegistrationSnapshotBatch(shop,selected,transport)){
            for(var key:selected){var actual=batch.read(key);assertEquals(key,actual.key());assertEquals("Article "+key.cardId(),actual.vendorArticle());assertEquals(List.of("barcode-"+key.cardId()),actual.variants().getFirst().barcodes());}
            assertEquals(count/100,calls.get());assertTrue(batch.retainedBytes()<=16L*1024*1024);
        }
        noPersistentSnapshots();
    }
    @Test void shortPageAndUnrelatedIncompleteCardsDoNotHideLaterTargets()throws Exception {
        var calls=new AtomicInteger();var unrelated=new JsonObject();unrelated.addProperty("nmID",999);
        try(var batch=new WbRegistrationSnapshotBatch(shop,List.of(key(1)),(p,b,m)->calls.incrementAndGet()==1?page(List.of(unrelated),999):page(List.of(card(1)),1))){assertEquals("Article 1",batch.read(key(1)).vendorArticle());assertEquals(2,calls.get());}
    }
    @Test void onlyAnEmptyPageConfirmsThatASelectedCardIsAbsent()throws Exception {
        var calls=new AtomicInteger();
        try(var batch=new WbRegistrationSnapshotBatch(shop,List.of(key(1),key(2)),(p,b,m)->calls.incrementAndGet()==1?page(List.of(card(1)),1):page(List.of(),1))){
            assertEquals(key(1),batch.read(key(1)).key());var absent=assertThrows(WbProductSnapshotReader.CardReadException.class,()->batch.read(key(2)));
            assertEquals("WB_CARD_NOT_FOUND",absent.code());assertEquals(key(2),absent.key());assertEquals(2,calls.get());
        }
    }
    @Test void incompleteSelectedCardHasExactMissingFieldsAndDoesNotPoisonHealthyCards()throws Exception {
        var incomplete=card(1);incomplete.remove("dimensions");incomplete.add("characteristics",JsonNull.INSTANCE);var calls=new AtomicInteger();
        try(var batch=new WbRegistrationSnapshotBatch(shop,List.of(key(1),key(2)),(p,b,m)->{calls.incrementAndGet();return page(List.of(incomplete,card(2)),2);})){
            var failure=assertThrows(WbProductSnapshotReader.CardReadException.class,()->batch.read(key(1)));
            assertEquals("WB_CARD_INCOMPLETE",failure.code());assertEquals(key(1),failure.key());assertEquals(List.of("characteristics","dimensions"),failure.missingFields());
            assertEquals(key(2),batch.read(key(2)).key());assertEquals(1,calls.get());
        }
        noPersistentSnapshots();
    }
    @Test void aMissingVariantNeverReusesAnotherSizesFacts()throws Exception {
        var missing=new ProductKey(1,Marketplace.WILDBERRIES,"1","999");
        try(var batch=new WbRegistrationSnapshotBatch(shop,List.of(missing),(p,b,m)->page(List.of(card(1)),1))){var failure=assertThrows(WbProductSnapshotReader.CardReadException.class,()->batch.read(missing));assertEquals("WB_VARIANT_NOT_FOUND",failure.code());assertEquals(missing,failure.key());}
    }
    @Test void repeatedCursorIsAGlobalReadFailureRatherThanCardAbsence()throws Exception {
        var calls=new AtomicInteger();
        try(var batch=new WbRegistrationSnapshotBatch(shop,List.of(key(1)),(p,b,m)->{calls.incrementAndGet();return page(List.of(card(999)),999);})){
            var failure=assertThrows(IOException.class,()->batch.read(key(1)));assertFalse(failure instanceof WbProductSnapshotReader.CardReadException);assertEquals("WB_CATALOG_CURSOR_INVALID",failure.getMessage());assertEquals(2,calls.get());
            assertEquals("WB_REGISTRATION_BATCH_FAILED",assertThrows(IOException.class,()->batch.read(key(1))).getMessage());
        }
    }
    @Test void contradictoryCursorAndApiLevelErrorsCannotLookLikeEmptySuccessfulReads()throws Exception {
        for(String mode:List.of("count","error","cards")){
            var response=page(List.of(card(1)),1);
            if(mode.equals("count"))response.getAsJsonObject("cursor").addProperty("total",0);
            if(mode.equals("error"))response.addProperty("error",true);
            if(mode.equals("cards"))response.remove("cards");
            try(var batch=new WbRegistrationSnapshotBatch(shop,List.of(key(1)),(p,b,m)->response)){var failure=assertThrows(IOException.class,()->batch.read(key(1)));assertFalse(failure instanceof WbProductSnapshotReader.CardReadException,mode);}
        }
    }
    @Test void selectedDuplicatesAreRejectedInsteadOfChoosingOnePayload()throws Exception {
        try(var batch=new WbRegistrationSnapshotBatch(shop,List.of(key(1),key(2)),(p,b,m)->page(List.of(card(1),card(1)),1))){assertEquals("WB_CATALOG_DUPLICATE_PRODUCT",assertThrows(IOException.class,()->batch.read(key(1))).getMessage());}
    }
    @Test void transportFailureKeepsItsGlobalIdentity()throws Exception {
        var expected=new java.net.SocketTimeoutException("fixture unavailable");
        try(var batch=new WbRegistrationSnapshotBatch(shop,List.of(key(1)),(p,b,m)->{throw expected;})){assertSame(expected,assertThrows(IOException.class,()->batch.read(key(1))));}
    }
    @Test void credentialsChangedDuringHttpCannotReturnFreshEvidence()throws Exception {
        try(var batch=new WbRegistrationSnapshotBatch(shop,List.of(key(1)),(p,b,m)->{
            try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE shops SET api_key='rotated' WHERE id=1");}catch(java.sql.SQLException failure){throw new IOException(failure);}return page(List.of(card(1)),1);
        })){assertThrows(SecurityException.class,()->batch.read(key(1)));}noPersistentSnapshots();
    }
    @Test void cancelledAndUnselectedReadsStartNoRequest()throws Exception {
        var calls=new AtomicInteger();try(var batch=new WbRegistrationSnapshotBatch(shop,List.of(key(1)),(p,b,m)->{calls.incrementAndGet();return page(List.of(card(1)),1);})){
            assertThrows(SecurityException.class,()->batch.read(key(2)));Thread.currentThread().interrupt();
            try{assertThrows(InterruptedIOException.class,()->batch.read(key(1)));}finally{Thread.interrupted();}assertEquals(0,calls.get());
        }
    }
    @Test void selectedSpillIsPrivateAndRemovedOnCloseWithoutChangingPersistentData()throws Exception {
        Path spill;var batch=new WbRegistrationSnapshotBatch(shop,List.of(key(1),key(2)),(p,b,m)->page(List.of(card(1),card(2)),2),1);
        try(batch){assertEquals(key(1),batch.read(key(1)).key());assertEquals(key(2),batch.read(key(2)).key());assertEquals(0,batch.retainedBytes());spill=batch.spillDirectory();assertNotNull(spill);try(var files=Files.list(spill)){assertEquals(2,files.count());}
            var posix=Files.getFileAttributeView(spill,PosixFileAttributeView.class);if(posix!=null)assertEquals(PosixFilePermissions.fromString("rwx------"),posix.readAttributes().permissions());
            else {var acl=Files.getFileAttributeView(spill,AclFileAttributeView.class);assertNotNull(acl);assertEquals(1,acl.getAcl().size());assertEquals(Files.getOwner(spill),acl.getAcl().getFirst().principal());}
        }
        assertFalse(Files.exists(spill));assertEquals("WB_REGISTRATION_BATCH_CLOSED",assertThrows(IOException.class,()->batch.read(key(1))).getMessage());noPersistentSnapshots();
    }
}
