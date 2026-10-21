package com.vncode.app.integration.znack;

import com.google.gson.JsonParseException;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CatalogCountryDiagnosticsTest {
    private static final String KEY="synthetic-country-api-key-42";
    private static final String VALID="{\"apiversion\":3,\"result\":{\"_etag\":\"fixture\",\"_list\":[{\"country_iso\":\"RU\",\"country_name\":\"РОССИЯ\"}]}}";

    @Test void countryReadsUseTheEstablishedEndpointAndAuthForEachMode() throws Exception {
        var seen=new ArrayList<Request>();
        var transport=new OkHttpClient.Builder().addInterceptor(chain->{seen.add(chain.request());return response(chain.request(),200,VALID,"application/json");}).build();
        var bearer=new ZnackApiClient(transport);assertEquals(3,bearer.nationalCatalogCountries(ZnackModels.PRODUCTION_TRUE_API,"synthetic-bearer").getAsJsonObject().get("apiversion").getAsInt());
        var catalog=ZnackApiClient.forCatalogKey(transport,KEY,()->{});catalog.nationalCatalogCountries(ZnackModels.PRODUCTION_TRUE_API,"unused");
        assertEquals("/api/v3/true-api/nk/dictionary/isocountry",seen.get(0).url().encodedPath());
        assertEquals("Bearer synthetic-bearer",seen.get(0).header("Authorization"));assertNull(seen.get(0).url().queryParameter("apikey"));
        assertEquals("/v3/dictionary/isocountry",seen.get(1).url().encodedPath());assertNull(seen.get(1).header("Authorization"));assertEquals(KEY,seen.get(1).url().queryParameter("apikey"));
        assertTrue(seen.stream().allMatch(request->request.method().equals("GET")),"Reading diagnostic data never writes a card");
    }

    @Test void authFailuresKeepOperationEnvironmentAndStatusWithoutCredentialUrls() throws Exception {
        for(int status:new int[]{401,403}){
            var transport=new OkHttpClient.Builder().addInterceptor(chain->response(chain.request(),status,"{\"error_message\":\"denied\",\"token\":\"private-token\"}","application/json")).build();
            var api=ZnackApiClient.forCatalogKey(transport,KEY,()->{});
            var failure=assertThrows(ZnackApiClient.ZnackApiException.class,()->api.nationalCatalogCountries(ZnackModels.PRODUCTION_TRUE_API,"unused"));
            assertEquals(status,failure.statusCode());assertEquals("COUNTRY_DICTIONARY",failure.operation());assertEquals("production",failure.environment());assertEquals("CATALOG_KEY",failure.authentication());assertEquals("",failure.requestId());
            assertEquals("application/json",failure.contentType());assertFalse(failure.url().contains("?"));
            String report=ZnackErrorDetails.format(failure);assertTrue(report.contains("HTTP status: "+status));assertTrue(report.contains("Operation: COUNTRY_DICTIONARY"));
            assertFalse(report.contains(KEY));assertFalse(report.contains("private-token"));
        }
    }

    @Test void lookalikeHostsAndSandboxPathsRemainCustomDiagnosticEnvironments(){
        var transport=new OkHttpClient.Builder().addInterceptor(chain->response(chain.request(),403,"{}","application/json")).build();
        var api=new ZnackApiClient(transport);
        for(String base:java.util.List.of("https://markirovka.crpt.ru.example.test/api/v3/true-api",
                "https://fixture.invalid/sandbox","https://api.nk.sandbox.crptech.ru.example.test")){
            var failure=assertThrows(ZnackApiClient.ZnackApiException.class,()->api.nationalCatalogCountries(base,"synthetic-bearer"));
            assertEquals("custom",failure.environment());
        }
        assertEquals("https://fixture.invalid/api/v3/true-api/nk/dictionary/isocountry",api.nationalCatalogCountrySource("https://user:password@fixture.invalid"));
    }

    @Test void overBudgetRateLimitKeepsServerDelayWithoutWaitingOrRepeating() {
        var calls=new AtomicInteger();var slept=new AtomicInteger();
        var transport=new OkHttpClient.Builder().addInterceptor(chain->{calls.incrementAndGet();return response(chain.request(),429,"{}","application/json").newBuilder().header("Retry-After","301").build();}).build();
        var api=new ZnackApiClient(transport,millis->slept.incrementAndGet());
        var failure=assertThrows(ZnackApiClient.ZnackApiException.class,()->api.nationalCatalogCountries(ZnackModels.PRODUCTION_TRUE_API,"synthetic-bearer"));
        assertEquals(Duration.ofSeconds(301),failure.retryAfter());assertEquals(1,calls.get());assertEquals(0,slept.get());assertEquals("COUNTRY_DICTIONARY",failure.operation());
    }

    @Test void timeoutPreservesTheOriginalCauseAndAddsCountryOperationContext() {
        var timeout=new SocketTimeoutException("https://private.invalid/countries?apikey=private-timeout-key");
        var transport=new OkHttpClient.Builder().addInterceptor(chain->{throw timeout;}).build();
        var failure=assertThrows(IOException.class,()->new ZnackApiClient(transport).nationalCatalogCountries(ZnackModels.PRODUCTION_TRUE_API,"synthetic-bearer"));
        assertSame(timeout,failure.getCause());assertTrue(failure.getMessage().contains("operation=COUNTRY_DICTIONARY"));
        String report=ZnackErrorDetails.format(failure);assertTrue(report.contains("SocketTimeoutException"));assertFalse(report.contains("private-timeout-key"));
    }

    @Test void htmlSuccessIsNotPassedToTheCountrySchemaAsValidJson() {
        var transport=new OkHttpClient.Builder().addInterceptor(chain->response(chain.request(),200,"<html>login token=private-html-token</html>","text/html")).build();
        var failure=assertThrows(ZnackApiClient.ZnackApiException.class,()->new ZnackApiClient(transport).nationalCatalogCountries(ZnackModels.PRODUCTION_TRUE_API,"synthetic-bearer"));
        assertEquals(200,failure.statusCode());assertEquals("HTML",failure.responseKind());assertEquals("COUNTRY_DICTIONARY",failure.operation());assertInstanceOf(JsonParseException.class,failure.getCause());
        assertFalse(ZnackErrorDetails.format(failure).contains("private-html-token"));
    }

    @Test void malformedJsonKeepsTheParserCauseAndHttpMetadata() {
        var transport=new OkHttpClient.Builder().addInterceptor(chain->response(chain.request(),200,"{\"result\": [}","application/json")).build();
        var failure=assertThrows(ZnackApiClient.ZnackApiException.class,()->new ZnackApiClient(transport).nationalCatalogCountries(ZnackModels.PRODUCTION_TRUE_API,"synthetic-bearer"));
        assertEquals(200,failure.statusCode());assertEquals("INVALID_JSON",failure.responseKind());assertInstanceOf(JsonParseException.class,failure.getCause());assertTrue(failure.getMessage().contains("CATALOG_COUNTRY_RESPONSE_INVALID_JSON"));
    }

    @Test void emptyBodyKeepsHttp200AndItsOriginalEndOfInputCause(){
        var transport=new OkHttpClient.Builder().addInterceptor(chain->response(chain.request(),200,"","application/json")).build();
        var failure=assertThrows(ZnackApiClient.ZnackApiException.class,()->new ZnackApiClient(transport).nationalCatalogCountries(ZnackModels.PRODUCTION_TRUE_API,"synthetic-bearer"));
        assertEquals(200,failure.statusCode());assertEquals("EMPTY",failure.responseKind());assertInstanceOf(java.io.EOFException.class,failure.getCause());assertEquals("COUNTRY_DICTIONARY",failure.operation());
    }

    @Test void cachedScopeChecksRemainCredentialBoundWithoutIssuingHttp() throws Exception {
        var calls=new AtomicInteger();var changed=new AtomicBoolean();
        var transport=new OkHttpClient.Builder().addInterceptor(chain->{calls.incrementAndGet();return response(chain.request(),200,VALID,"application/json");}).build();
        var api=ZnackApiClient.forCatalogKey(transport,KEY,()->{if(changed.get())throw new IllegalStateException("CATALOG_KEY_SCOPE_CHANGED");});
        api.assertCatalogReadScope();assertEquals(0,calls.get());
        changed.set(true);assertThrows(IllegalStateException.class,api::assertCatalogReadScope);assertEquals(0,calls.get());
        assertNotEquals(api.catalogReadScopeId(),new ZnackApiClient().catalogReadScopeId());assertFalse(api.catalogReadScopeId().contains(KEY));
    }

    @Test void legacyHttpConstructorsStripUserInfoAndEveryUrlQuery() {
        var failure=new ZnackApiClient.ZnackApiException("fixture",422,"{}","POST","https://user:password@example.test/v3/feed?apikey=private-api&opaque=private-query#fragment");
        assertEquals("https://example.test/v3/feed",failure.url());assertFalse(failure.diagnosticDetails().contains("password"));assertFalse(failure.diagnosticDetails().contains("private-query"));
    }

    private static Response response(Request request,int status,String body,String contentType){
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .header("Content-Type",contentType).body(ResponseBody.create(body,MediaType.get(contentType))).build();
    }
}
