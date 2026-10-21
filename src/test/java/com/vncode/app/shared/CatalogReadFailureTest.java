package com.vncode.app.shared;

import com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CatalogReadFailureTest {
    @TempDir java.nio.file.Path directory;
    private String previousDirectory;
    @BeforeEach void isolateData() throws Exception {
        previousDirectory=System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir",directory.toString());
        com.vncode.app.config.Database.initDatabase();
    }
    @AfterEach void restoreData(){
        if(previousDirectory==null)System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir",previousDirectory);
    }
    @Test void countryContextDoesNotHideHttpAuthRateLimitOrServerCauses(){
        for(var fixture:List.of(new HttpCase(401,"CATALOG_COUNTRY_AUTH_REQUIRED"),new HttpCase(403,"CATALOG_COUNTRY_AUTH_REQUIRED"),
                new HttpCase(404,"CATALOG_COUNTRY_ENDPOINT_UNAVAILABLE"),new HttpCase(429,"CATALOG_COUNTRY_RATE_LIMITED"),
                new HttpCase(503,"CATALOG_COUNTRY_SERVICE_UNAVAILABLE"))){
            var api=http(fixture.status(),"HTTP",fixture.status()==429?Duration.ofSeconds(301):null);
            var error=new IllegalStateException("CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE",api);
            var classified=CatalogReadFailure.country(error).orElseThrow();assertEquals(fixture.code(),classified.code());
            assertEquals(api.retryAfter(),classified.retryAfter());assertSame(api,error.getCause());
        }
    }

    @Test void htmlAndInvalidJsonWithHttp200AreResponseFailuresRatherThanCardResults(){
        assertEquals("CATALOG_COUNTRY_RESPONSE_EMPTY",CatalogReadFailure.country(http(200,"EMPTY",null)).orElseThrow().code());
        assertEquals("CATALOG_COUNTRY_RESPONSE_HTML",CatalogReadFailure.country(http(200,"HTML",null)).orElseThrow().code());
        assertEquals("CATALOG_COUNTRY_RESPONSE_INVALID_JSON",CatalogReadFailure.country(http(200,"INVALID_JSON",null)).orElseThrow().code());
    }

    @Test void wrappedNetworkReasonsRemainDistinct(){
        for(var fixture:List.of(new NetworkCase(new java.net.SocketTimeoutException("private-timeout"),"CATALOG_COUNTRY_TIMEOUT"),
                new NetworkCase(new java.net.UnknownHostException("private-host"),"CATALOG_COUNTRY_DNS"),
                new NetworkCase(new javax.net.ssl.SSLHandshakeException("private-certificate"),"CATALOG_COUNTRY_TLS"),
                new NetworkCase(new java.net.ConnectException("private-host"),"CATALOG_COUNTRY_CONNECTION_FAILED"))){
            var error=new IOException("CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE; operation=COUNTRY_DICTIONARY",fixture.failure());
            assertEquals(fixture.code(),CatalogReadFailure.country(error).orElseThrow().code());
            assertSame(fixture.failure(),error.getCause());
        }
    }

    @Test void genericWrapperAroundSchemaValidationIsNotInventedAsNetworkFailure(){
        var error=new IOException("private outer diagnostic",new IllegalStateException("CATALOG_COUNTRY_DICTIONARY_INVALID",new IllegalArgumentException("SCHEMA_INVALID")));
        assertTrue(CatalogReadFailure.country(error).isEmpty());
        assertTrue(FriendlyErrorService.format(error).endsWith("[CATALOG_COUNTRY_DICTIONARY_INVALID]"));
    }

    @Test void unrelatedProduct404AndTimeoutKeepTheirExistingMeaning(){
        assertTrue(CatalogReadFailure.country(new ZnackApiException("card lookup",404,"{}","GET","https://fixture.invalid/product")).isEmpty());
        assertTrue(CatalogReadFailure.country(new java.net.SocketTimeoutException("ordinary marketplace request")).isEmpty());
        assertTrue(CatalogReadFailure.country((Throwable)null).isEmpty());
    }

    @Test void localizedUiKeepsCountryContextAndNeverSuggestsBadOrMissingGtin(){
        var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
        try{for(var language:AppLanguage.values()){
            i18n.setLanguage(language);
            for(var fixture:List.of(new HttpCase(401,"CATALOG_COUNTRY_AUTH_REQUIRED"),new HttpCase(403,"CATALOG_COUNTRY_AUTH_REQUIRED"),
                    new HttpCase(404,"CATALOG_COUNTRY_ENDPOINT_UNAVAILABLE"),new HttpCase(429,"CATALOG_COUNTRY_RATE_LIMITED"),new HttpCase(503,"CATALOG_COUNTRY_SERVICE_UNAVAILABLE"))){
                String key="znack.registration.reason."+fixture.code(),expected=i18n.tr(key);assertNotEquals(key,expected);
                String text=FriendlyErrorService.format(new IOException("private-wrapper token=private-token",http(fixture.status(),"HTTP",null)));
                assertEquals(expected+"\n["+fixture.code()+"]",text);
                assertFalse(text.contains("private-token"));assertFalse(text.contains("GTIN_NOT_FOUND"));assertFalse(text.contains("INVALID_GTIN"));
            }
        }}finally{i18n.setLanguage(previous);}
    }

    @Test void localizedRateLimitGuidancePreservesFullRetryAfter(){
        var classified=CatalogReadFailure.country(http(429,"HTTP",Duration.ofSeconds(301))).orElseThrow();
        assertTrue(classified.message().contains("301"));assertFalse(classified.message().contains("znack.registration.country.retry_after"));
    }

    @Test void storedControlledOperationMarkersProduceTheSameFriendlyCause(){
        assertEquals("CATALOG_COUNTRY_AUTH_REQUIRED",CatalogReadFailure.country("ZnackApiException: CATALOG_COUNTRY_HTTP_403; rejected (HTTP 403)").orElseThrow().code());
        assertEquals("CATALOG_COUNTRY_RESPONSE_HTML",CatalogReadFailure.country("ZnackApiException: CATALOG_COUNTRY_RESPONSE_HTML; HTTP 200").orElseThrow().code());
        assertEquals("CATALOG_COUNTRY_TIMEOUT",CatalogReadFailure.country("CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE <- SocketTimeoutException: timeout").orElseThrow().code());
        assertTrue(CatalogReadFailure.country("card returned HTTP 404").isEmpty());
    }

    private static ZnackApiException http(int status,String kind,Duration retry){
        return new ZnackApiException("CATALOG_COUNTRY_HTTP_"+status,status,"{\"token\":\"private-token\"}","GET",
                "https://fixture.invalid/v3/dictionary/isocountry?apikey=private-api",retry,"COUNTRY_DICTIONARY","production","CATALOG_KEY","","application/json",kind);
    }
    private record HttpCase(int status,String code){}
    private record NetworkCase(Throwable failure,String code){}
}
