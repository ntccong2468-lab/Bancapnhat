package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic paid-order checkpoints; no seller API, purchase, real signing or license activation. */
class ZnackExhaustedBufferRecoveryTest {
    static final String GTIN="04600000000015",OTHER_GTIN="04600000000022";
    static final String OLD="01"+GTIN+"21SERIAL0000001",NEW="01"+GTIN+"21SERIAL0000002";
    static final String EXHAUSTED="{\"globalErrors\":[{\"errorCode\":3390,\"error\":\"Буфер не активный. Текущий статус буфера: EXHAUSTED\"}]}";
    @TempDir Path directory;
    ZnackRepository repository;Settings settings;long order,pipeline;FakeApi api;ZnackKizCodeService codes;

    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var p=c.createStatement()){p.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Synthetic','fixture-token')");}
        repository=new ZnackRepository(new ShopContext(1,"Synthetic"));
        repository.upsertProducts(List.of(new Product(GTIN,"Synthetic",null,null,null,null,null),new Product(OTHER_GTIN,"Other synthetic",null,null,null,null,null)));
        settings=new Settings("","","oms","connection","","","","","certificate","[]","","","",false,"","[]","",Instant.now(),"","","",60,"");
        order=repository.createDraft(GTIN,2);repository.updateOrder(order,"original-order","ACTIVE",OrderStatus.CODES_READY,null);
        pipeline=repository.enqueuePipeline(GTIN,2,"original-request");repository.updatePipeline(pipeline,order,PurchaseStage.DOWNLOADING_CODES,null);
        api=new FakeApi();codes=service(api);
    }
    @AfterEach void cleanup(){ZnackPurchaseCoordinator.cancelPendingPolls();ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");}

    @Test void diagnosticClassifierRequiresTheBusinessCodeAndExactExhaustedState(){
        assertTrue(ZnackErrorMessages.isExhaustedKizBuffer(EXHAUSTED));
        assertTrue(ZnackErrorMessages.isExhaustedKizBuffer("HTTP 400: 3390; Buffer EXHAUSTED"));
        assertTrue(ZnackErrorMessages.isExhaustedKizBuffer(ZnackErrorMessages.EXHAUSTED_KIZ_BUFFER));
        for(String invalid:List.of("EXHAUSTED", "HTTP 400: 33901; Buffer EXHAUSTED","HTTP 400: 3390; Buffer NOT_EXHAUSTED","HTTP 400: 3390; Buffer NOT EXHAUSTED","HTTP 400: 3390; Buffer EXHAUSTED_EXTRA","HTTP 400: 3590; Buffer EXHAUSTED","HTTP 400: 3390; Buffer PENDING"))assertFalse(ZnackErrorMessages.isExhaustedKizBuffer(invalid),invalid);
        assertFalse(ZnackErrorMessages.isPendingKizBuffer(EXHAUSTED));assertFalse(ZnackErrorMessages.isClosedKizOrder(EXHAUSTED));
    }

    @Test void exhaustedStatusReadRecoversWithoutCallingOrdinaryCodeDelivery()throws Exception{
        api.blocks=blocks(block("issued",2));api.replies.put("issued",reply("issued",OLD,NEW));
        var orders=new ZnackKizOrderService(null,null,null,repository){
            @Override public KizOrder refresh(Settings ignored,long id){repository.updateOrder(id,null,"EXHAUSTED",OrderStatus.WAITING_CODES,null);return repository.findOrder(id).orElseThrow();}
            @Override public KizOrder buy(Settings ignored,String gtin,int quantity){fail("No second purchase");return null;}
        };
        repository.updatePipeline(pipeline,order,PurchaseStage.POLLING_ORDER,null);
        new ZnackPurchaseCoordinator(repository,orders,codes,null).advance(settings,pipeline);
        assertEquals(0,api.ordinary);assertEquals(1,api.lists);assertEquals(List.of("issued"),api.retries);assertComplete();
    }

    @Test void aPartialBlockIsReplayedAndAlreadyConsumedCodeIsNotResurrected()throws Exception{
        repository.insertCodes(order,GTIN,new DownloadedCodes(List.of(OLD),"issued"));consumeOld();
        api.blocks=blocks(block("issued",2));api.replies.put("issued",reply("issued",OLD,NEW));
        new ZnackPurchaseCoordinator(repository,null,codes,null).advance(settings,pipeline);
        assertEquals(1,api.ordinary);assertEquals(List.of("issued"),api.retries);assertComplete();assertOldConsumed();
        assertEquals(1,repository.findOrders().size());
    }

    @Test void partialRecoveryAndRestartOnlyReplayIssuedBlocks()throws Exception{
        api.blocks=blocks(block("first",1),block("second",1));
        api.replies.put("first",reply("first",OLD));api.replies.put("second",reply("second",NEW));api.failRetryOnce="second";
        var first=new ZnackPurchaseCoordinator(repository,null,codes,null);first.advance(settings,pipeline);
        var waiting=repository.findPipeline(pipeline).orElseThrow();
        assertEquals(PurchaseStage.DOWNLOADING_CODES,waiting.stage());assertTrue(waiting.active());assertEquals("EXHAUSTED",repository.findOrder(order).orElseThrow().remoteStatus());assertEquals(1,repository.findCodes(order).size());
        assertEquals("",ZnackErrorMessages.displayForPipeline(waiting.stage().name(),waiting.errorMessage()));
        new ZnackPurchaseCoordinator(repository,null,service(api),null).advance(settings,pipeline);
        assertEquals(1,api.ordinary,"Restart cannot issue another consumptive /codes request");
        assertEquals(List.of("first","second","second"),api.retries,"Only the complete first block has positive quantity proof and can be skipped");assertComplete();
    }

    @Test void completeLocalOrderSkipsAllSuzCallsAndKeepsConsumedStock()throws Exception{
        repository.insertCodes(order,GTIN,new DownloadedCodes(List.of(OLD,NEW),"issued"));consumeOld();
        repository.updateOrder(order,null,"EXHAUSTED",OrderStatus.WAITING_CODES,null);
        new ZnackPurchaseCoordinator(repository,null,codes,null).advance(settings,pipeline);
        assertEquals(0,api.ordinary);assertEquals(0,api.lists);assertTrue(api.retries.isEmpty());assertComplete();assertOldConsumed();
    }

    @ParameterizedTest @ValueSource(strings={"gtin","quantity","external"})
    void mismatchedLinkedLocalOrderCannotDownloadRecoverOrComplete(String mismatch)throws Exception{
        if(mismatch.equals("gtin")){
            long other=repository.createDraft(OTHER_GTIN,2);repository.updateOrder(other,"wrong-order","EXHAUSTED",OrderStatus.WAITING_CODES,null);repository.updatePipeline(pipeline,other,PurchaseStage.DOWNLOADING_CODES,EXHAUSTED);
        }else if(mismatch.equals("quantity")){
            long other=repository.createDraft(GTIN,1);repository.updateOrder(other,"wrong-order","EXHAUSTED",OrderStatus.WAITING_CODES,null);repository.updatePipeline(pipeline,other,PurchaseStage.DOWNLOADING_CODES,EXHAUSTED);
        }else repository.updateOrder(order,"","EXHAUSTED",OrderStatus.WAITING_CODES,null);
        var failure=assertThrows(IOException.class,()->new ZnackPurchaseCoordinator(repository,null,codes,null).advance(settings,pipeline));
        assertEquals("SUZ_CODE_BLOCK_RECOVERY_IDENTITY_INVALID",failure.getMessage());assertEquals(0,api.ordinary);assertEquals(0,api.lists);assertTrue(api.retries.isEmpty());
        assertEquals(PurchaseStage.DOWNLOADING_CODES,repository.findPipeline(pipeline).orElseThrow().stage());
    }

    @Test void unavailableRecoveryKeepsTheOriginalOrderAndDoesNotReissueOrBuy()throws Exception{
        api.listFailure=new SocketTimeoutException("Synthetic block lookup unavailable");
        var coordinator=new ZnackPurchaseCoordinator(repository,null,codes,null);coordinator.advance(settings,pipeline);coordinator.advance(settings,pipeline);
        assertEquals(1,api.ordinary);assertEquals(2,api.lists);assertTrue(api.retries.isEmpty());assertEquals(1,repository.findOrders().size());
        var waiting=repository.findPipeline(pipeline).orElseThrow();assertEquals(PurchaseStage.DOWNLOADING_CODES,waiting.stage());assertTrue(waiting.active());assertEquals("EXHAUSTED",repository.findOrder(order).orElseThrow().remoteStatus());assertTrue(repository.findCodes(order).isEmpty());
    }

    @Test void identityFailureRemainsVisibleAndStillRetainsExhaustedCheckpointAcrossRestart()throws Exception{
        api.blocks=blocks(block("issued",2));api.replies.put("issued",reply("foreign-block",OLD,NEW));
        var first=new ZnackPurchaseCoordinator(repository,null,codes,null);
        assertThrows(ZnackKizCodeService.RecoveryIdentityException.class,()->first.advance(settings,pipeline));
        var waiting=repository.findPipeline(pipeline).orElseThrow();assertEquals("EXHAUSTED",repository.findOrder(order).orElseThrow().remoteStatus());assertEquals("SUZ_CODE_BLOCK_RECOVERY_IDENTITY_INVALID",waiting.errorMessage());assertEquals(com.vncode.app.shared.I18nService.getInstance().tr("znack.error.suz_code_block_recovery_identity_invalid"),ZnackErrorMessages.displayForPipeline(waiting.stage().name(),waiting.errorMessage()));
        assertThrows(ZnackKizCodeService.RecoveryIdentityException.class,()->new ZnackPurchaseCoordinator(repository,null,service(api),null).advance(settings,pipeline));
        assertEquals(1,api.ordinary);assertEquals(2,api.lists);assertTrue(repository.findCodes(order).isEmpty());assertEquals(1,repository.findOrders().size());
    }

    @ParameterizedTest @ValueSource(strings={"metadata","raw"})
    void aFullButForeignStoredSetCannotFakeCompletionOrCauseDeliveryAfterRestart(String corrupt)throws Exception{
        repository.insertCodes(order,GTIN,new DownloadedCodes(List.of(corrupt.equals("raw")?"01"+OTHER_GTIN+"21SERIAL0000001":OLD,NEW),"issued"));
        try(var c=Database.getConnection();var p=c.createStatement()){
            p.execute("UPDATE kiz_codes SET status='CONSUMED',legal_status='IN_CIRCULATION' WHERE shop_id=1");
            if(corrupt.equals("metadata"))p.execute("UPDATE kiz_codes SET gtin='"+OTHER_GTIN+"' WHERE shop_id=1 AND raw_code='"+OLD+"'");
        }
        repository.updateOrder(order,null,"EXHAUSTED",OrderStatus.WAITING_CODES,null);
        for(int restart=0;restart<2;restart++)assertThrows(ZnackKizCodeService.RecoveryIdentityException.class,
                ()->new ZnackPurchaseCoordinator(repository,null,service(api),null).advance(settings,pipeline));
        assertEquals(0,api.ordinary);assertEquals(0,api.lists);assertTrue(api.retries.isEmpty());
        assertEquals("EXHAUSTED",repository.findOrder(order).orElseThrow().remoteStatus());
        assertEquals(PurchaseStage.DOWNLOADING_CODES,repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(2,repository.findCodes(order).size());assertTrue(repository.findCodes(order).stream().allMatch(code->code.inventoryStatus()==KizInventoryStatus.CONSUMED&&code.legalStatus()==KizLegalStatus.IN_CIRCULATION));assertEquals(1,repository.findOrders().size());
    }

    @ParameterizedTest @ValueSource(strings={"omsId","orderId","gtin","oms_id","order_id","product_gtin"})
    void foreignOptionalListIdentityIsRejectedBeforeAnyBlockReplay(String field)throws Exception{
        var list=blocks(block("issued",2));list.addProperty(field,field.toLowerCase(Locale.ROOT).contains("gtin")?OTHER_GTIN:"foreign");api.blocks=list;
        assertThrows(ZnackKizCodeService.RecoveryIdentityException.class,()->codes.recoverIssuedBlocks(settings,order));
        assertTrue(api.retries.isEmpty());assertTrue(repository.findCodes(order).isEmpty());
    }

    @Test void laterForeignListGroupIsRejectedBeforeReplayingAnEarlierValidGroup()throws Exception{
        var response=new JsonArray();response.add(blocks(block("first",1)));var foreign=blocks(block("second",1));foreign.addProperty("gtin",OTHER_GTIN);response.add(foreign);api.blocks=response;
        assertThrows(ZnackKizCodeService.RecoveryIdentityException.class,()->codes.recoverIssuedBlocks(settings,order));assertTrue(api.retries.isEmpty());assertTrue(repository.findCodes(order).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"omsId","orderId","gtin","blockId","rawGtin"})
    void retryPayloadCannotReplaceTheOriginalIdentity(String field)throws Exception{
        api.blocks=blocks(block("issued",2));var payload=reply("issued",OLD,NEW);
        if(field.equals("rawGtin"))payload=reply("issued","01"+OTHER_GTIN+"21SERIAL0000001");else payload.addProperty(field,field.equals("gtin")?OTHER_GTIN:"foreign");
        api.replies.put("issued",payload);assertThrows(ZnackKizCodeService.RecoveryIdentityException.class,()->codes.recoverIssuedBlocks(settings,order));assertTrue(repository.findCodes(order).isEmpty());
    }

    @Test void legacyListWithoutQuantityReplaysPartiallyPersistedBlockIdempotently()throws Exception{
        repository.insertCodes(order,GTIN,new DownloadedCodes(List.of(OLD),"issued"));consumeOld();
        api.blocks=JsonParser.parseString("{\"blocks\":[\"issued\"]}");api.replies.put("issued",reply("issued",OLD,NEW));
        assertEquals(1,codes.recoverIssuedBlocks(settings,order));assertEquals(0,codes.recoverIssuedBlocks(settings,order));assertEquals(1,api.lists);assertEquals(List.of("issued"),api.retries);assertOldConsumed();
    }

    @Test void uuidCaseAndGtinPaddingStillIdentifyTheSameOriginalRequest()throws Exception{
        String oms="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",external="11111111-aaaa-bbbb-cccc-222222222222",block="33333333-aaaa-bbbb-cccc-444444444444";
        var base=settings;settings=new Settings(base.trueApiBaseUrl(),base.suzBaseUrl(),oms,base.omsConnection(),"","","","","certificate","[]","","","",false,"","[]","",Instant.now(),"","","",60,"");
        repository.updateOrder(order,external,"EXHAUSTED",OrderStatus.WAITING_CODES,null);
        var list=new JsonObject();list.addProperty("omsId",oms.toUpperCase(Locale.ROOT));list.addProperty("orderId",external.toUpperCase(Locale.ROOT));list.addProperty("gtin",GTIN.substring(1));var blockItem=block(block,2);list.add("blocks",new Gson().toJsonTree(List.of(blockItem)));api.blocks=list;
        var payload=reply(block.toUpperCase(Locale.ROOT),OLD,NEW);payload.addProperty("omsId",oms.toUpperCase(Locale.ROOT));payload.addProperty("gtin",GTIN.substring(1));api.replies.put(block,payload);
        assertEquals(2,codes.recoverIssuedBlocks(settings,order));assertEquals(2,repository.findCodes(order).size());
    }

    private ZnackKizCodeService service(FakeApi transport){
        var auth=new ZnackAuthService(transport,null){@Override public String suzToken(Settings ignored){return "fixture-token";}};
        return new ZnackKizCodeService(transport,auth,repository);
    }
    private JsonObject blocks(JsonObject... blockItems){var result=new JsonObject();result.addProperty("omsId",settings.omsId());result.addProperty("orderId",repository.findOrder(order).orElseThrow().externalOrderId());result.addProperty("gtin",GTIN);var rows=new JsonArray();for(var item:blockItems)rows.add(item);result.add("blocks",rows);return result;}
    private static JsonObject block(String id,int quantity){var result=new JsonObject();result.addProperty("blockId",id);result.addProperty("quantity",quantity);return result;}
    private static JsonObject reply(String id,String... values){var result=new JsonObject();result.addProperty("blockId",id);result.add("codes",new Gson().toJsonTree(List.of(values)));return result;}
    private void consumeOld()throws Exception{try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE kiz_codes SET status='CONSUMED',legal_status='IN_CIRCULATION' WHERE shop_id=1 AND raw_code=?")){p.setString(1,OLD);assertEquals(1,p.executeUpdate());}}
    private void assertOldConsumed(){var saved=repository.findCodes(order).stream().filter(code->OLD.equals(code.rawCode())).findFirst().orElseThrow();assertEquals(KizInventoryStatus.CONSUMED,saved.inventoryStatus());assertEquals(KizLegalStatus.IN_CIRCULATION,saved.legalStatus());}
    private void assertComplete(){assertEquals(2,repository.findCodes(order).size());assertEquals("EXHAUSTED",repository.findOrder(order).orElseThrow().remoteStatus());assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(order).orElseThrow().localStatus());assertEquals(PurchaseStage.COMPLETED,repository.findPipeline(pipeline).orElseThrow().stage());}

    final class FakeApi extends ZnackApiClient{
        int ordinary,lists;final List<String> retries=new ArrayList<>();JsonElement blocks;final Map<String,JsonElement> replies=new HashMap<>();Exception listFailure;String failRetryOnce;
        @Override public JsonElement codes(String base,String token,String oms,String external,int quantity,String gtin)throws IOException{ordinary++;throw new ZnackApiException("Synthetic SUZ response",400,EXHAUSTED);}
        @Override public JsonElement codeBlocks(String base,String token,String oms,String external,String gtin)throws IOException{lists++;assertEquals(settings.omsId(),oms);assertEquals(repository.findOrder(order).orElseThrow().externalOrderId(),external);assertEquals(GTIN,gtin);if(listFailure instanceof IOException failure)throw failure;return Objects.requireNonNull(blocks);}
        @Override public JsonElement retryCodeBlock(String base,String token,String oms,String blockId)throws IOException{assertEquals(settings.omsId(),oms);retries.add(blockId);if(blockId.equals(failRetryOnce)){failRetryOnce=null;throw new SocketTimeoutException("Synthetic lost replay");}return Objects.requireNonNull(replies.get(blockId));}
        @Override public JsonObject createOrder(String base,String token,String oms,byte[] body,String signature){fail("Recovery must never create a new paid order");return null;}
    }
}
