package com.vncode.app.integration.ozon;

import com.vncode.app.shared.RateLimitDeferral;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class OzonSharedCredentialCooldownTest {
    @Test void sameCredentialsAcrossShopIdsShareCooldownButAnotherCredentialCanProgress()throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","120"));
            server.enqueue(new MockResponse().setBody("{\"result\":{\"items\":[]}}"));
            server.start();var limiter=new OzonApiRateLimiter(Duration.ZERO);var http=new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).build();
            var policy=new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1));
            var credentials=new OzonCredentials("synthetic-client","synthetic-key");
            var first=new OzonApiClient(1,credentials,server.url("/"),http,limiter,policy);
            var duplicate=new OzonApiClient(2,credentials,server.url("/"),http,limiter,policy);
            var independent=new OzonApiClient(3,new OzonCredentials("other-client","other-key"),server.url("/"),http,limiter,policy);
            try(var reads=RateLimitDeferral.enter()){
                assertEquals(429,assertThrows(OzonApiException.class,()->first.listProducts("",10)).statusCode());
                var deferred=assertThrows(RateLimitDeferral.Deferred.class,()->duplicate.listProducts("",10));
                assertTrue(deferred.delay().compareTo(Duration.ofSeconds(1))>0);assertEquals(1,server.getRequestCount());
                independent.listProducts("",10);assertEquals(2,server.getRequestCount());
            }
        }
    }
}
