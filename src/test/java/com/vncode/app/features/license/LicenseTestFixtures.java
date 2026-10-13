package com.vncode.app.features.license;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Map;
import static com.vncode.app.features.license.LicenseModels.*;

/** Ephemeral test keys; none of these keys are used by the shipped application. */
final class LicenseTestFixtures {
    static final long NOW = 1_800_000_000L;
    final KeyPair issuer;
    final LicenseDeviceKeys device;
    final LicenseVerifier verifier;
    LicenseTestFixtures() throws Exception {
        issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        device = LicenseDeviceKeys.create();
        verifier = new LicenseVerifier(Map.of("test-issuer", issuer.getPublic()));
    }
    JsonObject payload() {
        JsonObject p = new JsonObject();
        p.addProperty("formatVersion", 1); p.addProperty("product", "vncode");
        p.addProperty("accountId", "account-test"); p.addProperty("deviceId", "device-test");
        p.addProperty("devicePublicKeyHash", device.hash()); p.addProperty("licenseRevision", 2);
        p.addProperty("issuedAt", NOW); p.addProperty("notBefore", NOW);
        p.addProperty("expiresAt", NOW + 72 * 3600); p.addProperty("subscriptionExpiresAt", NOW + 30 * 86400);
        p.addProperty("maxDevices", 1); p.addProperty("maxShops", 1); p.addProperty("offlineHours", 72);
        var permissions = new com.google.gson.JsonArray(); permissions.add("seller_api"); permissions.add("print_new");
        p.add("permissions", permissions);
        return p;
    }
    Envelope sign(JsonObject p) throws Exception { return sign(p.toString().getBytes(StandardCharsets.UTF_8)); }
    Envelope sign(byte[] p) throws Exception {
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(issuer.getPrivate()); signature.update(p);
        return new Envelope("test-issuer", LicenseDeviceKeys.encode(p), LicenseDeviceKeys.encode(signature.sign()));
    }
    Lease verify(Envelope e, long now, boolean online) throws Exception { return verifier.verify(e, "account-test", device.hash(), 2, now, online); }
    State state() throws Exception {
        return new State(device.publicKey(), device.privateKey(), "test-registration", "test-access",
                new Account("account-test", "fixture@gmail.com", "approved", NOW + 30 * 86400),
                sign(payload()), NOW, 2, null);
    }
}
