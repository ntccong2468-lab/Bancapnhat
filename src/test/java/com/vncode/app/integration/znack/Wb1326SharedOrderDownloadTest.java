package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.ZnackPurchaseBatchStore.Approved;
import com.vncode.app.integration.znack.ZnackPurchaseBatchStore.Batch;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual download/WAL behavior for multiple GTINs in one synthetic SUZ order; no paid or remote calls. */
class Wb1326SharedOrderDownloadTest {
    private static final String FIRST_GTIN="04600000000015", SECOND_GTIN="04600000000022", REMOTE_ID="synthetic-shared-suz-order";
    private static final String A1=raw(FIRST_GTIN,"SHARED0000001"),A2=raw(FIRST_GTIN,"SHARED0000002"),B1=raw(SECOND_GTIN,"SHARED0000003");
    @TempDir Path directory;
    private ZnackRepository repository;
    private ZnackPurchaseBatchStore store;
    private Batch batch;
    private long firstOrder,secondOrder;
    private final Settings original=settings("original");
    private final Remote remote=new Remote();

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shared order fixture','fixture')");}
        repository=new ZnackRepository(new ShopContext(1,"Shared order fixture"));repository.saveSettings(original);
        repository.upsertProducts(List.of(new Product(FIRST_GTIN,"First product","6103100000",null,null,null,null),
                new Product(SECOND_GTIN,"Second product","6103100000",null,null,null,null)));
        store=new ZnackPurchaseBatchStore(repository);
        var pipelines=store.enqueueApproved(original,List.of(new Approved(FIRST_GTIN,2,UUID.randomUUID().toString()),
                new Approved(SECOND_GTIN,1,UUID.randomUUID().toString())));
        batch=store.prepare(pipelines.getFirst()).orElseThrow();assertEquals(2,batch.lines().size());
        firstOrder=batch.lines().stream().filter(x->x.gtin().equals(FIRST_GTIN)).findFirst().orElseThrow().orderId();
        secondOrder=batch.lines().stream().filter(x->x.gtin().equals(SECOND_GTIN)).findFirst().orElseThrow().orderId();
        JsonObject payload=new JsonObject();payload.addProperty("productGroup","lp");JsonObject attributes=new JsonObject();attributes.addProperty("releaseMethodType","PRODUCTION");payload.add("attributes",attributes);
        JsonArray products=new JsonArray();for(var line:batch.lines()){JsonObject p=new JsonObject();p.addProperty("gtin",line.gtin());p.addProperty("quantity",line.quantity());
            p.addProperty("templateId",batch.group().templateId());p.addProperty("serialNumberType","OPERATOR");p.addProperty("cisType","UNIT");products.add(p);}payload.add("products",products);
        store.savePayload(batch.id(),payload.toString());assertTrue(store.beginSubmission(batch.id()));store.acceptRemoteOrder(batch.id(),REMOTE_ID);
        repository.updateOrder(firstOrder,null,"ACTIVE",OrderStatus.WAITING_CODES,null);
        repository.updateOrder(secondOrder,null,"ACTIVE",OrderStatus.WAITING_CODES,null);
        assertTrue(ZnackSigningSession.authorizeShopExplicitly(repository));
    }
    @AfterEach void cleanup(){ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");}

    @Test void oneRemoteBatchDownloadsDifferentQuantitiesIntoTheirExactLocalGtinMembers()throws Exception {
        assertNotEquals(firstOrder,secondOrder);
        assertEquals(REMOTE_ID,repository.findOrder(firstOrder).orElseThrow().externalOrderId());
        assertEquals(REMOTE_ID,repository.findOrder(secondOrder).orElseThrow().externalOrderId());
        assertEquals(2,remote.service().download(original,firstOrder));
        assertEquals(1,remote.service().download(original,secondOrder));
        assertEquals(List.of(new Request(FIRST_GTIN,2),new Request(SECOND_GTIN,1)),remote.deliveries);
        assertEquals(List.of(A1,A2),rawCodes(firstOrder));assertEquals(List.of(B1),rawCodes(secondOrder));
        assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(firstOrder).orElseThrow().localStatus());
        assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(secondOrder).orElseThrow().localStatus());
        assertEquals(2,repository.findOrders().size(),"Downloading members must not create additional paid orders");
        assertTrue(repository.findCodes(firstOrder).stream().allMatch(x->x.orderId()==firstOrder&&x.gtin().equals(FIRST_GTIN)));
        assertTrue(repository.findCodes(secondOrder).stream().allMatch(x->x.orderId()==secondOrder&&x.gtin().equals(SECOND_GTIN)));
        assertEquals(0,remote.blockReads);assertEquals(0,remote.replays);
    }

    @Test void aMixedSiblingPayloadIsRejectedAsAWholeEvenWhenTheSharedRemoteOrderIdMatches()throws Exception {
        remote.firstDelivery=block(FIRST_GTIN,"first-block",A1,B1);
        assertThrows(ZnackKizCodeService.RecoveryIdentityException.class,()->remote.service().download(original,firstOrder));
        assertTrue(rawCodes(firstOrder).isEmpty(),"The valid prefix of an invalid package must not enter inventory");
        assertTrue(rawCodes(secondOrder).isEmpty(),"A sibling GTIN cannot be silently diverted into another member");
        assertEquals(ZnackKizCodeService.DOWNLOAD_OUTCOME_UNCERTAIN,repository.findOrder(firstOrder).orElseThrow().errorMessage());
        assertTrue(Objects.toString(repository.findOrder(secondOrder).orElseThrow().errorMessage(),"").isBlank());
        assertEquals(1,remote.service().download(original,secondOrder));
        assertEquals(List.of(B1),rawCodes(secondOrder));
        assertEquals(OrderStatus.WAITING_CODES,repository.findOrder(firstOrder).orElseThrow().localStatus());
        assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(secondOrder).orElseThrow().localStatus());
    }

    @Test void uncertainMemberReplaysIssuedBlocksWithoutBlockingItsHealthySiblingOrRestoringConsumedCodes()throws Exception {
        repository.insertCodes(firstOrder,FIRST_GTIN,new DownloadedCodes(List.of(A1),"first-block"));
        repository.markCodes(firstOrder,KizLegalStatus.IN_CIRCULATION,null,null);
        var inventory=new ZnackGtinInventoryService();
        var reserved=inventory.reserveAvailable(1,FIRST_GTIN,1,"synthetic-reservation");
        assertEquals(1,inventory.consume(1,reserved));
        remote.timeoutFirst=true;
        assertThrows(SocketTimeoutException.class,()->remote.service().download(original,firstOrder));
        assertEquals(List.of(new Request(FIRST_GTIN,1)),remote.deliveries,
                "The fresh consuming GET must request only the member's durable remainder");
        assertEquals(1,remote.service().download(original,secondOrder),"A healthy sibling should continue independently");
        assertEquals(1,remote.service().download(original,firstOrder,0),"Zero fresh availability must still permit issued-block reconciliation");
        assertEquals(List.of(new Request(FIRST_GTIN,1),new Request(SECOND_GTIN,1)),remote.deliveries,
                "Recovery must never make another consuming GET for the uncertain member");
        assertEquals(1,remote.blockReads);assertEquals(1,remote.replays);
        assertEquals(List.of(A1,A2),rawCodes(firstOrder));assertEquals(List.of(B1),rawCodes(secondOrder));
        var retained=repository.findCodes(firstOrder).stream().filter(x->x.rawCode().equals(A1)).findFirst().orElseThrow();
        assertEquals(KizInventoryStatus.CONSUMED,retained.inventoryStatus());
        assertEquals(KizLegalStatus.IN_CIRCULATION,retained.legalStatus());
        assertEquals(KizLegalStatus.RECEIVED,repository.findCodes(firstOrder).stream().filter(x->x.rawCode().equals(A2)).findFirst().orElseThrow().legalStatus());
        assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(firstOrder).orElseThrow().localStatus());
        assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(secondOrder).orElseThrow().localStatus());
        assertEquals(0,inventory.availableCount(1,FIRST_GTIN),"Recovery cannot reissue a consumed code as available stock");
        assertEquals(0,remote.service().download(original,firstOrder),"A fully reconciled member is idempotent");
        assertEquals(2,remote.deliveries.size());
    }

    @Test void siblingIdentityInAnIssuedBlockListCannotRetargetAnUncertainMember()throws Exception {
        assertEquals(1,remote.service().download(original,secondOrder));
        repository.updateOrder(firstOrder,null,null,OrderStatus.WAITING_CODES,ZnackKizCodeService.DOWNLOAD_OUTCOME_UNCERTAIN);
        remote.foreignBlockList=true;
        assertThrows(ZnackKizCodeService.RecoveryIdentityException.class,()->remote.service().download(original,firstOrder));
        assertEquals(List.of(new Request(SECOND_GTIN,1)),remote.deliveries);
        assertEquals(1,remote.blockReads);assertEquals(0,remote.replays,"Validate the whole block-list identity before replay");
        assertTrue(rawCodes(firstOrder).isEmpty());assertEquals(List.of(B1),rawCodes(secondOrder));
        assertEquals(ZnackKizCodeService.DOWNLOAD_OUTCOME_UNCERTAIN,repository.findOrder(firstOrder).orElseThrow().errorMessage());
        assertEquals(OrderStatus.CODES_DOWNLOADED,repository.findOrder(secondOrder).orElseThrow().localStatus());
    }

    @Test void changedCallerConfigurationCannotRetargetThePaidMembersOmsAuthenticationOrDelivery()throws Exception {
        Settings changed=settings("different-caller");
        repository.saveSettings(changed);
        assertEquals(2,remote.service().download(changed,firstOrder));
        assertEquals(List.of(A1,A2),rawCodes(firstOrder));assertTrue(rawCodes(secondOrder).isEmpty());
        assertEquals(original,store.contextForOrder(firstOrder).settings());
        assertEquals(original,store.contextForOrder(secondOrder).settings());
        assertEquals(changed.signerCertificate(),repository.getSettings().signerCertificate());
        assertEquals(List.of(new Request(FIRST_GTIN,2)),remote.deliveries);
        assertEquals(1,remote.authentication);
    }

    private List<String> rawCodes(long id){return repository.findCodes(id).stream().map(KizCode::rawCode).toList();}
    private static String raw(String gtin,String serial){return "01"+gtin+"21"+serial+"\u001d91ABCD\u001d92synthetic-"+serial;}
    private static Settings settings(String prefix){return new Settings("https://"+prefix+".invalid","https://"+prefix+"-suz.invalid",prefix+"-oms",prefix+"-connection",
            "7700000001","","7700000001","",prefix+"-certificate","[]","","","",true,"","[]","{}",Instant.parse("2026-01-01T00:00:00Z"));}
    private static JsonObject block(String gtin,String blockId,String...codes) {
        JsonObject body=new JsonObject();body.addProperty("orderId",REMOTE_ID);body.addProperty("omsId","original-oms");body.addProperty("gtin",gtin);body.addProperty("blockId",blockId);
        JsonArray values=new JsonArray();for(String code:codes)values.add(code);body.add("codes",values);return body;
    }
    private record Request(String gtin,int quantity) { }
    private final class Remote extends ZnackApiClient {
        final List<Request> deliveries=new ArrayList<>();
        JsonElement firstDelivery=block(FIRST_GTIN,"first-block",A1,A2);
        boolean timeoutFirst,foreignBlockList;
        int authentication,blockReads,replays;
        @Override public JsonElement codes(String base,String token,String oms,String orderId,int quantity,String gtin)throws IOException {
            assertEquals(original.resolvedSuzBaseUrl(),base);assertEquals(original.omsId(),oms);assertEquals(REMOTE_ID,orderId);
            deliveries.add(new Request(gtin,quantity));
            if(gtin.equals(FIRST_GTIN)){if(timeoutFirst)throw new SocketTimeoutException("Synthetic lost consuming response");return firstDelivery.deepCopy();}
            assertEquals(SECOND_GTIN,gtin);return block(SECOND_GTIN,"second-block",B1);
        }
        @Override public JsonElement codeBlocks(String base,String token,String oms,String orderId,String gtin) {
            assertEquals(original.resolvedSuzBaseUrl(),base);assertEquals(original.omsId(),oms);assertEquals(REMOTE_ID,orderId);assertEquals(FIRST_GTIN,gtin);blockReads++;
            JsonObject body=new JsonObject();body.addProperty("orderId",REMOTE_ID);body.addProperty("omsId",original.omsId());body.addProperty("gtin",foreignBlockList?SECOND_GTIN:FIRST_GTIN);
            JsonObject issued=new JsonObject();issued.addProperty("blockId","first-block");issued.addProperty("quantity",2);JsonArray blocks=new JsonArray();blocks.add(issued);body.add("blocks",blocks);return body;
        }
        @Override public JsonElement retryCodeBlock(String base,String token,String oms,String blockId) {
            assertEquals(original.resolvedSuzBaseUrl(),base);assertEquals(original.omsId(),oms);assertEquals("first-block",blockId);replays++;
            return block(FIRST_GTIN,"first-block",A1,A2);
        }
        ZnackKizCodeService service() {
            var auth=new ZnackAuthService(this,ZnackSignatureProvider.unconfigured()) {
                @Override public String suzToken(Settings settings){authentication++;assertEquals(original,settings,"Token dispatch must use the member's original paid identity");return "synthetic-token";}
            };
            return new ZnackKizCodeService(this,auth,repository);
        }
    }
}
