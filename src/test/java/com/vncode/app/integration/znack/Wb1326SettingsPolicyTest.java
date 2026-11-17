package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.integration.znack.ZnackModels.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** New effective preference versus historical consent; only synthetic SQLite data. */
class Wb1326SettingsPolicyTest {
    @TempDir Path directory;
    ZnackRepository repository;
    Settings original;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Synthetic policy','fixture')");}
        repository=new ZnackRepository(new ShopContext(1,"Synthetic policy"));
        String fingerprint="A".repeat(40);Instant now=Instant.now();
        String metadata="{\"inn\":\"7700000001\",\"thumbprint\":\""+fingerprint+"\",\"hasPrivateKey\":true,\"validFrom\":\""+now.minusSeconds(86400)+"\",\"validTo\":\""+now.plusSeconds(86400*365L)+"\"}";
        original=new Settings("","","fixture-oms","fixture-connection","7700000001","7700000001","7700000001","",fingerprint,"[]","FIXTURE","01.01.2026","fixture-pdf",false,"","[]",metadata,now);
        repository.saveSettings(original);
        repository.upsertProducts(List.of(new Product("04600000000015","Synthetic","6103100000","CONFORMITY_DECLARATION","FIXTURE","01.01.2026",null)));
    }
    @AfterEach void cleanup(){ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");}

    @Test void normalEmptySettingsAlwaysEnableCirculation(){assertTrue(Settings.empty().autoIntroduction());}
    @Test void normalSettingsReadAndSaveUseMandatoryCirculationWithoutChangingOtherFields() throws Exception {
        assertEquals(original.withAutoIntroduction(true),repository.getSettings());
        try(var c=Database.getConnection();var s=c.createStatement();var row=s.executeQuery("SELECT auto_introduction FROM znack_settings WHERE shop_id=1")){assertTrue(row.next());assertEquals(1,row.getInt(1));}
    }
    @Test void settingsAndMarketplaceProofRemainReadOnlyInsideAnotherWriteTransaction() throws Exception {
        var shop=new ShopRepository().findById(1);
        assertDoesNotThrow(()->SqliteWriteTransaction.run(c->{assertNotNull(repository.getSettings());assertNotNull(RegisteredGtinProof.current(shop));return null;}));
    }
    @Test void schemaCapturesLegacyRawIntentBeforeAnyNewEffectivePreferenceIsSaved() throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_settings SET auto_introduction=0 WHERE shop_id=1");}
        long id=repository.createPipeline("04600000000015",2,UUID.randomUUID().toString());
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("DELETE FROM znack_pipeline_connections WHERE pipeline_id="+id);
            ZnackSchemaSupport.initialize(c);
        }
        assertTrue(repository.boundPipelineSettings(id).isPresent(),"Migration must freeze the old raw active intent before new policy saves");
        assertEquals(original,repository.boundPipelineSettings(id).orElseThrow());
        assertTrue(repository.getSettings().autoIntroduction());
        repository.saveSettings(original.withAutoIntroduction(true));
        assertEquals(original,repository.pipelineSettings(id),"Saving the new preference cannot grant old paid intent");
    }
    @Test void missingCompletedOriginalProfileIsNotInventedByOpeningSettingsOrSchema() throws Exception {
        long id=repository.createPipeline("04600000000015",1,UUID.randomUUID().toString());
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE znack_purchase_pipelines SET stage='COMPLETED' WHERE id="+id);
            s.execute("DELETE FROM znack_pipeline_connections WHERE pipeline_id="+id);
            ZnackSchemaSupport.initialize(c);
        }
        repository.getSettings();assertTrue(repository.boundPipelineSettings(id).isEmpty());
        assertEquals(PurchaseStage.COMPLETED,repository.findPipeline(id).orElseThrow().stage());
        assertTrue(repository.findOrders().isEmpty());
    }
}
