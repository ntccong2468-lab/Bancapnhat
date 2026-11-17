package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Loopback HTTP only: the paid-order group must survive through every legal-status route. */
class Wb1326ProtocolApiRoutingTest {
    @ParameterizedTest @ValueSource(strings={"lp","shoes"})
    void introductionAndReadbackUseTheSameExplicitGroup(String group)throws Exception{
        try(var remote=new Remote()){
            var api=new ZnackApiClient(new OkHttpClient());
            remote.reply(200,"\"synthetic-document\"");
            assertEquals("synthetic-document",api.createDocument(remote.base(),"fixture-token",new JsonObject(),group));
            remote.reply(200,"{}");api.document(remote.base(),"fixture-token","synthetic-document",group);
            var cis=new JsonArray();cis.add("synthetic-cis");
            remote.reply(200,"[]");api.cisesInfo(remote.base(),"fixture-token",cis,group);
            assertEquals(List.of("/api/v3/true-api/lk/documents/create?pg="+group,
                    "/api/v4/true-api/doc/synthetic-document/info?pg="+group,
                    "/api/v3/true-api/cises/info?pg="+group),remote.paths);
            assertEquals(List.of("POST","GET","POST"),remote.methods);
            assertEquals("Bearer fixture-token",remote.authorizations.getFirst());
            assertEquals(cis,JsonParser.parseString(remote.bodies.getLast()));
        }
    }

    @Test void explicitLightIndustryKeepsExistingVirtualAdapterMethods()throws Exception{
        var calls=new ArrayList<String>();
        var api=new ZnackApiClient(){
            @Override public String createDocument(String b,String t,JsonObject p){calls.add("create");return "retained-adapter";}
            @Override public JsonElement document(String b,String t,String id){calls.add("document");return JsonNull.INSTANCE;}
            @Override public JsonElement cisesInfo(String b,String t,JsonElement p){calls.add("cises");return new JsonArray();}
        };
        assertEquals("retained-adapter",api.createDocument("unused","unused",new JsonObject(),"lp"));
        api.document("unused","unused","id","lp");api.cisesInfo("unused","unused",new JsonArray(),"lp");
        assertEquals(List.of("create","document","cises"),calls);
    }

    @ParameterizedTest @ValueSource(strings={"","unknown","lp&pg=shoes","../shoes"})
    void unknownGroupCannotSilentlyTargetAnotherProductGroup(String group)throws Exception{
        try(var remote=new Remote()){
            var api=new ZnackApiClient(new OkHttpClient());
            assertThrows(IllegalArgumentException.class,()->api.createDocument(remote.base(),"fixture",new JsonObject(),group));
            assertThrows(IllegalArgumentException.class,()->api.document(remote.base(),"fixture","id",group));
            assertThrows(IllegalArgumentException.class,()->api.cisesInfo(remote.base(),"fixture",new JsonArray(),group));
            assertTrue(remote.paths.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings={"lp","shoes"})
    void cisBatchLimitIsEnforcedBeforeTransportForEitherGroup(String group)throws Exception{
        try(var remote=new Remote()){
            var values=new JsonArray();for(int i=0;i<=ZnackApiClient.CISES_MAX_CODES_PER_REQUEST;i++)values.add("fixture-"+i);
            assertThrows(IllegalArgumentException.class,()->new ZnackApiClient(new OkHttpClient())
                    .cisesInfo(remote.base(),"fixture",values,group));
            assertTrue(remote.paths.isEmpty());
        }
    }

    @Test void shoesHttpSuccessWithoutDocumentReceiptRemainsFailure()throws Exception{
        try(var remote=new Remote()){
            remote.reply(200,"{}");
            assertThrows(IOException.class,()->new ZnackApiClient(new OkHttpClient())
                    .createDocument(remote.base(),"fixture",new JsonObject(),"shoes"));
            assertEquals(1,remote.paths.size());
        }
    }

    @Test void interruptedPacingBeforePurchaseIsProvablyUnsent()throws Exception{
        try(var remote=new Remote()){
            var clock=new AtomicLong();
            var api=new ZnackApiClient(new OkHttpClient(),millis->{throw new InterruptedException("fixture");},new SuzRequestPacing(clock::get));
            remote.reply(200,"[]");api.orderStatus(remote.base(),"fixture","same-oms","existing-order");
            try{
                var deferred=assertThrows(SuzRequestPacing.DeferredException.class,()->api.createOrder(
                        remote.base(),"fixture","same-oms","{}".getBytes(StandardCharsets.UTF_8),"fixture-signature"));
                assertEquals(Duration.ofMillis(500),deferred.retryAfter());
                assertEquals(List.of("GET"),remote.methods,"Admission interruption must not send the paid POST");
            }finally{Thread.interrupted();}
        }
    }

    @Test void purchaseRateLimitDefersAccountWithoutReplayingMutation()throws Exception{
        try(var remote=new Remote()){
            var clock=new AtomicLong();var slept=new ArrayList<Long>();
            var api=new ZnackApiClient(new OkHttpClient(),millis->{slept.add(millis);clock.addAndGet(millis);},new SuzRequestPacing(clock::get));
            remote.reply(429,"{\"errorCode\":\"rate-limit\"}","3");
            var limited=assertThrows(ZnackApiClient.ZnackApiException.class,()->api.createOrder(
                    remote.base(),"fixture","same-oms","{}".getBytes(StandardCharsets.UTF_8),"fixture-signature"));
            assertEquals(429,limited.statusCode());assertEquals(Duration.ofSeconds(3),limited.retryAfter());
            assertEquals(List.of("POST"),remote.methods,"HTTP 429 must return the original intent to the durable scheduler");
            remote.reply(200,"[]");api.orderStatus(remote.base(),"fixture","same-oms","existing-order");
            assertEquals(List.of(3000L),slept);assertEquals(List.of("POST","GET"),remote.methods);
        }
    }

    @ParameterizedTest @CsvSource({"document,400","codes,400","order,400","document,401","codes,401","order,401"})
    void mutationDoesNotImplicitlyReplayAnUotOrExpiredSessionError(String operation,int status)throws Exception{
        try(var remote=new Remote()){
            var api=new ZnackApiClient(new OkHttpClient(),millis->{fail("A mutation must not sleep and replay");});
            remote.reply(status,"{\"errorCode\":\"1090\"}");
            assertThrows(ZnackApiClient.ZnackApiException.class,()->{
                switch(operation){
                    case "document"->api.createDocument(remote.base(),"fixture",new JsonObject(),"shoes");
                    case "codes"->api.codes(remote.base(),"fixture","oms","existing",1,"04600000000015");
                    case "order"->api.createOrder(remote.base(),"fixture","oms","{}".getBytes(StandardCharsets.UTF_8),"signature");
                    default->throw new AssertionError(operation);
                }
            });
            assertEquals(1,remote.paths.size(),"Only the durable engine may decide whether a mutation is known-unsent");
        }
    }

    private record Response(int status,String body,String retryAfter){}
    private static final class Remote implements AutoCloseable{
        final HttpServer server;
        final ConcurrentLinkedQueue<Response> responses=new ConcurrentLinkedQueue<>();
        final List<String> paths=new ArrayList<>(),methods=new ArrayList<>(),bodies=new ArrayList<>(),authorizations=new ArrayList<>();
        Remote()throws IOException{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                paths.add(exchange.getRequestURI().toString());methods.add(exchange.getRequestMethod());
                bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                Response response=responses.poll();if(response==null)response=new Response(500,"{}",null);
                byte[] bytes=response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                if(response.retryAfter()!=null)exchange.getResponseHeaders().set("Retry-After",response.retryAfter());
                exchange.sendResponseHeaders(response.status(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
        }
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void reply(int status,String body){reply(status,body,null);}
        void reply(int status,String body,String retryAfter){responses.add(new Response(status,body,retryAfter));}
        @Override public void close(){server.stop(0);}
    }
}
