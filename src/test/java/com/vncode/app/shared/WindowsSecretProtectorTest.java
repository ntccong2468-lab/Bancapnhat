package com.vncode.app.shared;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;

class WindowsSecretProtectorTest {
    @Test void restrictsPurposeAndSizeBeforeStartingAnyExternalProcess() {
        assertThrows(IllegalArgumentException.class,()->new WindowsSecretProtector("other-purpose",65536));
        assertThrows(IllegalArgumentException.class,()->new WindowsSecretProtector("VN code GS1 mail",8193));
        assertThrows(IllegalArgumentException.class,()->new WindowsSecretProtector("VN code account license",65537));
        assertThrows(IllegalArgumentException.class,()->new WindowsSecretProtector("VN code account license",0));
        var mail=new WindowsSecretProtector();var license=new WindowsSecretProtector("VN code account license",65536);
        assertThrows(IOException.class,()->mail.protect("x".repeat(8193)));
        assertThrows(IOException.class,()->license.protect("é".repeat(32769)),"Limits count UTF-8 bytes, not Java characters");
        assertThrows(IOException.class,()->license.reveal("plain-text"));
        assertThrows(IOException.class,()->license.reveal("dpapi:"+"A".repeat(200000)));
    }
    @Test @DisabledOnOs(OS.WINDOWS) void neverFallsBackToPlaintextOffWindows() {
        var license=new WindowsSecretProtector("VN code account license",65536);
        assertThrows(IOException.class,()->license.protect("fixture-secret"));
        assertThrows(IOException.class,()->license.reveal("dpapi:AQ=="));
    }
    @Test @EnabledOnOs(OS.WINDOWS) void separatesLicenseAndExistingMailEntropyOnWindows() throws Exception {
        var existingMail=new WindowsSecretProtector();var explicitMail=new WindowsSecretProtector("VN code GS1 mail",8192);
        var license=new WindowsSecretProtector("VN code account license",65536);
        String mail=existingMail.protect("fixture-mail-password");assertEquals("fixture-mail-password",explicitMail.reveal(mail));
        assertThrows(IOException.class,()->license.reveal(mail));
        String fullSize="é".repeat(32768);String protectedState=license.protect(fullSize);
        assertEquals(fullSize,license.reveal(protectedState));assertThrows(IOException.class,()->existingMail.reveal(protectedState));
    }
}
