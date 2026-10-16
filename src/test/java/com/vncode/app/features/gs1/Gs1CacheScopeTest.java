package com.vncode.app.features.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class Gs1CacheScopeTest {
    @TempDir Path directory;Shop shop;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    private Gs1Membership member(boolean active){return new Gs1Membership("7700000000",active?"Current owner":"Other observation",active,false,LocalDate.now().plusYears(1),List.of(new Gs1Membership.Prefix("4601234",50,List.of())),Instant.now(),Gs1Membership.CATALOG_SOURCE);}
    private BalanceSnapshot cash(String amount){return new BalanceSnapshot(new BalanceScope("7700000000","production","2","1"),new BigDecimal(amount),"RUB",Instant.now(),URI.create("https://markirovka.crpt.ru/bff-elk/v1/organisation/balance"));}
    private void save(Shop owner,Gs1Membership member,List<BalanceSnapshot> cash)throws Exception{new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(member,"source@example.invalid",cash,"","")).refresh(owner);}
    @Test void oneShopCannotReadAnotherShopsObservationEvenWithTheSameInn()throws Exception{
        save(shop,member(true),List.of(cash("100.00")));var second=GtinWriteTestFixture.shop(Marketplace.OZON);save(second,member(false),List.of(cash("1.00")));
        var first=new Gs1AutomaticSyncService().cached(shop).orElseThrow();assertTrue(first.membership().member());assertEquals(new BigDecimal("100.00"),first.balances().getFirst().amount());assertFalse(new Gs1AutomaticSyncService().cached(second).orElseThrow().membership().member());
    }
    @Test void partialFailureKeepsOnlyTheSameScopesLastGoodValuesAndTheirTimes()throws Exception{
        save(shop,member(true),List.of(cash("100.00")));var before=new Gs1AutomaticSyncService().cached(shop).orElseThrow();
        new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(null,"",List.of(),"NETWORK_TIMEOUT","NETWORK_DNS")).refresh(shop);
        var after=new Gs1AutomaticSyncService().cached(shop).orElseThrow();assertEquals(before.membership(),after.membership());assertEquals(before.balances(),after.balances());assertEquals(before.catalogCheckedAt(),after.catalogCheckedAt());assertEquals(before.balanceCheckedAt(),after.balanceCheckedAt());assertEquals("NETWORK_TIMEOUT",after.catalogError());
    }
    @Test void aSuccessfulEmptyAccountListClearsOldBalancesRatherThanKeepingGhostAccounts()throws Exception{save(shop,member(true),List.of(cash("100.00")));save(shop,member(true),List.of());assertTrue(new Gs1AutomaticSyncService().cached(shop).orElseThrow().balances().isEmpty());}
    @Test void certificateRotationDoesNotReuseTheOldObservationsOnANewFailure()throws Exception{
        save(shop,member(true),List.of(cash("100.00")));GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);assertTrue(new Gs1AutomaticSyncService().cached(shop).isEmpty());
        new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(null,"",List.of(),"NETWORK_TIMEOUT","NETWORK_DNS")).refresh(shop);
        var current=new Gs1AutomaticSyncService().cached(shop).orElseThrow();assertNull(current.membership());assertTrue(current.balances().isEmpty());assertEquals("",current.email());assertNull(current.catalogCheckedAt());assertNull(current.balanceCheckedAt());
    }
    @Test void addedColumnsMigrateAnOldScopedStatusTableWithoutChangingOldRecords()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("DROP TABLE gs1_sync_status");s.execute("CREATE TABLE gs1_sync_status(shop_id INTEGER PRIMARY KEY,owner TEXT NOT NULL,registry_source TEXT NOT NULL,inn TEXT NOT NULL,profile_email TEXT NOT NULL DEFAULT '',checked_at TEXT NOT NULL,catalog_error TEXT NOT NULL DEFAULT '',balance_error TEXT NOT NULL DEFAULT '',application_sent INTEGER,application_new INTEGER NOT NULL DEFAULT 0)");s.execute("INSERT INTO gs1_sync_status VALUES(1,'old','old','7700000000','old@example.invalid','2026-10-01T00:00:00Z','','',1,0)");Gs1AutomaticSyncService.initialize(c);
            try(var rows=s.executeQuery("SELECT profile_email,application_sent,membership_json,balances_json FROM gs1_sync_status")){assertTrue(rows.next());assertEquals("old@example.invalid",rows.getString(1));assertEquals(1,rows.getInt(2));assertNull(rows.getString(3));assertNull(rows.getString(4));}
        }
    }
}
