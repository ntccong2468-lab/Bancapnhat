package com.vncode.app.features.gtinsync;

import com.google.gson.JsonParser;
import com.vncode.app.integration.znack.registration.NationalCatalogGtinSource;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
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
}
