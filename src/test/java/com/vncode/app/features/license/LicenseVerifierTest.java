package com.vncode.app.features.license;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.vncode.app.features.license.LicenseModels.*;
import static org.junit.jupiter.api.Assertions.*;

class LicenseVerifierTest {
    @Test void signedStartTimesAllowBoundedDriftInEitherDisplayTimezoneAndRejectMoreThanFiveMinutes()throws Exception {
        var f=new LicenseTestFixtures();var envelope=f.sign(f.payload());
        var instant=java.time.Instant.ofEpochSecond(LicenseTestFixtures.NOW-3);
        for(String zone:List.of("Asia/Ho_Chi_Minh","Europe/Moscow")) {
            var clock=java.time.Clock.fixed(instant,java.time.ZoneId.of(zone));
            assertNotNull(f.verify(envelope,clock.instant().getEpochSecond(),true));
        }
        assertNotNull(f.verify(envelope,LicenseTestFixtures.NOW-300,true));
        assertEquals("LICENSE_CLOCK_SKEW",assertThrows(IOException.class,()->f.verify(envelope,LicenseTestFixtures.NOW-301,true)).getMessage());
        assertEquals("LICENSE_EXPIRED",assertThrows(IOException.class,()->f.verify(envelope,LicenseTestFixtures.NOW+72*3600,false)).getMessage());
        assertEquals("LICENSE_EXPIRED",assertThrows(IOException.class,()->f.verify(envelope,LicenseTestFixtures.NOW+30*86400,true)).getMessage());
    }
    @Test void validatesGeneratedIssuerSignatureAndImmutablePermissions() throws Exception {
        var f = new LicenseTestFixtures(); var lease = f.verify(f.sign(f.payload()), LicenseTestFixtures.NOW, false);
        assertEquals("account-test", lease.accountId()); assertEquals(f.device.hash(), lease.deviceHash());
        assertEquals(2, lease.revision()); assertEquals(72, lease.offlineHours());
        assertTrue(lease.permissions().contains(Permission.PRINT_NEW));
        assertThrows(UnsupportedOperationException.class, () -> lease.permissions().add(Permission.GS1));
    }
    @Test void rejectsPayloadSignatureAndTrustTampering() throws Exception {
        var f = new LicenseTestFixtures(); var valid = f.sign(f.payload());
        var changed = f.payload(); changed.addProperty("maxShops", 100);
        assertThrows(IOException.class, () -> f.verify(new Envelope(valid.keyId(), LicenseDeviceKeys.encode(changed.toString().getBytes(StandardCharsets.UTF_8)), valid.signature()), LicenseTestFixtures.NOW, false));
        var signature = LicenseDeviceKeys.decode(valid.signature(), 64); signature[0] ^= 1;
        assertThrows(IOException.class, () -> f.verify(new Envelope(valid.keyId(), valid.payload(), LicenseDeviceKeys.encode(signature)), LicenseTestFixtures.NOW, false));
        assertEquals("LICENSE_TRUST_NOT_CONFIGURED", assertThrows(IOException.class, () -> new LicenseVerifier(Map.of()).verify(valid,"account-test",f.device.hash(),2,LicenseTestFixtures.NOW,false)).getMessage());
    }
    @Test void rejectsSignedDuplicateFieldsMalformedUtf8AndTrailingJson() throws Exception {
        var f = new LicenseTestFixtures(); String text = f.payload().toString();
        for (String invalid : new String[] {text.substring(0,text.length()-1)+",\"maxShops\":2}", text+" {}", text.replace("\"maxShops\":1", "\"maxShops\":1.0"), text.replace("\"maxShops\":1", "\"maxShops\":1e0")}) {
            var e = f.sign(invalid.getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> f.verify(e, LicenseTestFixtures.NOW, false));
        }
        var utf8 = f.sign(new byte[] {(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> f.verify(utf8, LicenseTestFixtures.NOW, false));
    }
    @Test void rejectsSignedWrongTypesAndOutOfRangeLimits() throws Exception {
        var f = new LicenseTestFixtures();
        for (String field : new String[] {"formatVersion","issuedAt","licenseRevision","maxDevices","maxShops","offlineHours"}) {
            var p = f.payload(); p.addProperty(field, "1"); var e = f.sign(p);
            assertThrows(IOException.class, () -> f.verify(e,LicenseTestFixtures.NOW,false), field);
        }
        var invalid = Map.of("maxDevices", new long[]{0,11}, "maxShops",new long[]{0,101}, "offlineHours",new long[]{-1,169}, "licenseRevision",new long[]{0,1});
        for (var entry: invalid.entrySet()) for (long value:entry.getValue()) {
            var p=f.payload(); p.addProperty(entry.getKey(),value); var e=f.sign(p);
            assertThrows(IOException.class,()->f.verify(e,LicenseTestFixtures.NOW,false),entry.getKey());
        }
        var p=f.payload(); p.addProperty("product","other-product"); var e=f.sign(p);
        assertThrows(IOException.class,()->f.verify(e,LicenseTestFixtures.NOW,false));
    }
    @Test void rejectsUnknownDuplicateAndNonStringPermissions() throws Exception {
        var f=new LicenseTestFixtures();
        for (String permissions:new String[]{"[\"seller_api\",\"seller_api\"]","[\"admin\"]","[true]","{}","null"}) {
            var p=f.payload(); p.add("permissions",JsonParser.parseString(permissions)); var e=f.sign(p);
            assertThrows(IOException.class,()->f.verify(e,LicenseTestFixtures.NOW,false));
        }
    }
    @Test void bindsAccountDeviceRevisionAndRejectsMillisecondTimestamps() throws Exception {
        var f=new LicenseTestFixtures(); var e=f.sign(f.payload());
        assertThrows(IOException.class,()->f.verifier.verify(e,"different-account",f.device.hash(),2,LicenseTestFixtures.NOW,false));
        assertThrows(IOException.class,()->f.verifier.verify(e,"account-test","0".repeat(64),2,LicenseTestFixtures.NOW,false));
        assertThrows(IOException.class,()->f.verifier.verify(e,"account-test",f.device.hash(),3,LicenseTestFixtures.NOW,false));
        var p=f.payload();
        for(String field:new String[]{"issuedAt","notBefore","expiresAt","subscriptionExpiresAt"}) p.addProperty(field,p.get(field).getAsLong()*1000);
        var millis=f.sign(p); assertThrows(IOException.class,()->f.verify(millis,LicenseTestFixtures.NOW,false));
    }
    @Test void expiryAndOnlineOnlyLeaseHaveSeparateRules() throws Exception {
        var f=new LicenseTestFixtures(); var e=f.sign(f.payload());
        assertEquals("LICENSE_EXPIRED",assertThrows(IOException.class,()->f.verify(e,LicenseTestFixtures.NOW+72*3600,false)).getMessage());
        assertEquals("LICENSE_EXPIRED",assertThrows(IOException.class,()->f.verify(e,LicenseTestFixtures.NOW+30*86400,true)).getMessage());
        var p=f.payload(); p.addProperty("offlineHours",0); p.addProperty("expiresAt",LicenseTestFixtures.NOW); var onlineOnly=f.sign(p);
        assertNotNull(f.verify(onlineOnly,LicenseTestFixtures.NOW,true));
        assertEquals("LICENSE_EXPIRED",assertThrows(IOException.class,()->f.verify(onlineOnly,LicenseTestFixtures.NOW,false)).getMessage());
    }
    @Test void rejectsInvalidSignedTimeOrderingAndExcessOfflineWindow() throws Exception {
        var f=new LicenseTestFixtures();
        String[] fields={"notBefore","issuedAt","expiresAt","subscriptionExpiresAt","subscriptionExpiresAt"};
        long[] values={LicenseTestFixtures.NOW+1,LicenseTestFixtures.NOW+301,LicenseTestFixtures.NOW-1,LicenseTestFixtures.NOW,253402300800L};
        for(int i=0;i<fields.length;i++) {
            var p=f.payload();p.addProperty(fields[i],values[i]);var e=f.sign(p);
            assertThrows(IOException.class,()->f.verify(e,LicenseTestFixtures.NOW,false),fields[i]);
        }
        var p=f.payload();p.addProperty("expiresAt",LicenseTestFixtures.NOW+72*3600+1);var e=f.sign(p);
        assertThrows(IOException.class,()->f.verify(e,LicenseTestFixtures.NOW,false));
    }
}
