package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.znack.ZnackModels;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Sandbox authentication/write contracts are not approved by the shared production journal. */
class OzonSandboxAllocationTest {
    @TempDir Path root;
    private OzonCardRegistrationWorkflowTest fixture;
    @BeforeEach void setup()throws Exception {
        fixture=new OzonCardRegistrationWorkflowTest();fixture.root=root;fixture.setup();
        GtinWriteTestFixture.saveSettings(fixture.shop,GtinWriteTestFixture.CERTIFICATE,ZnackModels.SANDBOX_NATIONAL_CATALOG);
    }
    @AfterEach void cleanup(){fixture.clear();}
    @Test void sandboxDoesNotAllocateWhileItsContractIsUnverified(){
        assertTrue(fixture.workflow.register(fixture.shop,fixture.key,fixture.draft,true,false,null));
        assertEquals(0,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
        assertNotNull(fixture.workflow.failure(fixture.shop));
        assertTrue(fixture.workflow.failure(fixture.shop).getMessage().contains("V3_SANDBOX_AUTH_UNVERIFIED"));
    }
}
