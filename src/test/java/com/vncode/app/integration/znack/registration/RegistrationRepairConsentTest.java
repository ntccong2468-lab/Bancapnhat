package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationRepairConsentTest {
    @TempDir Path root;
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void ordinaryRegistrationCannotGrantRepairWithoutTypedSellerIntent()throws Exception {
        var f=new OzonCardRegistrationWorkflowTest();f.root=root;f.setup();var scope=f.session().scope();
        assertTrue(f.repository.claim(f.shop,f.key,"9001","",scope,false));
        assertTrue(f.repository.transition(f.key,ZnackCardRegistrationModels.Status.CHECKING,ZnackCardRegistrationModels.Status.ALLOCATING_GTIN));
        JsonObject body=ZnackNationalCatalogService.buildPayload(GtinWriteTestFixture.GTIN,f.draft,"");
        f.repository.generated(f.key,GtinWriteTestFixture.GTIN,body.toString());
        assertThrows(RegistrationValidationException.class,()->RegistrationRepairConsent.grant(f.shop,f.key,scope,f.row().gtin(),body));
        assertFalse(RegistrationRepairConsent.allowed(f.shop,f.key,scope,f.row().gtin(),body));
    }
}
