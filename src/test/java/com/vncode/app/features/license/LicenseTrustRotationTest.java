package com.vncode.app.features.license;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.*;
import static com.vncode.app.features.license.LicenseModels.*;
import static org.junit.jupiter.api.Assertions.*;

class LicenseTrustRotationTest {
    @Test void packagedTrustImportsOwnerProvidedPublicKeyAndRetainsPreviousIssuer()throws Exception {
        var trusted=LicenseRuntime.trustedKeys();
        assertEquals(Set.of("vncode-1","vncode-2"),trusted.keySet());
        assertArrayEquals(LicenseDeviceKeys.publicKey("SPtRxTUJ78fqt5YMJ4DJ_PZwB2cMYegs-J7nY62ADMQ").getEncoded(),trusted.get("vncode-2").getEncoded());
        assertArrayEquals(LicenseDeviceKeys.publicKey("Gg7F92gqXnCLGdMbI130qHcBJ2hiI6KseZBgWkNCTeI").getEncoded(),trusted.get("vncode-1").getEncoded());
        assertTrue(new LicenseVerifier(trusted).configured());
    }

    @Test void rotatingIssuerKeepsOldOfflineLeaseButRejectsRelabeledSignaturesAndExpiry()throws Exception {
        var old=new LicenseTestFixtures();var fresh=new LicenseTestFixtures();
        var verifier=new LicenseVerifier(Map.of("vncode-1",old.issuer.getPublic(),"vncode-2",fresh.issuer.getPublic()));
        byte[] payload=old.payload().toString().getBytes(StandardCharsets.UTF_8);
        var previous=old.sign(payload);var oldEnvelope=new Envelope("vncode-1",previous.payload(),previous.signature());
        var signer=Signature.getInstance("Ed25519");signer.initSign(fresh.issuer.getPrivate());signer.update(payload);
        var newEnvelope=new Envelope("vncode-2",LicenseDeviceKeys.encode(payload),LicenseDeviceKeys.encode(signer.sign()));
        for(var envelope:List.of(oldEnvelope,newEnvelope))
            assertNotNull(verifier.verify(envelope,"account-test",old.device.hash(),2,LicenseTestFixtures.NOW+60,false));
        assertThrows(IOException.class,()->verifier.verify(new Envelope("vncode-2",previous.payload(),previous.signature()),"account-test",old.device.hash(),2,LicenseTestFixtures.NOW,false));
        assertThrows(IOException.class,()->verifier.verify(new Envelope("unknown",newEnvelope.payload(),newEnvelope.signature()),"account-test",old.device.hash(),2,LicenseTestFixtures.NOW,false));
        assertEquals("LICENSE_EXPIRED",assertThrows(IOException.class,()->verifier.verify(oldEnvelope,"account-test",old.device.hash(),2,LicenseTestFixtures.NOW+72*3600,false)).getMessage());
    }
}
