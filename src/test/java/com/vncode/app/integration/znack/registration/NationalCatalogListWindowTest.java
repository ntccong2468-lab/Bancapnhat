package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NationalCatalogListWindowTest {
    @TempDir Path profile;
    Shop shop;String previous;
    @BeforeEach void setup()throws Exception{previous=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void cleanup(){if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);}
    @Test void exactlyTenThousandCardsUseOnlyTheTenSupportedThousandRowPages()throws Exception{
        var pages=new ArrayList<Integer>();var reads=new AtomicInteger();
        var source=new NationalCatalogGtinSource(shop.getId(),page->{assertTrue(page<10,"No request may exceed the documented offset window");pages.add(page);return page(page*1000+1,1000,10_000);},gtin->{reads.incrementAndGet();return card(gtin);}).catalogListWindow();
        var result=source.read(shop.getId());assertEquals(10_000,result.size());assertEquals(10_000,reads.get());assertEquals(java.util.stream.IntStream.range(0,10).boxed().toList(),pages);assertEquals(gtin(1),result.getFirst().gtin());assertEquals(gtin(10_000),result.getLast().gtin());
    }
    @Test void moreThanTheDocumentedWindowIsUnknownAndNeverReturnsAPartialCatalogOrClearsGoodData(){
        var repository=new GtinSyncRepository();var before=repository.catalog(shop.getId(),shop.getMarketplace());var pages=new ArrayList<Integer>();var reads=new AtomicInteger();
        var source=new NationalCatalogGtinSource(shop.getId(),page->{pages.add(page);return page(1,1000,10_001);},gtin->{reads.incrementAndGet();return card(gtin);}).catalogListWindow().resumable();
        var failure=assertThrows(IOException.class,()->source.read(shop.getId()));assertEquals("CATALOG_LIST_RANGE_UNSUPPORTED",failure.getMessage());assertEquals(List.of(0),pages);assertEquals(0,reads.get());assertEquals(before,repository.catalog(shop.getId(),shop.getMarketplace()));
        var remote=new com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException("fixture",413,"{}");var rejected=new NationalCatalogGtinSource(shop.getId(),page->{throw remote;},g->{fail("Rejected list cannot prove absence");return null;}).catalogListWindow();var mapped=assertThrows(IOException.class,()->rejected.read(shop.getId()));assertEquals("CATALOG_LIST_RANGE_UNSUPPORTED",mapped.getMessage());assertSame(remote,mapped.getCause());
    }
    @Test void missingTotalsOversizedPagesAndTruncatedWindowsCannotProveCompleteness(){
        var reads=new AtomicInteger();
        for(JsonElement response:List.of(new JsonArray(),JsonParser.parseString("{\"results\":[]}"),page(1,1001,1001))){
            var source=new NationalCatalogGtinSource(shop.getId(),p->response,g->{reads.incrementAndGet();return card(g);}).catalogListWindow();assertThrows(IOException.class,()->source.read(shop.getId()));
        }
        var truncated=new NationalCatalogGtinSource(shop.getId(),p->p==0?page(1,1000,2000):page(1001,0,2000),g->{reads.incrementAndGet();return card(g);}).catalogListWindow();assertEquals("incomplete_catalog",assertThrows(IOException.class,()->truncated.read(shop.getId())).getMessage());assertEquals(0,reads.get());
    }
    @Test void legacyUnknownOrOutOfWindowCursorsRestartAtPageZeroAndKeepTheGoodCacheOnFailure()throws Exception{
        String source="National Catalog@fixture";var cursor=new CatalogScanCheckpoint(shop.getId(),source);cursor.load();var before=new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace());
        for(var saved:List.of(
                new CatalogScanCheckpoint.State(1,2,false,List.of(gtin(1)),0,List.of(),List.of()),
                new CatalogScanCheckpoint.State(1,2,false,List.of(gtin(1)),0,List.of(),List.of(),10_000),
                new CatalogScanCheckpoint.State(99,10_000,false,List.of(gtin(1)),0,List.of(),List.of(),1000),
                new CatalogScanCheckpoint.State(1,10_001,false,List.of(gtin(1)),0,List.of(),List.of(),1000))){
            cursor.save(saved);var pages=new ArrayList<Integer>();var failure=new java.net.SocketTimeoutException("fixture first-page timeout");
            var reader=new NationalCatalogGtinSource(shop.getId(),page->{pages.add(page);throw failure;},g->{fail("Incomplete lookup must not read cards");return null;},source).catalogListWindow().resumable();assertSame(failure,assertThrows(java.net.SocketTimeoutException.class,()->reader.read(shop.getId())));assertEquals(List.of(0),pages);assertEquals(before,new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()));
        }
    }
    @Test void aCursorFromTheCurrentContractStillResumesWithoutLosingItsFirstThousandGtins()throws Exception{
        String source="National Catalog@fixture";var cursor=new CatalogScanCheckpoint(shop.getId(),source);cursor.load();var first=java.util.stream.IntStream.rangeClosed(1,1000).mapToObj(NationalCatalogListWindowTest::gtin).toList();cursor.save(new CatalogScanCheckpoint.State(1,1001,false,first,0,List.of(),List.of(),1000));var pages=new ArrayList<Integer>();
        var reader=new NationalCatalogGtinSource(shop.getId(),page->{pages.add(page);assertEquals(1,page);return page(1001,1,1001);},NationalCatalogListWindowTest::card,source).catalogListWindow().resumable();var result=reader.read(shop.getId());assertEquals(1001,result.size());assertEquals(List.of(1),pages);assertEquals(first.getFirst(),result.getFirst().gtin());assertEquals(gtin(1001),result.getLast().gtin());assertNull(cursor.load());
    }
    @Test void theSeparateLegacyReaderDoesNotInheritAnUnverifiedCatalogKeyWindowLimit()throws Exception{
        var seen=new ArrayList<Integer>();var source=new NationalCatalogGtinSource(shop.getId(),page->{seen.add(page);return page==0?page(1,10_000,10_001):page(10_001,1,10_001);},NationalCatalogListWindowTest::card);
        assertEquals(10_001,source.read(shop.getId()).size());assertEquals(List.of(0,1),seen);
    }
    @Test void offlineAndServerFailuresKeepTheLastCompletedListPage()throws Exception{
        for(Exception failure:List.of(new java.net.UnknownHostException("fixture"),new java.net.ConnectException("fixture"),new com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException("fixture",503,"{}"))){
            String scope="National Catalog@"+failure.getClass().getSimpleName();
            var first=new NationalCatalogGtinSource(shop.getId(),p->{if(p==1)throw failure;return page(1,1,2);},NationalCatalogListWindowTest::card,scope).catalogListWindow().resumable();
            assertSame(failure,assertThrows(Exception.class,()->first.read(shop.getId())));
            var pages=new ArrayList<Integer>();var resumed=new NationalCatalogGtinSource(shop.getId(),p->{pages.add(p);return page(2,1,2);},NationalCatalogListWindowTest::card,scope).catalogListWindow().resumable();
            assertEquals(2,resumed.read(shop.getId()).size());assertEquals(List.of(1),pages);
        }
    }
    @Test void registrationLookupRequiresACompleteKnownTotalEvenWhenUsingTheSeparateLegacyReader()throws Exception{
        for(JsonElement response:List.of(new JsonArray(),JsonParser.parseString("{\"results\":[]}"))){
            var strict=new NationalCatalogGtinSource(shop.getId(),p->response,g->{fail("Unknown completeness cannot authorise registration");return null;}).requireCompleteTotal();assertThrows(IOException.class,()->strict.read(shop.getId()));
            assertTrue(new NationalCatalogGtinSource(shop.getId(),p->response,NationalCatalogListWindowTest::card).read(shop.getId()).isEmpty(),"Keep the ordinary legacy UI reader's existing behavior separate");
        }
        assertTrue(new NationalCatalogGtinSource(shop.getId(),p->page(1,0,0),NationalCatalogListWindowTest::card).requireCompleteTotal().read(shop.getId()).isEmpty());
    }
    private static JsonElement page(int start,int count,int total){var response=new JsonObject();var rows=new JsonArray();for(int n=0;n<count;n++){var row=new JsonObject();row.addProperty("gtin",gtin(start+n));rows.add(row);}response.add("results",rows);response.addProperty("total",total);return response;}
    private static JsonElement card(String gtin){var card=new JsonObject();card.addProperty("gtin",gtin);card.addProperty("good_id",Long.parseLong(gtin));card.addProperty("good_status","draft");var response=new JsonObject();var rows=new JsonArray();rows.add(card);response.add("result",rows);return response;}
    private static String gtin(int value){String base=String.format(Locale.ROOT,"%013d",value);int sum=0,weight=3;for(int n=base.length()-1;n>=0;n--){sum+=(base.charAt(n)-'0')*weight;weight=4-weight;}return base+(10-sum%10)%10;}
}
