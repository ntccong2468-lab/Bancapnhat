package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackApiClient;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NationalCatalogVersionSourceTest {
    @TempDir Path directory;
    final String scope="National Catalog@owner-fixture";
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    NationalCatalogVersionSource source(NationalCatalogVersionSource.VersionReader index,NationalCatalogVersionSource.IdReader cards,boolean resume){
        return new NationalCatalogVersionSource(1,scope,index,cards,g->{throw new AssertionError("An indexed card must not be read again by GTIN");},List::of,null,null,p->{},()->{},resume);
    }
    @Test void moreThanTenThousandCardsAreEnumeratedByIdWithNoProductListOffsetOverflow()throws Exception{
        var offsets=new ArrayList<Integer>();var batches=new ArrayList<List<String>>();
        var result=source(offset->{offsets.add(offset);return page(offset,10_001);},ids->{assertTrue(ids.size()<=25);batches.add(ids);return cards(ids);},true).read(1);
        assertEquals(10_001,result.size());assertEquals(101,offsets.size());assertEquals(10_000,offsets.getLast());assertEquals(401,batches.size());
        assertEquals(10_001,batches.stream().flatMap(List::stream).distinct().count());assertEquals(gtin(10_001),result.getLast().gtin());
        assertTrue(result.stream().allMatch(c->c.source().equals(scope)&&!c.published()));
        assertEquals(0,new CatalogVersionCheckpoint(1,scope).load(true).offset());
    }
    @Test void networkAndQuotaRecoveryResumeBothIndexAndCardBatchesWithoutRepeatingCompletedReads()throws Exception{
        var initial=source(offset->{if(offset==100)throw new ZnackApiClient.ZnackApiException("fixture",429,"{}");return page(offset,101);},ids->{fail("Index incomplete");return null;},true);
        assertThrows(ZnackApiClient.ZnackApiException.class,()->initial.read(1));assertEquals(100,new CatalogVersionCheckpoint(1,scope).load(true).offset());
        var offsets=new ArrayList<Integer>();var seen=new ArrayList<String>();
        assertThrows(java.net.UnknownHostException.class,()->source(offset->{offsets.add(offset);return page(offset,101);},ids->{if(ids.getFirst().equals("26"))throw new java.net.UnknownHostException("fixture");seen.addAll(ids);return cards(ids);},true).read(1));
        assertEquals(List.of(100),offsets);assertEquals(25,seen.size());
        var oldTime=new CatalogVersionCheckpoint(1,scope).entries(false).getFirst().observedAt();
        var result=source(offset->{fail("Saved complete index must not be requested again");return null;},ids->{assertFalse(ids.contains("1"));seen.addAll(ids);return cards(ids);},true).read(1);
        assertEquals(101,result.size());assertEquals(101,new HashSet<>(seen).size());assertEquals(101,seen.size());assertEquals(oldTime,result.getFirst().syncedAt());
    }
    @Test void changingTotalDuplicateIdsAndUnexpectedCardsCannotProveAnEmptyCatalog(){
        var changing=source(offset->page(offset,offset==0?101:102),NationalCatalogVersionSourceTest::cards,true);
        assertEquals("CATALOG_INDEX_CHANGED",assertThrows(IllegalStateException.class,()->changing.read(1)).getMessage());
        assertEquals(0,new CatalogVersionCheckpoint(1,scope).load(true).offset());
        var duplicate=source(offset->{var p=page(offset,101);if(offset==100)p.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("goods").get(0).getAsJsonObject().addProperty("good_id",1);return p;},NationalCatalogVersionSourceTest::cards,true);
        assertThrows(IllegalStateException.class,()->duplicate.read(1));assertEquals(0,new CatalogVersionCheckpoint(1,scope).load(true).offset());
        assertEquals("CATALOG_INDEX_CARD_IDENTITY_CHANGED",assertThrows(IllegalStateException.class,()->source(o->page(o,1),ids->cards(List.of("2")),true).read(1)).getMessage());
    }
    @Test void aListedCardNotYetReadableStaysPendingAndDoesNotAuthorizeNewRegistration()throws Exception{
        assertEquals("CATALOG_INDEX_CARD_PENDING",assertThrows(IOException.class,()->source(o->page(o,1),ids->JsonParser.parseString("{\"result\":[]}"),true).read(1)).getMessage());
        assertTrue(new CatalogVersionCheckpoint(1,scope).load(true).listed());
        assertEquals(1,source(o->{fail("Reuse index");return null;},NationalCatalogVersionSourceTest::cards,true).read(1).size());
    }
    @Test void unknownGtinAndConflictingVersionNeverBecomeCompleteAbsence(){
        var missing=source(o->page(o,1),ids->{var c=cards(ids).getAsJsonObject();var row=c.getAsJsonArray("result").get(0).getAsJsonObject();row.remove("gtin");row.remove("identified_by");return c;},true);
        assertEquals("CATALOG_INDEX_CARD_GTIN_MISSING",assertThrows(IllegalStateException.class,()->missing.read(1)).getMessage());
        var changed=source(o->page(o,1),ids->{var c=cards(ids).getAsJsonObject();c.getAsJsonArray("result").get(0).getAsJsonObject().addProperty("_etag","changed");return c;},true);
        assertEquals("CATALOG_INDEX_CHANGED",assertThrows(IllegalStateException.class,()->changed.read(1)).getMessage());
    }
    @Test void aFreshRegistrationLookupCannotBorrowOrClearTheBackgroundCursor()throws Exception{
        assertThrows(java.net.UnknownHostException.class,()->source(o->{if(o>0)throw new java.net.UnknownHostException("fixture");return page(o,101);},NationalCatalogVersionSourceTest::cards,true).read(1));
        var offsets=new ArrayList<Integer>();assertEquals(1,source(o->{offsets.add(o);return page(o,1);},NationalCatalogVersionSourceTest::cards,false).read(1).size());
        assertEquals(List.of(0),offsets);assertEquals(100,new CatalogVersionCheckpoint(1,scope).load(true).offset());
    }
    @Test void ownerScopeIsRecheckedBeforeAnyRemoteRead(){
        var source=new NationalCatalogVersionSource(1,scope,o->{fail("Changed owner cannot read");return null;},NationalCatalogVersionSourceTest::cards,g->null,List::of,null,null,p->{},()->{throw new SecurityException("CATALOG_OWNER_CHANGED");},true);
        assertThrows(SecurityException.class,()->source.read(1));
    }
    static JsonElement page(int offset,int total){var result=new JsonObject();var rows=new JsonArray();for(int id=offset+1;id<=Math.min(offset+100,total);id++){var row=new JsonObject();row.addProperty("good_id",id);row.addProperty("etag","etag-"+id);rows.add(row);}result.add("goods",rows);result.addProperty("offset",offset);result.addProperty("goods_count",rows.size());result.addProperty("last_product_number",offset+rows.size());result.addProperty("total",total);var response=new JsonObject();response.add("result",result);return response;}
    static JsonElement cards(List<String> ids){var response=new JsonObject();var rows=new JsonArray();for(String id:ids){var card=new JsonObject();card.addProperty("good_id",id);card.addProperty("gtin",gtin(Integer.parseInt(id)));card.addProperty("good_status","draft");card.addProperty("_etag","etag-"+id);rows.add(card);}response.add("result",rows);return response;}
    static String gtin(int value){String body=String.format(Locale.ROOT,"%013d",value);int sum=0;for(int n=0;n<body.length();n++)sum+=(body.charAt(n)-'0')*(n%2==0?3:1);return body+(10-sum%10)%10;}
}
