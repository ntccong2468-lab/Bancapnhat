package com.vncode.app.integration.znack;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ZnackMutationTransportTest {
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
