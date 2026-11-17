package com.vncode.app.integration.znack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real normal-download service and localhost HTTP; no seller, signing or paid request. */
class ZnackNormalDownloadIdentityTest {
    private static final String GTIN="04600000000015";
    private static final String OTHER_GTIN="04600000000022";
    private static final String FIRST=mark(GTIN,"SERIAL0000001");
    private static final String SECOND=mark(GTIN,"SERIAL0000002");
    private static final String THIRD=mark(GTIN,"SERIAL0000003");
    @TempDir Path directory;
    private ZnackRepository repository;

    @BeforeEach void prepare()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());
        Database.initDatabase();
        try(var c=Database.getConnection();var statement=c.createStatement()){
            statement.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Download fixture','fixture')");
        }
        repository=new ZnackRepository(new ShopContext(1,"Download fixture"));
        repository.upsertProducts(List.of(new Product(GTIN,"Synthetic product",null,null,null,null,null)));
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @ParameterizedTest @ValueSource(strings={"omsId","orderId","gtin","product_gtin"})
    void suppliedForeignIdentityRejectsTheWholeNormalBlockBeforePersistence(String field)throws Exception{
        long order=order(2);
        var payload=block(FIRST,SECOND);
        payload.addProperty(field,field.toLowerCase(java.util.Locale.ROOT).contains("gtin")?OTHER_GTIN:"foreign-identity");
        try(var remote=new Remote(payload)){
            assertThrows(IOException.class,()->remote.service().download(remote.settings,order));
            assertUnchangedEmptyOrder(order);
            remote.assertSingleOrdinaryDelivery();
        }
    }

    @Test void foreignEmbeddedGtinAfterAValidCodeCannotPartiallyPoisonTheOrder()throws Exception{
        long order=order(2);
        try(var remote=new Remote(block(FIRST,mark(OTHER_GTIN,"SERIAL0000009")))){
            assertThrows(IOException.class,()->remote.service().download(remote.settings,order));
            assertUnchangedEmptyOrder(order);
            remote.assertSingleOrdinaryDelivery();
        }
    }

    @Test void tooManyDistinctCodesCannotMarkAnOversizedOrderDownloaded()throws Exception{
        long order=order(2);
        try(var remote=new Remote(block(FIRST,SECOND,THIRD))){
            assertThrows(IOException.class,()->remote.service().download(remote.settings,order));
            assertUnchangedEmptyOrder(order);
            remote.assertSingleOrdinaryDelivery();
        }
    }

    @Test void sameCisWithDifferentCryptographicPayloadCannotEnterOneBlockTwice()throws Exception{
        long order=order(3);
        try(var remote=new Remote(block(FIRST,FIRST+"-different-tail",SECOND))){
            assertThrows(IOException.class,()->remote.service().download(remote.settings,order));
            assertUnchangedEmptyOrder(order);remote.assertSingleOrdinaryDelivery();
        }
    }

    @Test void aNewEncodingOfAnExistingCisCannotPoisonAnIncompleteOrder()throws Exception{
        long order=order(3);
        repository.insertCodes(order,GTIN,new DownloadedCodes(List.of(FIRST),"already-stored"));
        var before=repository.findCodes(order);
        try(var remote=new Remote(block(FIRST+"-different-tail",SECOND))){
            assertThrows(IOException.class,()->remote.service().download(remote.settings,order));
            assertEquals(before,repository.findCodes(order));
            assertNotEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(order).orElseThrow().localStatus());
            remote.assertSingleOrdinaryDelivery();
        }
    }

    @Test void onlyNewDistinctCodesCountAgainstTheDurableRemainder()throws Exception{
        long order=order(2);
        repository.insertCodes(order,GTIN,new DownloadedCodes(List.of(FIRST),"already-stored"));
        try(var remote=new Remote(block(FIRST,FIRST,SECOND,SECOND))){
            assertEquals(1,remote.service().download(remote.settings,order));
            assertEquals(List.of(FIRST,SECOND),repository.findCodes(order).stream().map(KizCode::rawCode).toList());
            assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(order).orElseThrow().localStatus());
            assertTrue(remote.query.contains("quantity=1"));
            remote.assertSingleOrdinaryDelivery();
        }
    }

    @Test void oversizedNewRemainderPreservesTheAlreadyStoredCode()throws Exception{
        long order=order(2);
        repository.insertCodes(order,GTIN,new DownloadedCodes(List.of(FIRST),"already-stored"));
        var before=repository.findCodes(order);
        try(var remote=new Remote(block(FIRST,SECOND,THIRD))){
            assertThrows(IOException.class,()->remote.service().download(remote.settings,order));
            assertEquals(before,repository.findCodes(order));
            assertNotEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(order).orElseThrow().localStatus());
            remote.assertSingleOrdinaryDelivery();
        }
    }

    @Test void validPartialDeliveryPreservesFullGsAndCryptographicPayload()throws Exception{
        long order=order(2);
        try(var remote=new Remote(block(FIRST))){
            assertEquals(1,remote.service().download(remote.settings,order));
            assertEquals(FIRST,repository.findCodes(order).getFirst().rawCode());
            assertEquals("issued-block",repository.findCodes(order).getFirst().blockId());
            assertEquals(KizLegalStatus.RECEIVED,repository.findCodes(order).getFirst().legalStatus());
            assertEquals(OrderStatus.WAITING_CODES,repository.findOrder(order).orElseThrow().localStatus());
            remote.assertSingleOrdinaryDelivery();
        }
    }

    @Test void legacyOpaqueAndBareArrayResponsesKeepTheirOriginalPayload()throws Exception{
        long order=order(2);var payload=new JsonArray();payload.add("legacy-opaque-one");payload.add("legacy-opaque-two");
        try(var remote=new Remote(payload)){
            assertEquals(2,remote.service().download(remote.settings,order));
            assertEquals(List.of("legacy-opaque-one","legacy-opaque-two"),repository.findCodes(order).stream().map(KizCode::rawCode).toList());
            assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(order).orElseThrow().localStatus());
            assertEquals(1,repository.findOrders().size());
            remote.assertSingleOrdinaryDelivery();
        }
    }

    @Test void duplicateValuesWithinAValidBlockDoNotInflateQuantity()throws Exception{
        long order=order(2);
        try(var remote=new Remote(block(FIRST,FIRST,SECOND,SECOND))){
            assertEquals(2,remote.service().download(remote.settings,order));
            assertEquals(2,repository.findCodes(order).size());
            assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(order).orElseThrow().localStatus());
            remote.assertSingleOrdinaryDelivery();
        }
    }

    @ParameterizedTest @ValueSource(strings={"errorCode","errorMessage","globalErrors","errors"})
    void successHttpWithReportedBusinessErrorPreservesTheUncertainDeliveryReceipt(String field)throws Exception{
        long order=order(1);var payload=block(FIRST);
        if(field.equals("errorCode"))payload.addProperty(field,"110");
        else if(field.equals("errorMessage"))payload.addProperty(field,"Synthetic rejected");
        else {var errors=new JsonArray();errors.add("synthetic");payload.add(field,errors);}
        try(var remote=new Remote(payload)){
            assertAll("Business errors cannot commit a successful normal delivery",
                    ()->assertThrows(IOException.class,()->remote.service().download(remote.settings,order)),
                    ()->assertTrue(repository.findCodes(order).isEmpty(),"No code may enter stock from a rejected block"),
                    ()->assertEquals(ZnackKizCodeService.DOWNLOAD_OUTCOME_UNCERTAIN,repository.findOrder(order).orElseThrow().errorMessage()),
                    ()->assertNotEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(order).orElseThrow().localStatus()),
                    remote::assertSingleOrdinaryDelivery);
        }
    }

    @Test void zeroErrorCodeAndEmptyErrorCollectionsKeepAValidResponseCompatible()throws Exception{
        long order=order(1);var payload=block(FIRST);
        payload.addProperty("errorCode","0");payload.addProperty("errorMessage","");
        payload.add("globalErrors",new JsonArray());payload.add("errors",new JsonArray());
        try(var remote=new Remote(payload)){
            assertEquals(1,remote.service().download(remote.settings,order));
            assertEquals(FIRST,repository.findCodes(order).getFirst().rawCode());
            assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(order).orElseThrow().localStatus());
            assertTrue(java.util.Objects.toString(repository.findOrder(order).orElseThrow().errorMessage(),"").isBlank());
            remote.assertSingleOrdinaryDelivery();
        }
    }

    private void assertUnchangedEmptyOrder(long id){
        assertTrue(repository.findCodes(id).isEmpty(),"Invalid blocks must not store even their first valid-looking code");
        var order=repository.findOrder(id).orElseThrow();
        assertEquals("fixture-order",order.externalOrderId());assertEquals(GTIN,order.gtin());
        assertNotEquals(OrderStatus.CODES_DOWNLOADED,order.localStatus());assertEquals(1,repository.findOrders().size());
    }
    private long order(int quantity){
        long id=repository.createDraft(GTIN,quantity);
        repository.updateOrder(id,"fixture-order","ACTIVE",OrderStatus.CODES_READY,null);return id;
    }
    private static String mark(String gtin,String serial){return "01"+gtin+"21"+serial+'\u001d'+"91ABCD"+'\u001d'+"92fixture-crypto-tail";}
    private static JsonObject block(String...codes){
        var result=new JsonObject();result.addProperty("omsId","fixture-oms");result.addProperty("blockId","issued-block");
        var values=new JsonArray();for(var code:codes)values.add(code);result.add("codes",values);return result;
    }
    private final class Remote implements AutoCloseable{
        final HttpServer server;final ZnackApiClient api=new ZnackApiClient();
        final AtomicInteger ordinary=new AtomicInteger(),unexpected=new AtomicInteger();final Settings settings;
        volatile String query="";
        Remote(JsonElement response)throws IOException{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                query=exchange.getRequestURI().getRawQuery();
                boolean expected=exchange.getRequestMethod().equals("GET")&&exchange.getRequestURI().getPath().equals("/api/v3/codes");
                if(expected)ordinary.incrementAndGet();else unexpected.incrementAndGet();
                respond(exchange,expected?200:500,response.toString());
            });server.start();
            settings=new Settings("","http://127.0.0.1:"+server.getAddress().getPort(),"fixture-oms","fixture-connection","","","","",
                    "fixture-certificate","[]","","","",false,"","[]","",Instant.now());
        }
        ZnackKizCodeService service(){
            var auth=new ZnackAuthService(api,ZnackSignatureProvider.unconfigured()){
                @Override public String suzToken(Settings ignored){return "fixture-token";}
            };return new ZnackKizCodeService(api,auth,repository);
        }
        void assertSingleOrdinaryDelivery(){assertEquals(1,ordinary.get());assertEquals(0,unexpected.get(),"No extra purchase/retry/auth call");}
        @Override public void close(){server.stop(0);}
    }
    private static void respond(HttpExchange exchange,int status,String body)throws IOException{
        exchange.getRequestBody().readAllBytes();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);
        exchange.getResponseBody().write(bytes);exchange.close();
    }
}
