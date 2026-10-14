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
            seen.add(chain.request()); return response(chain.request(), 200, "{}");
        }).build();
        var api = ZnackApiClient.forCatalogKey(transport, KEY, checks::incrementAndGet);
        api.products(BASE, "true-api-token");
        api.productCards(BASE, "true-api-token", "00000000000017;00000000000024");
        api.generatedGtins(BASE, "true-api-token");
        api.nationalCatalogCategories(BASE, "true-api-token", "6103");
        api.nationalCatalogAttributes(BASE, "true-api-token", 42);
        api.submitNationalCatalogFeed(BASE, "true-api-token", new JsonObject());
        assertNull(seen.getFirst().url().queryParameter("apikey"));
        assertEquals("Bearer true-api-token", seen.getFirst().header("Authorization"));
        assertEquals("/api/v4/true-api/product/gtin", seen.getFirst().url().encodedPath());
        assertEquals("00000000000017;00000000000024", seen.get(1).url().queryParameter("gtins"));
        for (Request request : seen.subList(1, seen.size())) {
            assertEquals(HttpUrl.get(ZnackModels.PRODUCTION_NATIONAL_CATALOG).host(), request.url().host());
            assertEquals(KEY, request.url().queryParameter("apikey"));
            assertNull(request.header("Authorization"));
        }
        assertEquals(5, checks.get());
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
