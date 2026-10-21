package com.vncode.app.integration.znack;

import com.google.gson.JsonObject;
import okhttp3.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogApiAuthorizationTest {
    private static final String KEY = "synthetic-Catalog-key-42";
    private static final String BASE = ZnackModels.PRODUCTION_TRUE_API;
    private static Response response(Request request, int status, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
    @Test void catalogKeyIsOnlySentToCatalogAndTrueApiStillUsesBearer() throws Exception {
        var seen = new ArrayList<Request>(); var checks = new AtomicInteger();
        var transport = new OkHttpClient.Builder().addInterceptor(chain -> {
            seen.add(chain.request()); return response(chain.request(), 200, "{\"result\":{\"goods\":[],\"total\":0}}");
        }).build();
        var api = ZnackApiClient.forCatalogKey(transport, KEY, checks::incrementAndGet);
        api.productInfo(BASE,"true-api-token","00000000000017");
        api.products(BASE, "true-api-token");
        api.productCards(BASE, "true-api-token", "00000000000017;00000000000024");
        api.generatedGtins(BASE, "true-api-token");
        api.nationalCatalogCategories(BASE, "true-api-token", "6103");
        api.nationalCatalogAttributes(BASE, "true-api-token", 42);
        api.submitNationalCatalogFeed(BASE, "true-api-token", new JsonObject());
        assertNull(seen.getFirst().url().queryParameter("apikey"));
        assertEquals("Bearer true-api-token", seen.getFirst().header("Authorization"));
        assertEquals("/api/v4/true-api/product/info", seen.getFirst().url().encodedPath());
        assertEquals("/v4/product-list",seen.get(1).url().encodedPath());
        assertEquals("00000000000017;00000000000024", seen.get(2).url().queryParameter("gtins"));
        for (Request request : seen.subList(1, seen.size())) {
            assertEquals(HttpUrl.get(ZnackModels.PRODUCTION_NATIONAL_CATALOG).host(), request.url().host());
            assertEquals(KEY, request.url().queryParameter("apikey"));
            assertNull(request.header("Authorization"));
        }
        assertEquals(6, checks.get());
    }
    @Test void catalogProductListIsNotRestrictedToTheTrueApiLpGroupAndPaginatesByOffset()throws Exception{
        var seen=new ArrayList<Request>();
        var transport=new OkHttpClient.Builder().addInterceptor(chain->{seen.add(chain.request());return response(chain.request(),200,"{\"result\":{\"goods\":[{\"gtin\":\"04645588781154\"}],\"total\":2300}}");}).build();
        var api=ZnackApiClient.forCatalogKey(transport,KEY,()->{});
        var response=api.products(BASE,null,2,1_000).getAsJsonObject();
        assertEquals(2300,response.get("total").getAsInt());assertEquals(1,response.getAsJsonArray("results").size());
        var request=seen.getFirst();assertEquals("1000",request.url().queryParameter("limit"));assertEquals("2000",request.url().queryParameter("offset"));assertNull(request.url().queryParameter("pg"));assertNull(request.header("Authorization"));assertEquals(KEY,request.url().queryParameter("apikey"));
        var unsupported=assertThrows(IllegalArgumentException.class,()->api.products(BASE,null,2,10_000));assertEquals("CATALOG_LIST_RANGE_UNSUPPORTED",unsupported.getMessage());assertEquals(1,seen.size(),"An unsupported range is rejected locally without another authenticated request");
    }
    @Test void catalogListAlwaysIncludesOneStableDateIntervalAcrossConnectionCheckAndAllPages()throws Exception {
        var seen=new ArrayList<Request>();
        var transport=new OkHttpClient.Builder().addInterceptor(chain->{
            var request=chain.request();seen.add(request);
            boolean interval=request.url().queryParameter("from_date")!=null&&request.url().queryParameter("to_date")!=null;
            // Real API observation: missing interval returns a valid, misleading empty result.
            return response(request,200,interval?"{\"result\":{\"goods\":[{\"gtin\":\"04645588781154\"}],\"total\":1961}}":"{\"result\":{\"goods\":[],\"total\":0}}");
        }).build();
        var api=ZnackApiClient.forCatalogKey(transport,KEY,()->{});api.checkCatalogConnection(BASE);
        for(int page=0;page<2;page++)assertEquals(1961,api.products(BASE,null,page,1000).getAsJsonObject().get("total").getAsInt());
        String upper=seen.getFirst().url().queryParameter("to_date");
        assertNotNull(upper);assertDoesNotThrow(()->java.time.LocalDateTime.parse(upper,java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")));
        for(var request:seen){assertEquals("2000-01-01 00:00:00",request.url().queryParameter("from_date"));assertEquals(upper,request.url().queryParameter("to_date"));}
        assertEquals("1000",seen.get(2).url().queryParameter("offset"));assertEquals(3,seen.size());
    }
    @Test void changingScopeStopsRequestsAndConnectionCheckIsReadOnly() throws Exception {
        var seen = new ArrayList<Request>();
        var transport = new OkHttpClient.Builder().addInterceptor(chain -> {
            seen.add(chain.request()); return response(chain.request(), 200, "{\"result\":{\"goods\":[],\"total\":0}}");
        }).build();
        ZnackApiClient.forCatalogKey(transport, KEY, () -> {}).checkCatalogConnection(BASE);
        assertEquals("GET", seen.getFirst().method()); assertEquals("/v4/product-list", seen.getFirst().url().encodedPath());
        assertEquals("1", seen.getFirst().url().queryParameter("limit"));
        var guarded = ZnackApiClient.forCatalogKey(transport, KEY, () -> { throw new IllegalStateException("CATALOG_KEY_SCOPE_CHANGED"); });
        assertThrows(IllegalStateException.class, () -> guarded.generateGtins(BASE, "unused", 1));
        assertEquals(1, seen.size());
    }
    @Test void keyNeverAppearsInServerFailureOrInvalidJsonDiagnostics() throws Exception {
        for (int status : new int[]{403, 200}) {
            var transport = new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(), status, KEY)).build();
            var api = ZnackApiClient.forCatalogKey(transport, KEY, () -> {});
            var failure = assertThrows(IOException.class, () -> api.checkCatalogConnection(BASE));
            assertFalse(failure.toString().contains(KEY));
            assertNull(failure.getCause());
            if (failure instanceof ZnackApiClient.ZnackApiException detail) assertFalse(detail.diagnosticDetails().contains(KEY));
        }
        assertFalse(ZnackSanitizer.diagnostic("https://example.test/?apikey="+KEY+"&gtin=123").contains(KEY));
        assertTrue(ZnackSanitizer.diagnostic("apikey="+KEY+"&gtin=123").contains("gtin=123"));
    }
    @Test void noRedirectOrMutationReplayWhenUsingApiKey() throws Exception {
        var calls = new AtomicInteger();
        var transport = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet(); return response(chain.request(), 302, "{}").newBuilder().header("Location", "https://evil.example/").build();
        }).build();
        var api = ZnackApiClient.forCatalogKey(transport, KEY, () -> {});
        assertThrows(IOException.class, () -> api.generateGtins(BASE, "unused", 1));
        assertEquals(1, calls.get());
    }
}
