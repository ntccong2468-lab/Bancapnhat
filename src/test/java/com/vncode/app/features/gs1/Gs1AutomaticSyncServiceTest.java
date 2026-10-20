package com.vncode.app.features.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1AutomaticSyncServiceTest {
    @TempDir Path root;Shop shop;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private Gs1Membership member(String inn){return new Gs1Membership(inn,"Synthetic enterprise",true,false,LocalDate.now().plusYears(1),List.of(new Gs1Membership.Prefix("4601234",50,List.of())),Instant.now(),Gs1Membership.CATALOG_SOURCE);}
    @Test void readsConfiguredScopeAndSavesMembershipAndProfileWithoutBrowserOrManualLogin()throws Exception{
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var service=new Gs1AutomaticSyncService((s,settings,scope)->{calls.incrementAndGet();assertEquals(shop.getId(),s.getId());assertEquals("7700000000",scope.inn());assertEquals(GtinWriteTestFixture.CERTIFICATE,scope.fingerprint());return new Gs1AutomaticSyncService.Result(member(scope.inn()),"synthetic@example.invalid",List.of(),"","");});
        assertTrue(service.refresh(shop).complete());assertEquals(1,calls.get());assertEquals("synthetic@example.invalid",service.cached(shop).orElseThrow().email());assertTrue(new Gs1Repository().membership("7700000000","production").isPresent());
    }
    @Test void failedLookupKeepsPreviousMembershipAndMarksFailureInsteadOfPretendingEmptyAccount()throws Exception{
        new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(member(scope.inn()),"synthetic@example.invalid",List.of(),"","")).refresh(shop);
        var service=new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(null,"",List.of(),"NETWORK_TIMEOUT","NETWORK_DNS"));
        assertFalse(service.refresh(shop).complete());assertEquals("NETWORK_TIMEOUT",service.cached(shop).orElseThrow().catalogError());assertEquals("synthetic@example.invalid",service.cached(shop).orElseThrow().email());assertTrue(new Gs1Repository().membership("7700000000","production").isPresent());
    }
    @Test void anotherEnterpriseCannotOverwriteMembershipOrBalanceCache(){
        var service=new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(member("7800000000"),"",List.of(),"",""));
        assertThrows(SecurityException.class,()->service.refresh(shop));assertTrue(new Gs1Repository().membership("7800000000","production").isEmpty());assertTrue(service.cached(shop).isEmpty());
    }
    @Test void certificateChangeDuringRequestCannotPublishOldAccountData(){
        var service=new Gs1AutomaticSyncService((s,settings,scope)->{GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);return new Gs1AutomaticSyncService.Result(member(scope.inn()),"",List.of(),"","");});
        assertThrows(IllegalStateException.class,()->service.refresh(shop));assertTrue(service.cached(shop).isEmpty());
    }
    @Test void profileEmailUsesOnlySourcedProfileAndRejectsControlCharacters(){
        assertEquals("actual@example.invalid",Gs1AutomaticSyncService.profileEmail(com.google.gson.JsonParser.parseString("{\"applicant\":{\"email\":\"actual@example.invalid\"},\"apiKey\":\"DO-NOT-READ\"}").getAsJsonObject()));
        assertEquals("",Gs1AutomaticSyncService.safeEmail("email@example.invalid\nsecret"));assertEquals("",Gs1AutomaticSyncService.profileEmail(new com.google.gson.JsonObject()));
    }
}
