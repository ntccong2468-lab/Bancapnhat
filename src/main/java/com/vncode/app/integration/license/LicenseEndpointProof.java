package com.vncode.app.integration.license;

import com.google.gson.JsonObject;
import com.vncode.app.features.license.LicenseDeviceKeys;
import com.vncode.app.features.license.LicenseJson;
import okhttp3.*;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static com.vncode.app.integration.license.LicenseApi.*;

/** A pinned backup must identify the same issuer before it can receive credentials. */
final class LicenseEndpointProof {
    private static final SecureRandom RANDOM = new SecureRandom();
    static NetworkProbe probe(HttpUrl origin, OkHttpClient client, NetworkMode mode, Map<String, PublicKey> keys) {
        byte[] nonceBytes = new byte[32]; RANDOM.nextBytes(nonceBytes);
        String nonce = LicenseDeviceKeys.encode(nonceBytes);
        JsonObject body = new JsonObject(); body.addProperty("nonce", nonce);
        var phase = new LicenseNetworkDiagnostics.Phase();
        var bounded = client.newBuilder().connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(5))
                .writeTimeout(Duration.ofSeconds(5)).callTimeout(Duration.ofSeconds(5)).eventListener(phase).build();
        var request = new Request.Builder().url(origin.resolve("api/client/license/endpoint-proof"))
                .header("Accept", "application/json").header("Cache-Control", "no-store")
                .post(WebLicenseApi.oneShotJson(body.toString())).build();
        long started = System.nanoTime();int status = 0;String protocol = "", code = "LICENSE_ENDPOINT_UNVERIFIED";
        boolean valid = false;
        try (var response = bounded.newCall(request).execute()) {
            status = response.code();protocol = response.protocol().toString();
            if ("challenge".equalsIgnoreCase(response.header("cf-mitigated"))) code = "LICENSE_EDGE_CHALLENGE";
            else if (status == 200 && response.body() != null && response.body().contentType() != null
                    && response.body().contentType().type().equals("application") && response.body().contentType().subtype().equals("json")) {
                String decoded=WebLicenseApi.utf8(response.body());
                try { verify(LicenseJson.object(decoded), nonce, origin, keys, Instant.now().getEpochSecond()); }
                catch(java.io.IOException invalid){throw new java.io.IOException("LICENSE_ENDPOINT_UNVERIFIED");}
                valid = true;code = "OK";
            }
        } catch (java.io.IOException network) {
            if (!"LICENSE_ENDPOINT_UNVERIFIED".equals(network.getMessage())) code = WebLicenseApi.networkFailure(network).code();
        } catch (RuntimeException invalid) { code = "LICENSE_ENDPOINT_UNVERIFIED"; }
        return new NetworkProbe(mode, ProbeEndpoint.BACKUP, valid, status, code, phase.value, protocol,
                Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
    }
    static void verify(JsonObject envelope, String nonce, HttpUrl origin, Map<String, PublicKey> keys, long now) throws java.io.IOException {
        try {
            String keyId = LicenseJson.string(envelope, "key_id", 64);
            PublicKey key = keys.get(keyId);if (key == null) throw new IllegalArgumentException();
            byte[] payload = LicenseDeviceKeys.decode(LicenseJson.string(envelope, "payload", 8192), 4096);
            byte[] signature = LicenseDeviceKeys.decode(LicenseJson.string(envelope, "signature", 86), 64);
            var verifier = Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(payload);
            if (!verifier.verify(signature)) throw new IllegalArgumentException();
            var json = LicenseJson.object(new String(payload, StandardCharsets.UTF_8));
            long issued = LicenseJson.number(json, "issuedAt"), expires = LicenseJson.number(json, "expiresAt");
            if (LicenseJson.number(json, "formatVersion") != 1 || !"vncode".equals(LicenseJson.string(json, "product", 16))
                    || !"license-endpoint".equals(LicenseJson.string(json, "purpose", 32))
                    || !nonce.equals(LicenseJson.string(json, "nonce", 43))
                    || !origin.toString().equals(LicenseJson.string(json, "origin", 256))
                    || issued < now - 300 || issued > now + 300 || expires <= issued || expires > issued + 120
                    || expires < now - 300) throw new IllegalArgumentException();
        } catch (Exception invalid) { throw new java.io.IOException("LICENSE_ENDPOINT_UNVERIFIED"); }
    }
}
