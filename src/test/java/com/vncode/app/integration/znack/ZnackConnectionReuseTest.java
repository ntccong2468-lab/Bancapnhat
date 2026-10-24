package com.vncode.app.integration.znack;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZnackConnectionReuseTest {
    @Test void separateApiClientsReuseTheSocketWithoutReusingRequestCredentials()throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(new MockResponse().setBody("{\"result\":[]}"));
            server.enqueue(new MockResponse().setBody("{\"result\":[]}"));server.start();
            var base=server.url("/").toString();
            new ZnackApiClient().nationalCatalogCategories(base,"fixture-first-token","6112");
            new ZnackApiClient().nationalCatalogCategories(base,"fixture-second-token","6112");
            var first=server.takeRequest();var second=server.takeRequest();
            assertEquals(0,first.getSequenceNumber());assertEquals(1,second.getSequenceNumber(),"The next card should reuse the keep-alive socket");
            assertEquals("Bearer fixture-first-token",first.getHeader("Authorization"));
            assertEquals("Bearer fixture-second-token",second.getHeader("Authorization"));
            assertNull(first.getRequestUrl().queryParameter("apikey"));assertNull(second.getRequestUrl().queryParameter("apikey"));
        }
    }
}
