package com.vncode.app.integration.wb;
import com.vncode.app.models.Shop;
import com.vncode.app.features.news.NewsScope;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WbNewsClientTest {
 @Test void readsInclusiveCursorWithScopedAuthorizationAndPlainText()throws Exception{try(var server=new MockWebServer()){server.start();server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":42,\"date\":\"2026-10-11T00:00:00Z\",\"header\":\"Official title\",\"content\":\"<p>GTIN &amp; size</p>\"}]}"));var client=new WbNewsClient(new OkHttpClient(),server.url("/").uri());var shop=new Shop(1,"Fixture","fixture-token");var result=client.readSince(shop,Instant.parse("2026-10-01T00:00:00Z"),"41");assertEquals("GTIN & size",result.items().getFirst().originalBody());assertEquals(NewsScope.forShop(shop),result.items().getFirst().scope());var request=server.takeRequest(2,TimeUnit.SECONDS);assertEquals("fixture-token",request.getHeader("Authorization"));assertTrue(request.getPath().contains("fromID=41"));}}
 @Test void rateLimitIsBoundedAndSecretsDoNotAppearInErrors()throws Exception{try(var server=new MockWebServer()){server.start();server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","120").setBody("secret-token"));var client=new WbNewsClient(new OkHttpClient(),server.url("/").uri());var error=assertThrows(WbNewsClient.RateLimitedException.class,()->client.readSince(new Shop(1,"Fixture","secret-token"),Instant.EPOCH,""));assertFalse(error.toString().contains("secret-token"));assertEquals(120,error.retryAfter().toSeconds());assertEquals(1,server.getRequestCount());}}
}
