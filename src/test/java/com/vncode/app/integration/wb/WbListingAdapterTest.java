package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.features.listingcopy.ListingCopyMappingTest.*;

class WbListingAdapterTest {
    @TempDir Path root;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'WB','fixture','WILDBERRIES')");}}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void articleCollisionRequiresExplicitChoice()throws Exception{
        var adapter=new WbListingAdapter(s->(p,b,m)->p.equals("content/v2/get/cards/list")?JsonParser.parseString("{\"cards\":[{\"nmID\":7,\"vendorCode\":\"NEW\"}]}"):fixture("99").call(p,b,m),i->{throw new java.io.IOException();});
        var p=adapter.preview(target(Marketplace.WILDBERRIES),source(Marketplace.OZON),options(Marketplace.WILDBERRIES));assertFalse(p.publishable());assertTrue(p.issues().stream().anyMatch(i->i.code().equals("ARTICLE_COLLISION")));
    }
    @Test void lostResponseSendsOnlyOnceAndDoesNotInventTask()throws Exception{
        var writes=new AtomicInteger();var adapter=new WbListingAdapter(s->(p,b,m)->{if(m){writes.incrementAndGet();throw new java.io.IOException("timeout");}return fixture("99").call(p,b,m);},i->{throw new java.io.IOException();});
        var p=adapter.preview(target(Marketplace.WILDBERRIES),source(Marketplace.OZON),options(Marketplace.WILDBERRIES));assertThrows(java.io.IOException.class,()->adapter.submit(target(Marketplace.WILDBERRIES),p));assertEquals(1,writes.get());
    }
    @Test void swappedPicturesDoNotReconcileFromTheirCountAlone()throws Exception{
        var adapter=new WbListingAdapter(s->(p,b,m)->JsonParser.parseString("{\"cards\":[{\"nmID\":321,\"vendorCode\":\"NEW\",\"title\":\"VN\",\"photos\":[{\"big\":\"https://basket-01.wbbasket.ru/2.jpg\"},{\"big\":\"https://basket-01.wbbasket.ru/1.jpg\"}]}]}"),i->new ListingMediaInspector.Inspected(i.uri().toString().endsWith("1.jpg")?"FIRST":"SECOND",900,1200,"image/jpeg"));
        String media="[{\"url\":\"https://basket-01.wbbasket.ru/1.jpg\",\"sha256\":\"FIRST\"},{\"url\":\"https://basket-01.wbbasket.ru/2.jpg\",\"sha256\":\"SECOND\"}]";
        var shop=target(Marketplace.WILDBERRIES);var r=new RemoteSubmission(1,Marketplace.WILDBERRIES,ListingIdentity.fingerprint(shop),"hash",null,List.of("NEW"),"[{\"subjectID\":99,\"variants\":[{\"vendorCode\":\"NEW\",\"title\":\"VN\"}]}]",media,"[]","ASSETS_SENT",Map.of("NEW","321"));
        assertNotEquals(CopyState.RECONCILED,adapter.reconcile(shop,r).state());
    }
    @Test void completeOverwriteRetainsUntouchedSizesDocumentsAndMarking()throws Exception{
        String raw="""
          {"nmID":123,"vendorCode":"OLD","title":"Keep","brand":"Old","description":"Keep description","kizMarked":true,
          "dimensions":{"length":12,"width":13,"height":14,"weightBrutto":0.123,"isValid":true},
          "characteristics":[{"id":777,"value":["Cotton"]}],"sizes":[{"chrtID":1234,"techSize":"M","wbSize":"46","skus":["04610689132457"]}],
          "documents":{"items":[{"id":"owned-doc","type":2,"number":"D","verdict":{"verified":true,"status":1}}]}}
          """;
        var shop=target(Marketplace.WILDBERRIES);var s=source(Marketplace.WILDBERRIES);var own=new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"123","1234"),"live:source",s.title(),s.description(),s.brand(),"OLD","99",s.media(),s.attributes(),s.variants(),s.dimensions(),s.documents(),s.checkedAt(),Map.of("source.payload",raw,"owner",ListingIdentity.fingerprint(shop)));
        var adapter=new WbListingAdapter(x->fixture("99"),i->{throw new java.io.IOException();});var preview=adapter.previewUpdate(shop,own,Map.of("brand",List.of("VN")),null).preview();assertTrue(preview.publishable());var body=JsonParser.parseString(preview.requestJson()).getAsJsonArray().get(0).getAsJsonObject();assertEquals("VN",body.get("brand").getAsString());assertTrue(body.get("kizMarked").getAsBoolean());assertEquals(1234,body.getAsJsonArray("sizes").get(0).getAsJsonObject().get("chrtID").getAsInt());assertEquals("owned-doc",body.getAsJsonObject("documents").getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());assertFalse(preview.requestJson().contains("verdict"));assertFalse(preview.requestJson().contains("isValid"));
    }
}
