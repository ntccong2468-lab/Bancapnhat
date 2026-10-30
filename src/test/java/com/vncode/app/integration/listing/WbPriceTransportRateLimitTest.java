package com.vncode.app.integration.listing;

import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.net.InetAddress;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WbPriceTransportRateLimitTest {
    @TempDir Path root;
    private Shop shop;
    private static final String TOKEN="header."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"acc\":3}".getBytes(java.nio.charset.StandardCharsets.UTF_8))+".signature";
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.prepareStatement("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture','WILDBERRIES',?)")){
            s.setString(1,TOKEN);s.executeUpdate();
        }
        shop=new Shop(1,"Fixture",Marketplace.WILDBERRIES,null,TOKEN);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private ListingHttpClient client(MockWebServer server){
        var origin=server.url("/").newBuilder().host("discounts-prices-api.wildberries.ru").build();
        var local=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(host->List.of(InetAddress.getLoopbackAddress())).build();
        return new ListingHttpClient(shop,local,origin);
    }
    @Test void explicit429OnPriceReadWaitsAndRetries()throws Exception {
        try(var server=new MockWebServer()){
            server.start();server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After","0").setBody("{}"));
            server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
            assertTrue(client(server).call("api/v2/list/goods/filter?limit=100",null,false).getAsJsonObject().get("ok").getAsBoolean());
            assertEquals(2,server.getRequestCount());
        }
    }
    @Test void priceMutationIsNotAutomaticallyReplayedAfter429()throws Exception {
        try(var server=new MockWebServer()){
            server.start();server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After","0").setBody("{}"));
            server.enqueue(new MockResponse().setBody("{}"));
            assertThrows(ListingRateLimitException.class,()->client(server).call("api/v2/upload/task",new JsonObject(),true));
            assertEquals(1,server.getRequestCount());
        }
    }
}
