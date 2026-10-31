package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogRepairLedgerStateTest {
    static final String GTIN="04601234567893";
    @TempDir Path root;Shop shop;ZnackRegistrationScope scope;
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private CatalogAutomaticRepair.Proposal proposal(String value){
        var before=new JsonObject();before.addProperty("gtin",GTIN);before.addProperty("good_id",91);before.addProperty("value","old");
        var after=before.deepCopy();after.addProperty("value",value);
        return new CatalogAutomaticRepair.Proposal(before,after,List.of(new CatalogAutomaticRepair.Change("value","old",value,"fixture")),"CATALOG_REJECTED: fixture",91);
    }
    private boolean reserve(String feed,CatalogAutomaticRepair.Proposal p)throws Exception{return SqliteWriteTransaction.run(c->CatalogAutomaticRepairLedger.reserve(c,scope,GTIN,feed,p));}
    private String state(String feed){return CatalogAutomaticRepairLedger.history(shop,GTIN,91).stream().filter(r->r.rejectedFeed().equals(feed)).findFirst().orElseThrow().state();}

    @Test void noSendFailureReleasesBudgetAndSamePayloadCanResume()throws Exception{
        var p=proposal("first");assertTrue(reserve("f1",p));
        CatalogAutomaticRepairLedger.preSendFailure(shop,scope,p.payload(),false);assertEquals("PRE_SEND_FAILED",state("f1"));
        assertTrue(CatalogAutomaticRepairLedger.sending(shop,scope,p.payload()));assertEquals("SENDING",state("f1"));
        CatalogAutomaticRepairLedger.finished(shop,scope,p.payload(),"ACKNOWLEDGED");assertEquals("ACKNOWLEDGED",state("f1"));
        assertThrows(IllegalStateException.class,()->CatalogAutomaticRepairLedger.sending(shop,scope,p.payload()));
    }
    @Test void rateLimitUsesSameCorrectionAndUnknownNeverReplays()throws Exception{
        var p=proposal("first");assertTrue(reserve("f1",p));assertTrue(CatalogAutomaticRepairLedger.sending(shop,scope,p.payload()));
        CatalogAutomaticRepairLedger.preSendFailure(shop,scope,p.payload(),false);assertEquals("SENDING",state("f1"),"Another failed attempt must not release an in-flight send");
        CatalogAutomaticRepairLedger.finished(shop,scope,p.payload(),"WAITING");assertEquals("WAITING",state("f1"));
        assertTrue(CatalogAutomaticRepairLedger.sending(shop,scope,p.payload()));
        CatalogAutomaticRepairLedger.finished(shop,scope,p.payload(),"UNKNOWN");assertEquals("UNKNOWN",state("f1"));
        assertThrows(IllegalStateException.class,()->CatalogAutomaticRepairLedger.sending(shop,scope,p.payload()));
        assertTrue(reserve("f2",proposal("second")));assertFalse(reserve("f3",proposal("third")));
    }
    @Test void abandonedLocalValidationDoesNotConsumeTwoRoundLimit()throws Exception{
        var first=proposal("first");assertTrue(reserve("f1",first));CatalogAutomaticRepairLedger.preSendFailure(shop,scope,first.payload(),false);
        assertTrue(reserve("f2",proposal("second")));assertTrue(reserve("f3",proposal("third")));
        assertFalse(reserve("f4",proposal("fourth")));
        assertThrows(IllegalStateException.class,()->CatalogAutomaticRepairLedger.sending(shop,scope,first.payload()));
    }
}
