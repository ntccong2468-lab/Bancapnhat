package com.vncode.app.features.license;

import org.junit.jupiter.api.extension.*;
import java.nio.file.Files;

/** Only JUnit loads this extension. Domain tests use signed fixtures, never website credentials. */
public final class LicenseTestBootstrap implements BeforeAllCallback, BeforeEachCallback {
    private static LicenseService fixture;
    private static synchronized void install()throws Exception {
        if(fixture==null)fixture=LicenseDomainFixture.active(Files.createTempDirectory("vncode-license-domain-"));
        LicenseRuntime.install(fixture);
    }
    public void beforeAll(ExtensionContext context)throws Exception{install();}
    public void beforeEach(ExtensionContext context)throws Exception{install();}
}
