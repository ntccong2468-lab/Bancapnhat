package com.vncode.app.integration.znack;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackCatalogEndpointRoutingTest {
    @Test void canonicalOfficialEnvironmentsKeepTheirEstablishedCatalogRoutes(){
        for(String base:List.of(ZnackModels.PRODUCTION_TRUE_API,ZnackModels.PRODUCTION_NATIONAL_CATALOG,"https://MARKIROVKA.CRPT.RU:443/api/v4/true-api","  https://markirovka.crpt.ru  "))assertEquals(ZnackModels.PRODUCTION_NATIONAL_CATALOG,ZnackApiClient.nationalCatalogBase(base));
        for(String base:List.of("https://markirovka.sandbox.crptech.ru/api/v3/true-api",ZnackModels.SANDBOX_NATIONAL_CATALOG))assertEquals(ZnackModels.SANDBOX_NATIONAL_CATALOG,ZnackApiClient.nationalCatalogBase(base));
        assertEquals(ZnackModels.PRODUCTION_NATIONAL_CATALOG,ZnackApiClient.nationalCatalogBase(null));assertEquals(ZnackModels.PRODUCTION_NATIONAL_CATALOG,ZnackApiClient.nationalCatalogBase(" "));
    }
    @Test void lookalikeHostnamesAndSandboxPathsNeverSwitchTheConfiguredOrigin(){
        for(String base:List.of("https://markirovka.crpt.ru.example.test/api/v3/true-api","https://api.nk.sandbox.crptech.ru.example.test","https://fixture.invalid/sandbox","http://127.0.0.1:12345/api/v3/true-api","https://sandbox.fixture.invalid/api/v3/true-api"))assertEquals(ZnackApiClient.apiRoot(base),ZnackApiClient.nationalCatalogBase(base));
    }
    @Test void unsupportedOfficialOriginVariantsFailBeforeDispatchInsteadOfChangingOrigin(){
        for(String base:List.of("http://markirovka.crpt.ru/api/v3/true-api","https://markirovka.crpt.ru:8443/api/v3/true-api","https://user:password@markirovka.crpt.ru","invalid base")){
            var failure=assertThrows(IllegalArgumentException.class,()->ZnackApiClient.nationalCatalogBase(base));assertEquals("CATALOG_ENDPOINT_INVALID",failure.getMessage());assertFalse(failure.getMessage().contains("password"));
        }
    }
    @Test void customCatalogReadKeepsTargetAndNeverReceivesTheOfficialCatalogKey()throws Exception {
        var seen=new ArrayList<Request>();var transport=new OkHttpClient.Builder().addInterceptor(chain->{seen.add(chain.request());return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(ResponseBody.create("{\"result\":[]}",MediaType.get("application/json"))).build();}).build();
        var api=ZnackApiClient.forCatalogKey(transport,"synthetic-routing-key-42",()->fail("Custom origin cannot obtain official Catalog key scope"));
        api.productCards("https://fixture.invalid/sandbox","unused","04631993764370");
        assertEquals(1,seen.size());assertEquals("fixture.invalid",seen.getFirst().url().host());assertEquals("/sandbox/v3/feed-product",seen.getFirst().url().encodedPath());assertNull(seen.getFirst().url().queryParameter("apikey"));assertNull(seen.getFirst().header("Authorization"));
    }
    @Test void catalogListRangeIsExplicitAndNeverClampedOrDispatchedBeyondItsDocumentedWindow()throws Exception {
        var seen=new ArrayList<Request>();var transport=new OkHttpClient.Builder().addInterceptor(chain->{seen.add(chain.request());return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(ResponseBody.create("{\"result\":{\"goods\":[],\"total\":0}}",MediaType.get("application/json"))).build();}).build();
        var api=ZnackApiClient.forCatalogKey(transport,"synthetic-routing-key-42",()->{});
        for(var range:List.of(new int[]{0,1001},new int[]{10,1000},new int[]{-1,1000},new int[]{0,0},new int[]{Integer.MAX_VALUE,1000})){
            var failure=assertThrows(IllegalArgumentException.class,()->api.products(ZnackModels.PRODUCTION_TRUE_API,"unused",range[0],range[1]));assertEquals("CATALOG_LIST_RANGE_UNSUPPORTED",failure.getMessage());assertTrue(seen.isEmpty());
        }
        api.products(ZnackModels.PRODUCTION_TRUE_API,"unused",9,1000);assertEquals("9000",seen.getFirst().url().queryParameter("offset"));assertEquals("1000",seen.getFirst().url().queryParameter("limit"));
        api.products(ZnackModels.PRODUCTION_TRUE_API,"unused");assertEquals("0",seen.getLast().url().queryParameter("offset"));assertEquals("1000",seen.getLast().url().queryParameter("limit"));
        new ZnackApiClient(transport).products("http://127.0.0.1:12345","synthetic-bearer",0,10000);assertEquals("10000",seen.getLast().url().queryParameter("limit"));
    }
    @Test void versionIndexAndGoodIdRequestsUseTheirOwnOfficialRoutesAndBounds()throws Exception{
        var seen=new ArrayList<Request>();var transport=new OkHttpClient.Builder().addInterceptor(chain->{seen.add(chain.request());return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(ResponseBody.create("{\"result\":[]}",MediaType.get("application/json"))).build();}).build();
        var api=ZnackApiClient.forCatalogKey(transport,"synthetic-routing-key-42",()->{});
        api.catalogVersions(ZnackModels.PRODUCTION_TRUE_API,"unused","1234567890",10000);
        assertEquals("/v3/etagslist",seen.getLast().url().encodedPath());assertEquals("10000",seen.getLast().url().queryParameter("offset"));assertEquals("1234567890",seen.getLast().url().queryParameter("owner_inn"));assertNull(seen.getLast().header("Authorization"));
        api.productCardsByIds(ZnackModels.PRODUCTION_TRUE_API,"unused",List.of("1","2"));assertEquals("/v3/feed-product",seen.getLast().url().encodedPath());assertEquals("1;2",seen.getLast().url().queryParameter("good_ids"));assertNull(seen.getLast().url().queryParameter("gtins"));
        int count=seen.size();assertThrows(IllegalArgumentException.class,()->api.productCardsByIds(ZnackModels.PRODUCTION_TRUE_API,"unused",java.util.stream.IntStream.rangeClosed(1,26).mapToObj(Integer::toString).toList()));assertThrows(IllegalArgumentException.class,()->api.productCardsByIds(ZnackModels.PRODUCTION_TRUE_API,"unused",List.of("1","1")));assertThrows(IllegalArgumentException.class,()->api.catalogVersions(ZnackModels.PRODUCTION_TRUE_API,"unused","invalid",0));assertEquals(count,seen.size());
    }
}
