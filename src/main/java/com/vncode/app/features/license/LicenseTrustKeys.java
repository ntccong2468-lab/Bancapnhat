package com.vncode.app.features.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/** Packaged issuer trust shared by leases and endpoint identity proofs. */
public final class LicenseTrustKeys {
    private LicenseTrustKeys() {}
    public static Map<String, PublicKey> load() {
        Map<String, PublicKey> keys = new HashMap<>();
        try (var stream = LicenseTrustKeys.class.getResourceAsStream("/com/vncode/app/license/trusted-keys.json")) {
            if (stream != null) {
                byte[] bytes = stream.readNBytes(32769);
                if (bytes.length > 32768) throw LicenseJson.invalid();
                var json = LicenseJson.object(new String(bytes, StandardCharsets.UTF_8));
                for (var entry : json.entrySet()) keys.put(entry.getKey(), LicenseDeviceKeys.publicKey(entry.getValue().getAsString()));
            }
        } catch (IOException | RuntimeException invalid) { keys.clear(); }
        return Map.copyOf(keys);
    }
}
