package com.vncode.app.features.gs1;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.vncode.app.features.gs1.Gs1ApplicationStatus.State.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1ApplicationCacheTest {
    @TempDir Path root;Shop shop;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void reset(){System.clearProperty("vncode.appdata.dir");}
    private Gs1Membership member(){return new Gs1Membership("7700000000","Synthetic",false,false,null,List.of(),Instant.now(),Gs1Membership.CATALOG_SOURCE);}
    private Gs1AutomaticSyncService service(Gs1ApplicationStatus status){return new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(member(),"source@example.invalid",List.of(),"","",true,false,status));}
    private Gs1ApplicationStatus rejected(){return new Gs1ApplicationStatus(REJECTED,"Applicant position must be corrected");}
    @Test void rejectionAndItsReasonSurviveDatabaseReopenAndReturnAnEditableForm()throws Exception{
        service(rejected()).refresh(shop);Database.initDatabase();var cached=new Gs1AutomaticSyncService().cached(shop).orElseThrow();
        assertEquals(rejected(),cached.application());assertEquals(Boolean.TRUE,cached.applicationSent(),"Legacy bit remains intact for old readers");
        var decision=Gs1ActionPolicy.evaluate(cached.membership(),cached.application().form(cached.inn(),cached.email()),List.of(),new GtinAvailability(OptionalLong.empty(),false,0,0),LocalDate.now(),Instant.now(),cached.newApplication());
        assertEquals(Gs1ActionPolicy.JoinAction.JOIN,decision.joinAction());
    }
    @Test void sourceReadFailuresKeepTheLastGoodScopedRejectionAndDoNotFreshenItsTimestamp()throws Exception{
        service(rejected()).refresh(shop);var before=new Gs1AutomaticSyncService().cached(shop).orElseThrow();
        var failed=new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(null,"",List.of(),"NETWORK_TIMEOUT","NETWORK_DNS"));failed.refresh(shop);
        assertEquals(rejected(),failed.cached(shop).orElseThrow().application());assertEquals(before.catalogCheckedAt(),failed.cached(shop).orElseThrow().catalogCheckedAt());
        failed.failed(shop,RegisteredGtinProof.source(shop),"NETWORK_TIMEOUT");assertEquals(rejected(),failed.cached(shop).orElseThrow().application());
    }
    @Test void certificateScopeChangeCannotExposeOrCarryOverTheEarlierRejection()throws Exception{
        service(rejected()).refresh(shop);GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        assertTrue(new Gs1AutomaticSyncService().cached(shop).isEmpty());
        var failed=new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(null,"",List.of(),"NETWORK_TIMEOUT","NETWORK_DNS"));failed.refresh(shop);
        assertEquals(UNKNOWN,failed.cached(shop).orElseThrow().application().state());
    }
    @Test void successfulUnknownReadClearsPriorRejectionInsteadOfUsingTheLegacySentBit()throws Exception{
        service(rejected()).refresh(shop);service(Gs1ApplicationStatus.unknown()).refresh(shop);var cached=new Gs1AutomaticSyncService().cached(shop).orElseThrow();
        assertEquals(UNKNOWN,cached.application().state());assertNull(cached.application().form(cached.inn(),cached.email()));assertEquals(Boolean.TRUE,cached.applicationSent());
    }
    @Test void oldWriterTimestampChangeInvalidatesTheNewStateWhileKeepingItsLegacyFields()throws Exception{
        service(rejected()).refresh(shop);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE gs1_sync_status SET catalog_checked_at=?,application_sent=1 WHERE shop_id=?")){p.setString(1,Instant.now().plusSeconds(1).toString());p.setInt(2,shop.getId());assertEquals(1,p.executeUpdate());}
        var cached=new Gs1AutomaticSyncService().cached(shop).orElseThrow();assertEquals(UNKNOWN,cached.application().state());assertEquals(Boolean.TRUE,cached.applicationSent());
    }
    @Test void legacyOrMalformedSidecarRequiresFreshStatusAndNeverDiscardsMembership()throws Exception{
        service(rejected()).refresh(shop);
        for(String sidecar:Arrays.asList(null,"[]","{\"state\":\"SUBMITTED\"}")){
            try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE gs1_sync_status SET application_status_json=? WHERE shop_id=?")){p.setString(1,sidecar);p.setInt(2,shop.getId());p.executeUpdate();}
            var cached=new Gs1AutomaticSyncService().cached(shop).orElseThrow();assertEquals(UNKNOWN,cached.application().state());assertNotNull(cached.membership());assertEquals("source@example.invalid",cached.email());
        }
    }
    @Test void additiveCacheUpgradePreservesOldRowsAndSchemaVersionAndVerifiedBackup()throws Exception{
        Path old=root.resolve("old-gs1.db");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+old);var s=c.createStatement()){
            s.execute("PRAGMA user_version=7");s.execute("CREATE TABLE gs1_sync_status(shop_id INTEGER PRIMARY KEY,owner TEXT NOT NULL,registry_source TEXT NOT NULL,inn TEXT NOT NULL,profile_email TEXT NOT NULL DEFAULT '',checked_at TEXT NOT NULL,catalog_error TEXT NOT NULL DEFAULT '',balance_error TEXT NOT NULL DEFAULT '',application_sent INTEGER,application_new INTEGER NOT NULL DEFAULT 0)");
            s.execute("INSERT INTO gs1_sync_status VALUES(1,'owner','source','7700000000','source@example.invalid','2026-11-17T12:00:00Z','','',1,0)");
        }
        Path backup=root.resolve("old-gs1-backup.db");Files.copy(old,backup);byte[] original=Files.readAllBytes(backup);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+old);var s=c.createStatement()){
            Gs1AutomaticSyncService.initialize(c);Gs1AutomaticSyncService.initialize(c);
            try(var rows=s.executeQuery("SELECT profile_email,application_sent,application_status_json FROM gs1_sync_status")){assertTrue(rows.next());assertEquals("source@example.invalid",rows.getString(1));assertEquals(1,rows.getInt(2));assertNull(rows.getString(3));assertFalse(rows.next());}
            try(var version=s.executeQuery("PRAGMA user_version")){assertTrue(version.next());assertEquals(7,version.getInt(1));}
            try(var integrity=s.executeQuery("PRAGMA integrity_check")){assertTrue(integrity.next());assertEquals("ok",integrity.getString(1));}
        }
        assertArrayEquals(original,Files.readAllBytes(backup));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+backup);var s=c.createStatement()){try(var rows=s.executeQuery("SELECT profile_email,application_sent FROM gs1_sync_status")){assertTrue(rows.next());assertEquals("source@example.invalid",rows.getString(1));assertEquals(1,rows.getInt(2));}}
    }
}
