package com.vncode.app.integration.znack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ZnackKizPurchaseReadbackTest {
    private static final String GTIN="04601234567890";
    private static final String OTHER_GTIN="04601234567891";
    @TempDir Path temp;
    private ZnackRepository repository;

    @BeforeEach void prepare() throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());
        Database.initDatabase();
        try(var connection=Database.getConnection();var statement=connection.createStatement()){
            statement.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Purchase fixture','fixture')");
        }
        repository=new ZnackRepository(new ShopContext(1,"Purchase fixture"));
        repository.upsertProducts(List.of(new Product(GTIN,"Fixture product",null,null,null,null,null)));
    }

    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}

    @Test void pendingPurchasedBufferDoesNotInheritAnotherGtinsReadyStatus() throws Exception {
        long order=order(2);
        try(var remote=new Remote(exchange->respond(exchange,"""
                [{"gtin":"%s","bufferStatus":"PENDING","availableCodes":1},
                 {"gtin":"%s","bufferStatus":"ACTIVE","availableCodes":200}]
                """.formatted(GTIN,OTHER_GTIN)))){
            KizOrder result=remote.orders().refresh(remote.settings,order);
            assertEquals(OrderStatus.WAITING_CODES,result.localStatus());
            assertEquals("PENDING",result.remoteStatus());
            assertEquals("fixture-order",result.externalOrderId());
        }
    }

    @Test void readyPurchasedBufferDoesNotInheritAnotherGtinsRejection() throws Exception {
        long order=order(2);
        try(var remote=new Remote(exchange->respond(exchange,"""
                [{"gtin":"%s","bufferStatus":"ACTIVE","availableCodes":2},
                 {"gtin":"%s","bufferStatus":"REJECTED","rejectionReason":"Other GTIN rejection"}]
                """.formatted(GTIN,OTHER_GTIN)))){
            KizOrder result=remote.orders().refresh(remote.settings,order);
            assertEquals(OrderStatus.CODES_READY,result.localStatus());
            assertEquals("ACTIVE",result.remoteStatus());
            assertNotEquals("Other GTIN rejection",result.errorMessage());
        }
    }

    @Test void duplicatePurchasedBuffersPreserveAcceptedOrderWithoutGuessing() throws Exception {
        long order=order(2);
        KizOrder before=repository.findOrder(order).orElseThrow();
        try(var remote=new Remote(exchange->respond(exchange,"""
                [{"gtin":"%s","bufferStatus":"ACTIVE","availableCodes":2},
                 {"gtin":"%s","bufferStatus":"REJECTED","rejectionReason":"Ambiguous buffer"}]
                """.formatted(GTIN,GTIN)))){
            IOException failure=assertThrows(IOException.class,()->remote.orders().refresh(remote.settings,order));
            assertEquals(ZnackKizOrderService.BUFFER_MULTIPLE,failure.getMessage());
            assertEquals(before,repository.findOrder(order).orElseThrow());
        }
    }

    @Test void missingPurchasedBufferPreservesCheckpointInsteadOfUsingLastStatus() throws Exception {
        long order=order(2);
        KizOrder before=repository.findOrder(order).orElseThrow();
        try(var remote=new Remote(exchange->respond(exchange,"""
                [{"bufferStatus":"ACTIVE","availableCodes":2},
                 {"gtin":"%s","bufferStatus":"READY","availableCodes":2}]
                """.formatted(OTHER_GTIN)))){
            IOException failure=assertThrows(IOException.class,()->remote.orders().refresh(remote.settings,order));
            assertEquals(ZnackKizOrderService.BUFFER_MISSING,failure.getMessage());
            assertEquals(before,repository.findOrder(order).orElseThrow());
        }
    }

    @Test void missingPurchasedBufferStatusPreservesAcceptedOrderForFurtherReadback() throws Exception {
        long order=order(2);
        KizOrder before=repository.findOrder(order).orElseThrow();
        try(var remote=new Remote(exchange->respond(exchange,"""
                [{"gtin":"%s","availableCodes":2}]
                """.formatted(GTIN)))){
            IOException failure=assertThrows(IOException.class,()->remote.orders().refresh(remote.settings,order));
            assertEquals(ZnackKizOrderService.BUFFER_STATUS_MISSING,failure.getMessage());
            assertEquals(before,repository.findOrder(order).orElseThrow());
        }
    }

    @Test void partialDeliveryRemainsWaitingAndRequestsOnlyDurableRemainder() throws Exception {
        long order=order(3);
        repository.updateOrder(order,null,"ACTIVE",OrderStatus.CODES_READY,null);
        AtomicInteger calls=new AtomicInteger();
        List<String> queries=new ArrayList<>();
        try(var remote=new Remote(exchange->{
            queries.add(exchange.getRequestURI().getRawQuery());
            respond(exchange,calls.incrementAndGet()==1
                    ?"{\"blockId\":\"first\",\"codes\":[\"fixture-first\"]}"
                    :"{\"blockId\":\"second\",\"codes\":[\"fixture-second\",\"fixture-third\"]}");
        })){
            ZnackKizCodeService codes=new ZnackKizCodeService(remote.api,remote.auth,repository);
            assertEquals(1,codes.download(remote.settings,order));
            assertEquals(OrderStatus.WAITING_CODES,repository.findOrder(order).orElseThrow().localStatus());
            assertEquals("ACTIVE",repository.findOrder(order).orElseThrow().remoteStatus());
            assertEquals(2,codes.download(remote.settings,order));
            assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(order).orElseThrow().localStatus());
            assertEquals(3,repository.findCodes(order).size());
            assertEquals(List.of(
                    "omsId=fixture-oms&orderId=fixture-order&quantity=3&gtin="+GTIN,
                    "omsId=fixture-oms&orderId=fixture-order&quantity=2&gtin="+GTIN),queries);
            assertEquals(0,codes.download(remote.settings,order));
            assertEquals(2,calls.get(),"Already persisted paid codes must not cause another issuance request");
        }
    }

    @Test void emptyDeliveryDoesNotKeepAFalseCodesReadyLocalStatus() throws Exception {
        long order=order(2);
        repository.updateOrder(order,null,"ACTIVE",OrderStatus.CODES_READY,null);
        try(var remote=new Remote(exchange->respond(exchange,"{\"codes\":[]}"))){
            assertEquals(0,new ZnackKizCodeService(remote.api,remote.auth,repository).download(remote.settings,order));
            assertEquals(OrderStatus.WAITING_CODES,repository.findOrder(order).orElseThrow().localStatus());
            assertTrue(repository.findCodes(order).isEmpty());
        }
    }

    private long order(int quantity){
        long id=repository.createDraft(GTIN,quantity);
        repository.updateOrder(id,"fixture-order","CREATED",OrderStatus.SUBMITTED,null);
        return id;
    }

    private final class Remote implements AutoCloseable {
        final HttpServer server;
        final ZnackApiClient api=new ZnackApiClient();
        final ZnackAuthService auth=new ZnackAuthService(api,ZnackSignatureProvider.unconfigured()){
            @Override public String suzToken(Settings ignored){return "fixture-token";}
        };
        final Settings settings;
        Remote(HttpHandler handler) throws IOException {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",handler);server.start();
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            settings=new Settings("",base,"fixture-oms","fixture-connection","","","", "",
                    "fixture-certificate","[]","","","",false,"","[]","",Instant.now());
        }
        ZnackKizOrderService orders(){return new ZnackKizOrderService(api,auth,ZnackSignatureProvider.unconfigured(),repository);}
        @Override public void close(){server.stop(0);}
    }

    private static void respond(HttpExchange exchange,String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(200,bytes.length);
        exchange.getResponseBody().write(bytes);exchange.close();
    }
}
