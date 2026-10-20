package com.vncode.app.integration.znack;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ZnackMutationTransportTest {
    @Test void rateLimitedFeedPreservesServerDelayWithoutRepeatingItsPost() throws Exception {
        for(String retryAfter:java.util.List.of("125","invalid")){
            AtomicInteger requests=new AtomicInteger();
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                exchange.getRequestBody().readAllBytes();requests.incrementAndGet();
                exchange.getResponseHeaders().add("Retry-After",retryAfter);
                exchange.sendResponseHeaders(429,-1);exchange.close();
            });
            server.start();
            try{
                String base="http://127.0.0.1:"+server.getAddress().getPort();
                var failure=assertThrows(ZnackApiClient.ZnackApiException.class,
                        ()->new ZnackApiClient().submitNationalCatalogFeed(base,"fixture",new JsonObject()));
                assertEquals(1,requests.get(),"A rate-limited feed must not be blindly replayed");
                var reason=com.vncode.app.features.sync.SyncFailure.of(failure,0);
                assertEquals("ZNACK_RATE_LIMIT",reason.code());
                assertFalse(reason.actionRequired());
                assertEquals(java.time.Duration.ofSeconds(retryAfter.equals("125")?125:60),reason.delay());
            }finally{server.stop(0);}
        }
    }
    @Test void issuedKizBlockIsNotTransparentlyRequestedAgainAfterHttp408() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=server(requests);
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            assertThrows(ZnackApiClient.ZnackApiException.class,()->new ZnackApiClient().codes(
                    base,"fixture","fixture-oms","fixture-order",2,"04601234567890"));
            assertEquals(1,requests.get(),"An issued SUZ block must be recovered by block ID, not silently issued again");
        }finally{server.stop(0);}
    }
    @Test void issuedKizBlockIsNotRequestedAgainAfterHttp503WithImmediateRetryAfter() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=server(requests,503);
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            assertThrows(java.io.IOException.class,()->new ZnackApiClient().codes(
                    base,"fixture","fixture-oms","fixture-order",2,"04601234567890"));
            assertEquals(1,requests.get(),"503 Retry-After must not consume another block from the paid order");
        }finally{server.stop(0);}
    }
    @Test void allocationIsNotTransparentlyRepeatedAfterHttp408() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=server(requests);
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            assertThrows(ZnackApiClient.ZnackApiException.class,()->new ZnackApiClient().generateGtins(base,"fixture",1));
            assertEquals(1,requests.get(),"Even a GET allocation must not be replayed by OkHttp");
        }finally{server.stop(0);}
    }
    @Test void feedSubmissionIsNotTransparentlyRepeatedAfterHttp408() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=server(requests);
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            assertThrows(ZnackApiClient.ZnackApiException.class,()->new ZnackApiClient().submitNationalCatalogFeed(base,"fixture",new JsonObject()));
            assertEquals(1,requests.get(),"A lost feed result must be reconciled, not automatically resubmitted");
        }finally{server.stop(0);}
    }
    @Test void allocationIsNotRepeatedAfterHttp503WithImmediateRetryAfter() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=server(requests,503);
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            assertThrows(java.io.IOException.class,()->new ZnackApiClient().generateGtins(base,"fixture",1));
            assertEquals(1,requests.get(),"503 Retry-After must not replay allocation");
        }finally{server.stop(0);}
    }
    @Test void feedIsNotRepeatedAfterHttp503WithImmediateRetryAfter() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=server(requests,503);
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            assertThrows(java.io.IOException.class,()->new ZnackApiClient().submitNationalCatalogFeed(base,"fixture",new JsonObject()));
            assertEquals(1,requests.get(),"503 Retry-After must not replay a feed");
        }finally{server.stop(0);}
    }
    private HttpServer server(AtomicInteger requests) throws Exception {
        return server(requests,408);
    }
    private HttpServer server(AtomicInteger requests,int status) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();
            int count=requests.incrementAndGet();
            byte[] response=(count==1?"{\"error\":\"fixture request result lost\"}":
                    "{\"result\":{\"drafts\":[{\"gtin\":\"04631993764370\"}],\"feed_id\":\"duplicate\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            if(status==503)exchange.getResponseHeaders().add("Retry-After","0");
            exchange.sendResponseHeaders(count==1?status:200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();return server;
    }
}
