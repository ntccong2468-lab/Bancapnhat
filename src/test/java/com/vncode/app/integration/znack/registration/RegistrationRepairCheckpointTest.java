package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationRepairCheckpointTest {
    @TempDir Path root;
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void unapprovedLegacyCheckpointRollsBackRatherThanInventConsent()throws Exception {
        var f=new OzonCardRegistrationWorkflowTest();f.root=root;f.setup();var scope=f.session().scope();
        assertTrue(f.repository.claim(f.shop,f.key,"9001","",scope,false));
        assertTrue(f.repository.transition(f.key,Status.CHECKING,Status.ALLOCATING_GTIN));
        var payload=ZnackNationalCatalogService.buildPayload(GtinWriteTestFixture.GTIN,f.draft,"");
        assertThrows(RegistrationValidationException.class,()->f.repository.generatedWithRepairConsent(f.shop,f.key,scope,GtinWriteTestFixture.GTIN,payload.toString()));
        assertEquals(Status.ALLOCATING_GTIN,f.row().status());assertTrue(f.row().gtin().isBlank());
    }
}
