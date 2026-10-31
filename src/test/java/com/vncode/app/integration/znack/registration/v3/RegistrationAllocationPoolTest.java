package com.vncode.app.integration.znack.registration.v3;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.List;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationAllocationPoolTest {
    @TempDir Path temp;
    private RegistrationCommandJournal journal;
    private final Owner wb=new Owner(1,"wb-account","production","certificate-wb");
    private final Owner ozon=new Owner(2,"ozon-account","production","certificate-ozon");
    private final String inn="1234567890";
    private String pool;
    private final RegistrationCommandJournal.Command wbCommand=new RegistrationCommandJournal.Command(Action.ALLOCATE_GTIN,"101/11","generation-wb");
    private final RegistrationCommandJournal.Command ozonCommand=new RegistrationCommandJournal.Command(Action.ALLOCATE_GTIN,RegistrationCommandJournal.ozonAllocationResource("991","shirt"),"generation-ozon");

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture'),(2,'Ozon','OZON','fixture')");
        }
        pool=RegistrationCommandJournal.allocationPool(inn,ZnackModels.PRODUCTION_TRUE_API);
        journal=new RegistrationCommandJournal();
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void unknownOzonJournalBlocksWbAcrossRestartAndCertificateChange()throws Exception {
        assertThrows(SocketTimeoutException.class,()->journal.run(ozon,pool,List.of(ozonCommand),sent->{sent.run();throw new SocketTimeoutException();}));
        assertThrows(IllegalStateException.class,()->new RegistrationCommandJournal().run(wb,pool,List.of(wbCommand),sent->{fail("duplicate allocation");return List.of();}));
        var rotated=new Owner(1,"new-account","production","new-certificate");
        assertThrows(IllegalStateException.class,()->new RegistrationCommandJournal().run(rotated,pool,List.of(wbCommand),sent->{fail();return List.of();}));
    }
    @Test void unknownWbJournalBlocksOzonAcrossRestart()throws Exception {
        assertThrows(SocketTimeoutException.class,()->journal.run(wb,pool,List.of(wbCommand),sent->{sent.run();throw new SocketTimeoutException();}));
        assertThrows(IllegalStateException.class,()->new RegistrationCommandJournal().run(ozon,pool,List.of(ozonCommand),sent->{fail();return List.of();}));
    }
    @Test void legacyOzonUnknownBlocksWbForSameParticipantButNotAnother()throws Exception {
        legacyOzon("{\"inn\":\"1234567890\",\"environment\":\""+ZnackModels.PRODUCTION_TRUE_API+"\",\"fingerprint\":\"AA\"}");
        assertThrows(IllegalStateException.class,()->journal.run(wb,pool,List.of(wbCommand),sent->{fail();return List.of();}));
        String other=RegistrationCommandJournal.allocationPool("9876543210",ZnackModels.PRODUCTION_TRUE_API);
        assertEquals(List.of("04631993764370"),journal.run(wb,other,List.of(wbCommand),sent->{sent.run();return List.of("04631993764370");}));
    }
    @Test void malformedLegacyScopeFailsClosedInsteadOfAssumingAnotherParticipant()throws Exception {
        legacyOzon("{}");
        assertThrows(IllegalStateException.class,()->journal.run(wb,pool,List.of(wbCommand),sent->{fail();return List.of();}));
    }
    @Test void legacyWbUnknownBlocksOzonButTheCurrentWbCheckpointIsNotItsOwnConflict()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,status,created_at,updated_at) VALUES(1,11,101,'ALLOCATING_GTIN','now','now')");
            try(var p=c.prepareStatement("INSERT INTO znack_card_registration_scopes(shop_id,chrt_id,participant_inn,environment,fingerprint,created_at) VALUES(1,11,?,?,?,'now')")){
                p.setString(1,inn);p.setString(2,ZnackModels.PRODUCTION_TRUE_API);p.setString(3,"fixture");p.executeUpdate();
            }
        }
        assertThrows(IllegalStateException.class,()->journal.run(ozon,pool,List.of(ozonCommand),sent->{fail();return List.of();}));
        assertEquals(List.of("04631993764370"),journal.run(wb,pool,List.of(wbCommand),sent->{sent.run();return List.of("04631993764370");}));
    }
    private void legacyOzon(String scope)throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,scope_json,owner,created_at,updated_at) VALUES(2,'991','shirt','1','GTIN_REVIEW_REQUIRED',?,'owner','now','now')")){
            p.setString(1,scope);p.executeUpdate();
        }
    }
}
