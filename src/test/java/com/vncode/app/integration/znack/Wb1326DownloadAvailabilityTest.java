package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Actual consuming GET service against synthetic responses; no order is bought or signed. */
class Wb1326DownloadAvailabilityTest {
    private static final String GTIN="04600000000015";
    private static final String FIRST=code("SERIAL0000001"),SECOND=code("SERIAL0000002"),THIRD=code("SERIAL0000003");
    @TempDir Path directory;
    private ZnackRepository repository;
    private final Settings settings=new Settings("","","fixture-oms","fixture-connection","7700000001","","","",
            "fixture-certificate","[]","","","",true,"","[]","fixture",Instant.now());

    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Availability fixture','fixture')");}
        repository=new ZnackRepository(new ShopContext(1,"Availability fixture"));
        repository.upsertProducts(List.of(new Product(GTIN,"Synthetic product",null,null,null,null,null)));
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void zeroAvailabilityDoesNotAuthenticateOrClaimAConsumingRequest()throws Exception{
        long id=order(2);var remote=new Remote();
        assertEquals(0,remote.service().download(settings,id,0));
        assertEquals(0,remote.authentication);assertEquals(0,remote.deliveries);
        assertTrue(java.util.Objects.toString(repository.findOrder(id).orElseThrow().errorMessage(),"").isBlank());assertTrue(repository.findCodes(id).isEmpty());
    }
    @Test void freshRequestUsesTheSmallerOfAvailabilityAndDurableRemainder()throws Exception{
        long id=order(3);repository.insertCodes(id,GTIN,new DownloadedCodes(List.of(FIRST),"older"));
        var remote=new Remote();remote.delivery=block(SECOND);
        assertEquals(1,remote.service().download(settings,id,1));assertEquals(1,remote.requested);
        assertEquals(List.of(FIRST,SECOND),repository.findCodes(id).stream().map(KizCode::rawCode).toList());
        assertEquals(OrderStatus.WAITING_CODES,repository.findOrder(id).orElseThrow().localStatus());
    }
    @Test void aResponseLargerThanTheRequestedFreshBlockCannotPartiallyEnterStock()throws Exception{
        long id=order(3);var remote=new Remote();remote.delivery=block(FIRST,SECOND);
        assertThrows(IOException.class,()->remote.service().download(settings,id,1));
        assertTrue(repository.findCodes(id).isEmpty());
        assertEquals(ZnackKizCodeService.DOWNLOAD_OUTCOME_UNCERTAIN,repository.findOrder(id).orElseThrow().errorMessage());
    }
    @Test void uncertainIssuedBlockRecoveryContinuesEvenWhenFreshAvailabilityIsZero()throws Exception{
        long id=order(2);repository.insertCodes(id,GTIN,new DownloadedCodes(List.of(FIRST),"issued"));
        repository.updateOrder(id,null,null,OrderStatus.WAITING_CODES,ZnackKizCodeService.DOWNLOAD_OUTCOME_UNCERTAIN);
        var remote=new Remote();remote.replay=block(FIRST,SECOND);
        assertEquals(1,remote.service().download(settings,id,0));assertEquals(0,remote.deliveries);
        assertEquals(1,remote.blockReads);assertEquals(1,remote.blockReplays);
        assertEquals(2,repository.findCodes(id).size());assertTrue(java.util.Objects.toString(repository.findOrder(id).orElseThrow().errorMessage(),"").isBlank());
        assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(id).orElseThrow().localStatus());
    }
    @Test void anAlreadyCompleteOrderNeedsNoNewDeliveryEvenWithZeroAvailability()throws Exception{
        long id=order(1);repository.insertCodes(id,GTIN,new DownloadedCodes(List.of(THIRD),"saved"));
        var remote=new Remote();assertEquals(0,remote.service().download(settings,id,0));
        assertEquals(0,remote.authentication);assertEquals(0,remote.deliveries);
        assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(id).orElseThrow().localStatus());
    }
    @Test void negativeAvailabilityDoesNotReadOrMutateAnOrder(){
        var remote=new Remote();assertThrows(IllegalArgumentException.class,()->remote.service().download(settings,999,-1));
        assertEquals(0,remote.authentication);assertEquals(0,remote.deliveries);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void lostCappedDeliveryRecoversItsExactSavedTargetThenRequestsOnlyTheRemainder(boolean statusRefreshClearsDisplayedError)throws Exception{
        long id=order(5);var remote=new Remote();remote.loseDelivery=true;
        assertThrows(IOException.class,()->remote.service().download(settings,id,3));
        assertEquals(3,remote.requested);assertTrue(repository.findCodes(id).isEmpty());
        if(statusRefreshClearsDisplayedError)repository.updateOrder(id,null,"ACTIVE",OrderStatus.WAITING_CODES,null);
        remote.replay=block(FIRST,SECOND,THIRD);remote.blockQuantity=3;
        assertEquals(3,remote.service().download(settings,id,0),"A recovered capped package must not wait forever for the entire approved order");
        assertEquals(OrderStatus.WAITING_CODES,repository.findOrder(id).orElseThrow().localStatus());
        assertTrue(java.util.Objects.toString(repository.findOrder(id).orElseThrow().errorMessage(),"").isBlank());
        assertEquals(1,remote.deliveries,"Issued-block recovery must never make a new consuming GET");
        remote.delivery=block(code("SERIAL0000004"),code("SERIAL0000005"));
        assertEquals(2,remote.service().download(settings,id,2));assertEquals(2,remote.requested);
        assertEquals(2,remote.deliveries);assertEquals(5,repository.findCodes(id).size());
        assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(id).orElseThrow().localStatus());
    }

    @Test void expiredConsumingDeliveryIsNeverAutomaticallyReplayed()throws Exception{
        long id=order(2);var remote=new Remote();remote.rejectDelivery=true;
        assertThrows(ZnackApiClient.ZnackApiException.class,()->remote.service().download(settings,id,2));
        assertEquals(1,remote.deliveries);assertEquals(1,remote.authentication);assertTrue(repository.findCodes(id).isEmpty());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"blocks","replay"})
    void expiredIssuedBlockReadsRefreshOnlyThatRead(String route)throws Exception{
        long id=order(2);repository.updateOrder(id,null,null,OrderStatus.WAITING_CODES,ZnackKizCodeService.DOWNLOAD_OUTCOME_UNCERTAIN);
        var remote=new Remote();remote.expireRoute=route;
        assertEquals(2,remote.service().download(settings,id,0));
        assertEquals(1,remote.invalidations);assertEquals(0,remote.deliveries);
        assertEquals(2,repository.findCodes(id).size());
    }

    private long order(int quantity){long id=repository.createDraft(GTIN,quantity);repository.updateOrder(id,"synthetic-remote","ACTIVE",OrderStatus.WAITING_CODES,null);return id;}
    private static String code(String serial){return "01"+GTIN+"21"+serial+"\u001d91ABCD\u001d92synthetic";}
    private static JsonObject block(String...codes){var body=new JsonObject();body.addProperty("orderId","synthetic-remote");body.addProperty("omsId","fixture-oms");body.addProperty("gtin",GTIN);body.addProperty("blockId","issued");var list=new JsonArray();for(String value:codes)list.add(value);body.add("codes",list);return body;}
    private final class Remote extends ZnackApiClient{
        int authentication,deliveries,requested,blockReads,blockReplays,invalidations,blockQuantity=2;
        boolean loseDelivery,rejectDelivery;String expireRoute="";
        JsonElement delivery=block(FIRST),replay=block(FIRST,SECOND);
        @Override public JsonElement codes(String b,String t,String o,String id,int q,String g)throws IOException{
            deliveries++;requested=q;
            if(loseDelivery){loseDelivery=false;throw new IOException("Synthetic lost consuming response");}
            if(rejectDelivery)throw new ZnackApiException("Synthetic expired consuming request",401,"{}");
            return delivery.deepCopy();
        }
        private void expire(String route)throws IOException{
            if(route.equals(expireRoute)){expireRoute="";throw new ZnackApiException("Synthetic expired read",401,"{}");}
        }
        @Override public JsonElement codeBlocks(String b,String t,String o,String id,String g)throws IOException{
            blockReads++;expire("blocks");return JsonParser.parseString("{\"blocks\":[{\"blockId\":\"issued\",\"quantity\":"+blockQuantity+"}]}");
        }
        @Override public JsonElement retryCodeBlock(String b,String t,String o,String id)throws IOException{blockReplays++;expire("replay");return replay.deepCopy();}
        ZnackKizCodeService service(){
            var auth=new ZnackAuthService(this,ZnackSignatureProvider.unconfigured()){
                @Override public String suzToken(Settings s){authentication++;return "fixture";}
                @Override public synchronized boolean invalidateSuzToken(Settings s,String rejected){invalidations++;return true;}
            };
            return new ZnackKizCodeService(this,auth,repository);
        }
    }
}
