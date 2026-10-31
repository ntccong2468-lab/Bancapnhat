package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic owners, SQLite checkpoints and no live Catalog requests. */
public class CatalogSubmissionGuardTest {
    @TempDir Path root;
    private Shop wb,ozon;private ProductKey wbKey,ozonKey;private JsonObject payload;
    private static final String GTIN=GtinWriteTestFixture.GTIN;

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        wb=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);ozon=GtinWriteTestFixture.shop(Marketplace.OZON);
        wbKey=new ProductKey(1,Marketplace.WILDBERRIES,"101","11");ozonKey=new ProductKey(2,Marketplace.OZON,"501","offer");
        payload=new JsonObject();payload.addProperty("gtin",GTIN);
        try(var c=Database.getConnection()){
            try(var p=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,nm_id,chrt_id,gtin,payload_json,status,created_at,updated_at) VALUES(1,101,11,?,?,'READY_TO_SUBMIT','now','now')")){
                p.setString(1,GTIN);p.setString(2,payload.toString());p.executeUpdate();
            }
            try(var p=c.prepareStatement("INSERT INTO znack_card_registration_scopes(shop_id,chrt_id,participant_inn,environment,fingerprint,owner_hash,created_at) VALUES(1,11,?,?,?,?, 'now')")){
                var scope=scope(wb);p.setString(1,scope.inn());p.setString(2,scope.environment());p.setString(3,scope.fingerprint());p.setString(4,ListingIdentity.fingerprint(wb));p.executeUpdate();
            }
            try(var p=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,gtin,payload_json,scope_json,owner,created_at,updated_at) VALUES(2,'501','offer','9001','READY_TO_SUBMIT',?,?,?,?, 'now','now')")){
                p.setString(1,GTIN);p.setString(2,payload.toString());p.setString(3,new Gson().toJson(scope(ozon)));p.setString(4,ListingIdentity.fingerprint(ozon));p.executeUpdate();
            }
        }
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private static ZnackRegistrationScope scope(Shop shop){return ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());}
    private CatalogSubmissionGuard guard(Shop shop,ProductKey key,JsonObject body,CatalogSubmissionGuard.FeedReader reader)throws Exception{return CatalogSubmissionGuard.prepare(shop,scope(shop),key,body,reader);}
    private static long never(List<String> feeds,String code,Long good){fail("unexpected feed read");return 0;}

    @Test void pendingOrUnknownOtherMarketplaceBlocksAndNeverMarksOwnSending()throws Exception {
        for(String state:List.of("FEED_SUBMITTING","FEED_REVIEW_REQUIRED","FEED_SUBMITTED","SIGNING")){
            update("UPDATE znack_card_registrations SET status='"+state+"',feed_id='' WHERE shop_id=1");
            assertThrows(IllegalStateException.class,()->guard(ozon,ozonKey,payload,CatalogSubmissionGuardTest::never),state);
            assertEquals("READY_TO_SUBMIT",status("ozon_card_registrations",2));
        }
    }
    @Test void repairRequiresTerminalExactFeedProofAndRechecksNativeRevision()throws Exception {
        update("UPDATE znack_card_registrations SET status='ERROR',feed_id='feed-old',good_id=91 WHERE shop_id=1");
        payload.addProperty("good_id",91);setOzonPayload(payload);
        assertThrows(IllegalStateException.class,()->guard(ozon,ozonKey,payload,(f,g,id)->0));
        assertThrows(java.io.IOException.class,()->guard(ozon,ozonKey,payload,(f,g,id)->{throw new java.io.IOException("404");}));
        var guard=guard(ozon,ozonKey,payload,(feeds,gtin,id)->{assertEquals(List.of("feed-old"),feeds);assertEquals(GTIN,gtin);assertEquals(91L,id);return System.nanoTime();});
        update("UPDATE znack_card_registrations SET updated_at='changed' WHERE shop_id=1");
        assertThrows(IllegalStateException.class,guard::reserve);
        assertEquals("READY_TO_SUBMIT",status("ozon_card_registrations",2));
        var fresh=guard(ozon,ozonKey,payload,(f,g,id)->System.nanoTime());fresh.reserve();
        assertEquals("FEED_SUBMITTING",status("ozon_card_registrations",2));
    }
    @Test void creationNeverDuplicatesAnAlreadyLinkedOtherCard()throws Exception {
        update("UPDATE znack_card_registrations SET status='PUBLISHED',feed_id='feed',good_id=91 WHERE shop_id=1");
        assertThrows(IllegalStateException.class,()->guard(ozon,ozonKey,payload,CatalogSubmissionGuardTest::never));
        assertEquals("READY_TO_SUBMIT",status("ozon_card_registrations",2));
    }
    @Test void unknownSignatureBlocksCrossMarketplaceEditAndPendingFeedBlocksSigning()throws Exception {
        payload.addProperty("good_id",91);setOzonPayload(payload);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_card_registration_signatures(shop_id,chrt_id,gtin,good_id,document_xml,document_sha256,state,created_at,updated_at) VALUES(1,11,?,91,?,?,'RECONCILE_REQUIRED','now','now')")){
            p.setString(1,GTIN);p.setBytes(2,new byte[]{1});p.setString(3,"a".repeat(64));p.executeUpdate();
        }
        assertThrows(IllegalStateException.class,()->guard(ozon,ozonKey,payload,CatalogSubmissionGuardTest::never));
        update("DELETE FROM znack_card_registration_signatures WHERE shop_id=1");
        update("UPDATE znack_card_registrations SET status='FEED_REVIEW_REQUIRED',good_id=91 WHERE shop_id=1");
        assertThrows(IllegalStateException.class,()->CatalogSubmissionGuard.prepareSigning(ozon,scope(ozon),ozonKey,GTIN,91,CatalogSubmissionGuardTest::never));
        assertEquals("READY_TO_SUBMIT",status("ozon_card_registrations",2));
    }
    @Test void existingHistoryWithoutScopeStillRequiresReadbackForRepair()throws Exception {
        update("DELETE FROM znack_card_registrations WHERE shop_id=1");
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_card_registration_history(shop_id,chrt_id,nm_id,gtin,good_id,feed_id,status,archived_at) VALUES(1,11,101,?,91,'old-feed','ERROR','now')")){p.setString(1,GTIN);p.executeUpdate();}
        assertThrows(IllegalStateException.class,()->guard(ozon,ozonKey,payload,CatalogSubmissionGuardTest::never));
        payload.addProperty("good_id",91);setOzonPayload(payload);
        assertThrows(IllegalStateException.class,()->guard(ozon,ozonKey,payload,(f,g,id)->0));
        guard(ozon,ozonKey,payload,(f,g,id)->System.nanoTime()).reserve();
        assertEquals("FEED_SUBMITTING",status("ozon_card_registrations",2));
    }
    @Test void differentParticipantsDoNotShareThePendingLock()throws Exception {
        update("UPDATE znack_card_registration_scopes SET participant_inn='8888888888' WHERE shop_id=1");
        update("UPDATE znack_card_registrations SET status='FEED_SUBMITTING' WHERE shop_id=1");
        guard(ozon,ozonKey,payload,CatalogSubmissionGuardTest::never).reserve();
        assertEquals("FEED_SUBMITTING",status("ozon_card_registrations",2));
    }
    @Test void changedNativeOwnerOrScopeCannotClaimTheSend()throws Exception {
        var ozonPrepared=guard(ozon,ozonKey,payload,CatalogSubmissionGuardTest::never);
        update("UPDATE ozon_card_registrations SET owner='changed-owner' WHERE shop_id=2");
        assertThrows(IllegalStateException.class,ozonPrepared::reserve);assertEquals("READY_TO_SUBMIT",status("ozon_card_registrations",2));
        var wbPrepared=guard(wb,wbKey,payload,CatalogSubmissionGuardTest::never);
        update("UPDATE znack_card_registration_scopes SET fingerprint='changed' WHERE shop_id=1");
        assertThrows(IllegalStateException.class,wbPrepared::reserve);assertEquals("READY_TO_SUBMIT",status("znack_card_registrations",1));
    }
    @Test void twoProcessesCannotSendTheSameGtinConcurrently()throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var first=new ProcessBuilder(executable,"-Dvncode.appdata.dir="+root,"-cp",classpath,getClass().getName(),"wb",root.toString()).redirectErrorStream(true).start();
        var second=new ProcessBuilder(executable,"-Dvncode.appdata.dir="+root,"-cp",classpath,getClass().getName(),"ozon",root.toString()).redirectErrorStream(true).start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while((!Files.exists(root.resolve("ready-wb"))||!Files.exists(root.resolve("ready-ozon")))&&System.nanoTime()<deadline)Thread.sleep(10);
        assertTrue(Files.exists(root.resolve("ready-wb")));assertTrue(Files.exists(root.resolve("ready-ozon")));Files.writeString(root.resolve("go"),"go");
        assertTrue(first.waitFor(25,TimeUnit.SECONDS));assertTrue(second.waitFor(25,TimeUnit.SECONDS));
        String a=new String(first.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),b=new String(second.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,first.exitValue(),a);assertEquals(0,second.exitValue(),b);
        assertEquals(1,(a.contains("RESERVED=true")?1:0)+(b.contains("RESERVED=true")?1:0));
        assertEquals(1,(status("znack_card_registrations",1).equals("FEED_SUBMITTING")?1:0)+(status("ozon_card_registrations",2).equals("FEED_SUBMITTING")?1:0));
    }
    @Test void wbSignatureAndOzonRepairCannotStartTogether()throws Exception { raceSigningAgainstEdit(true); }
    @Test void ozonSignatureAndWbRepairCannotStartTogether()throws Exception { raceSigningAgainstEdit(false); }
    private void raceSigningAgainstEdit(boolean wbSigns)throws Exception {
        payload.addProperty("good_id",91);String hash="a".repeat(64);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_card_registrations SET payload_json=?,good_id=91,status=? WHERE shop_id=1")){
            p.setString(1,payload.toString());p.setString(2,wbSigns?"READY_TO_SIGN":"READY_TO_SUBMIT");p.executeUpdate();
        }
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_card_registrations SET payload_json=?,good_id=91,status=?,signature_state=?,signature_sha256=? WHERE shop_id=2")){
            p.setString(1,payload.toString());p.setString(2,wbSigns?"READY_TO_SUBMIT":"READY_TO_SIGN");p.setString(3,wbSigns?"":"PREPARED");p.setString(4,wbSigns?"":hash);p.executeUpdate();
        }
        if(wbSigns)try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_card_registration_signatures(shop_id,chrt_id,gtin,good_id,document_xml,document_sha256,state,created_at,updated_at) VALUES(1,11,?,91,?,?,'PREPARED','now','now')")){
            p.setString(1,GTIN);p.setBytes(2,new byte[]{1});p.setString(3,hash);p.executeUpdate();
        }
        String executable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String first=wbSigns?"wb-sign":"ozon-sign",second=wbSigns?"ozon-edit":"wb-edit";
        var a=new ProcessBuilder(executable,"-Dvncode.appdata.dir="+root,"-cp",classpath,getClass().getName(),first,root.toString()).redirectErrorStream(true).start();
        var b=new ProcessBuilder(executable,"-Dvncode.appdata.dir="+root,"-cp",classpath,getClass().getName(),second,root.toString()).redirectErrorStream(true).start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while((!Files.exists(root.resolve("ready-"+first))||!Files.exists(root.resolve("ready-"+second)))&&System.nanoTime()<deadline)Thread.sleep(10);
        assertTrue(Files.exists(root.resolve("ready-"+first)));assertTrue(Files.exists(root.resolve("ready-"+second)));Files.writeString(root.resolve("go"),"go");
        assertTrue(a.waitFor(25,TimeUnit.SECONDS));assertTrue(b.waitFor(25,TimeUnit.SECONDS));
        String one=new String(a.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),two=new String(b.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,a.exitValue(),one);assertEquals(0,b.exitValue(),two);
        assertEquals(1,(one.contains("RESERVED=true")?1:0)+(two.contains("RESERVED=true")?1:0));
    }
    public static void main(String[] args)throws Exception {
        String who=args[0];Path root=Path.of(args[1]);boolean isWb=who.startsWith("wb"),signing=who.endsWith("-sign"),editing=who.endsWith("-edit");
        Shop shop=new Shop(isWb?1:2,"Fixture",isWb?Marketplace.WILDBERRIES:Marketplace.OZON,isWb?null:"fixture-client","fixture-token");
        ProductKey key=new ProductKey(shop.getId(),shop.getMarketplace(),isWb?"101":"501",isWb?"11":"offer");
        JsonObject body=new JsonObject();body.addProperty("gtin",GTIN);if(editing)body.addProperty("good_id",91);
        var prepared=signing?CatalogSubmissionGuard.prepareSigning(shop,scope(shop),key,GTIN,91,CatalogSubmissionGuardTest::never)
                :CatalogSubmissionGuard.prepare(shop,scope(shop),key,body,CatalogSubmissionGuardTest::never);
        Files.writeString(root.resolve("ready-"+who),"ready");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!Files.exists(root.resolve("go"))&&System.nanoTime()<until)Thread.sleep(10);
        try{boolean reserved=signing?prepared.reserveSigning("a".repeat(64)):reserve(prepared);System.out.println("RESERVED="+reserved);}catch(IllegalStateException blocked){System.out.println("RESERVED=false");}
    }
    private static boolean reserve(CatalogSubmissionGuard prepared){prepared.reserve();return true;}
    private static void update(String sql)throws Exception{try(var c=Database.getConnection();var s=c.createStatement()){s.execute(sql);}}
    private static String status(String table,int shop)throws Exception{try(var c=Database.getConnection();var p=c.prepareStatement("SELECT status FROM "+table+" WHERE shop_id=?")){p.setInt(1,shop);try(var r=p.executeQuery()){assertTrue(r.next());return r.getString(1);}}}
    private static void setOzonPayload(JsonObject body)throws Exception{try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_card_registrations SET payload_json=? WHERE shop_id=2")){p.setString(1,body.toString());p.executeUpdate();}}
}
