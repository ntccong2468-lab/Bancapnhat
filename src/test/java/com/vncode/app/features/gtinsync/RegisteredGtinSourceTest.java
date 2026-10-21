package com.vncode.app.features.gtinsync;

import com.google.gson.JsonParser;
import com.vncode.app.integration.znack.registration.NationalCatalogGtinSource;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredGtinSourceTest {
    @Test void paginatesAndBindsPackagingLevelToRequestedGtin() throws Exception {
        AtomicInteger pages=new AtomicInteger();
        var source=new NationalCatalogGtinSource(1,p -> {
            pages.incrementAndGet();
            return JsonParser.parseString(p==0?"""
                {"results":[{"gtin":"00000000000017"},{"gtin":"00000000000024"}],"total":2}
                """:"{\"results\":[]}");
        },gtin->JsonParser.parseString("""
            {"result":[{"good_status":"published","article":"PANTS","color":"Đen","size":"XL",
            "identified_by":[{"type":"gtin","value":"00000000000017","level":"trade-unit"},
            {"type":"gtin","value":"00000000000024","level":"box"}]}]}
            """));
        var rows=source.read(1);
        assertEquals(2,rows.size());assertTrue(rows.getFirst().tradeUnit());assertFalse(rows.getLast().tradeUnit());
        assertEquals("00000000000017",rows.getFirst().gtin());assertEquals(1,pages.get());
        assertThrows(IllegalArgumentException.class,()->source.read(2));
    }
    @Test void zeroPaddingDoesNotLoseThePublishedTradeUnitProof()throws Exception{
        var source=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{\"results\":[{\"gtin\":\"4645588781154\"}],\"total\":1}"),g->JsonParser.parseString("{\"result\":[{\"gtin\":\"04645588781154\",\"good_status\":\"published\",\"identified_by\":[{\"type\":\"gtin\",\"value\":\"04645588781154\",\"level\":\"trade-unit\"}]}]}"));
        var row=source.read(1).getFirst();assertEquals("04645588781154",row.gtin());assertTrue(row.tradeUnit());assertTrue(row.published());
    }
    @Test void rejectsTruncatedPagesAndUnknownResponses() {
        var truncated=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{\"results\":[],\"total\":4}"),g->null);
        assertThrows(IOException.class,()->truncated.read(1));
        var invalid=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{}"),g->null);
        assertThrows(IOException.class,()->invalid.read(1));
    }
    @Test void unknownPublicationAndLevelAreIneligible() throws Exception {
        var source=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{\"results\":[{\"gtin\":\"00000000000017\"}],\"total\":1}"),g->JsonParser.parseString("{\"result\":[{\"gtin\":\"00000000000017\"}]}"));
        var row=source.read(1).getFirst();assertFalse(row.published());assertFalse(row.tradeUnit());
    }
    @Test void appRegisteredCardOutsideListedGroupIsFreshlyReadAndNeverMergedFromCachedStatus()throws Exception {
        var reads=new AtomicInteger();
        var source=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{\"results\":[],\"total\":0}"),g->{reads.incrementAndGet();return JsonParser.parseString("{\"result\":[{\"gtin\":\"00000000000017\",\"good_status\":\"published\",\"identified_by\":[{\"type\":\"gtin\",\"value\":\"00000000000017\",\"level\":\"trade-unit\"}]}]}");},"National Catalog@fixture",()->java.util.List.of("00000000000017"));
        var rows=source.read(1);assertEquals(1,reads.get());assertEquals(1,rows.size());assertTrue(rows.getFirst().published());assertTrue(rows.getFirst().tradeUnit());
        var unpublished=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{\"results\":[],\"total\":0}"),g->JsonParser.parseString("{\"result\":[{\"gtin\":\"00000000000017\",\"good_status\":\"draft\"}]}"),"National Catalog@fixture",()->java.util.List.of("00000000000017"));
        assertFalse(unpublished.read(1).getFirst().published());assertFalse(unpublished.read(1).getFirst().tradeUnit());
    }
    @Test void cardReadTimeAndActualIdentityComeFromEachReceivedCardNotTheStartOfTheScan()throws Exception{
        var responseAt=new AtomicReference<java.time.Instant>();
        var source=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{\"results\":[{\"gtin\":\"00000000000017\"}],\"total\":1}"),g->{responseAt.set(java.time.Instant.now());return card(g,"000123","draft");});
        var row=source.read(1).getFirst();assertFalse(row.syncedAt().isBefore(responseAt.get()));assertEquals("000123",row.details().single("@catalog_id"));assertEquals("draft",row.details().single("@catalog_status"));assertEquals(java.util.List.of("moderation"),row.details().values("@catalog_detailed_status"));assertFalse(row.published());
    }
    @Test void aShortPageWithAKnownTotalStillReadsAllRemainingPagesBeforeReturningCards()throws Exception{
        var seenPages=new java.util.ArrayList<Integer>();var seenCards=new java.util.ArrayList<String>();var gtins=java.util.List.of("00000000000017","00000000000024","00000000000031");
        var source=new NationalCatalogGtinSource(1,p->{seenPages.add(p);return JsonParser.parseString("{\"results\":[{\"gtin\":\""+gtins.get(p)+"\"}],\"total\":3}");},g->{assertEquals(java.util.List.of(0,1,2),seenPages);seenCards.add(g);return card(g,"123","published");});
        assertEquals(3,source.read(1).size());assertEquals(java.util.List.of(0,1,2),seenPages);assertEquals(gtins,seenCards);
    }
    @Test void changingTotalsRepeatedRowsOrTransportFailuresNeverReturnAPartialVerifiedCatalog(){
        var cardReads=new AtomicInteger();
        for(String second:java.util.List.of("{\"results\":[{\"gtin\":\"00000000000024\"}],\"total\":3}","{\"results\":[{\"gtin\":\"00000000000017\"}],\"total\":2}")){
            var source=new NationalCatalogGtinSource(1,p->JsonParser.parseString(p==0?"{\"results\":[{\"gtin\":\"00000000000017\"}],\"total\":2}":second),g->{cardReads.incrementAndGet();return card(g,"123","published");});assertThrows(IOException.class,()->source.read(1));
        }
        var failure=new java.net.SocketTimeoutException("fixture list timeout");var source=new NationalCatalogGtinSource(1,p->{if(p==1)throw failure;return JsonParser.parseString("{\"results\":[{\"gtin\":\"00000000000017\"}],\"total\":2}");},g->{cardReads.incrementAndGet();return card(g,"123","published");});assertSame(failure,assertThrows(java.net.SocketTimeoutException.class,()->source.read(1)));assertEquals(0,cardReads.get());
    }
    @Test void authenticationFailureIsUnknownAndAFreshReadCanRecoverWithoutANegativeCache()throws Exception{
        var reads=new AtomicInteger();var failure=new com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException("fixture",401,"{}");
        var source=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{\"results\":[{\"gtin\":\"00000000000017\"}],\"total\":1}"),g->{if(reads.incrementAndGet()==1)throw failure;return card(g,"123","published");});
        assertSame(failure,assertThrows(com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException.class,()->source.read(1)));var recovered=source.read(1);assertEquals(1,recovered.size());assertTrue(recovered.getFirst().published());assertEquals(2,reads.get());
    }
    @Test void malformedMissingAndAmbiguousCardReadbackCannotMasqueradeAsAnEmptySuccessfulLookup(){
        for(String response:java.util.List.of("{}","{\"result\":[]}","{\"result\":[{\"gtin\":\"00000000000017\"},{\"gtin\":\"00000000000017\"}]}")){
            var source=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{\"results\":[{\"gtin\":\"00000000000017\"}],\"total\":1}"),g->JsonParser.parseString(response));assertThrows(IOException.class,()->source.read(1));
        }
    }
    private static com.google.gson.JsonElement card(String gtin,String id,String status){return JsonParser.parseString("{\"result\":[{\"good_id\":\""+id+"\",\"gtin\":\""+gtin+"\",\"good_status\":\""+status+"\",\"good_detailed_status\":[\"moderation\"],\"article\":\"Fixture\",\"color\":\"Brown\",\"size\":\"52\",\"identified_by\":[{\"type\":\"gtin\",\"value\":\""+gtin+"\",\"level\":\"trade-unit\"}]}]}");}
    @Test void explicitlyUnsignedPublishedCardCannotAuthorizeMarketplaceWritesAndMalformedFlagCannotRefreshTruth()throws Exception{
        for(String flag:java.util.List.of("true","false","null")){
            var source=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{\"results\":[{\"gtin\":\"00000000000017\"}],\"total\":1}"),g->{var response=card(g,"123","published").getAsJsonObject();response.getAsJsonArray("result").get(0).getAsJsonObject().add("good_signed",JsonParser.parseString(flag));return response;});
            var row=source.read(1).getFirst();assertEquals(!flag.equals("false"),row.published());assertEquals(flag.equals("null")?"":flag,row.details().single("@catalog_signed"));
        }
        var malformed=new NationalCatalogGtinSource(1,p->JsonParser.parseString("{\"results\":[{\"gtin\":\"00000000000017\"}],\"total\":1}"),g->{var response=card(g,"123","published").getAsJsonObject();response.getAsJsonArray("result").get(0).getAsJsonObject().addProperty("good_signed","false");return response;});
        assertEquals("CATALOG_SIGNATURE_STATUS_UNVERIFIED",assertThrows(IllegalArgumentException.class,()->malformed.read(1)).getMessage());
    }

}
