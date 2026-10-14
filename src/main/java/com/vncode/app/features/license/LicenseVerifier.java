package com.vncode.app.features.license;

import com.google.gson.*;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.security.PublicKey;
import java.security.Signature;
import java.security.GeneralSecurityException;
import java.util.*;
import static com.vncode.app.features.license.LicenseModels.*;

public final class LicenseVerifier {
    static final long CLOCK_SKEW_SECONDS=300;
    private final Map<String, PublicKey> trusted;
    public LicenseVerifier(Map<String, PublicKey> trusted) { this.trusted = Map.copyOf(trusted); }
    public boolean configured() { return !trusted.isEmpty(); }
    public Lease verify(Envelope envelope, String accountId, String deviceHash, long minRevision, long now, boolean online) throws IOException {
        if (envelope == null || !trusted.containsKey(envelope.keyId())) throw new IOException("LICENSE_TRUST_NOT_CONFIGURED");
        byte[] bytes = LicenseDeviceKeys.decode(envelope.payload(), 32768), sig = LicenseDeviceKeys.decode(envelope.signature(), 64);
        if (sig.length != 64) throw LicenseJson.invalid();
        try {
            var verifier = Signature.getInstance("Ed25519");verifier.initVerify(trusted.get(envelope.keyId()));verifier.update(bytes);
            if(!verifier.verify(sig))throw LicenseJson.invalid();
        } catch (GeneralSecurityException invalid) { throw LicenseJson.invalid(); }
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException invalid) { throw LicenseJson.invalid(); }
        JsonObject o = LicenseJson.object(text);
        if (LicenseJson.number(o,"formatVersion") != 1 || !"vncode".equals(LicenseJson.string(o,"product",16))) throw LicenseJson.invalid();
        String account = LicenseJson.string(o,"accountId",128), device = LicenseJson.string(o,"deviceId",128), hash = LicenseJson.string(o,"devicePublicKeyHash",64);
        if(account.isBlank() || device.isBlank() || !account.equals(accountId) || !hash.matches("[a-f0-9]{64}") || !hash.equals(deviceHash)) throw LicenseJson.invalid();
        long revision=LicenseJson.number(o,"licenseRevision"),issued=LicenseJson.number(o,"issuedAt"),notBefore=LicenseJson.number(o,"notBefore"),expires=LicenseJson.number(o,"expiresAt"),subscription=LicenseJson.number(o,"subscriptionExpiresAt");
        long devices=LicenseJson.number(o,"maxDevices"),shops=LicenseJson.number(o,"maxShops"),hours=LicenseJson.number(o,"offlineHours");
        if(revision<1 || revision<minRevision || devices<1 || devices>10 || shops<1 || shops>100 || hours<0 || hours>168 || notBefore<=0 || notBefore>issued || issued>expires || expires>subscription || subscription>253402300799L) throw LicenseJson.invalid();
        // Both timestamps come from the verified issuer. Use the same bounded
        // tolerance for issuedAt and notBefore; ordinary NTP drift must not reject
        // a newly redeemed grant. Expiry and subscription deadlines remain exact.
        if(issued>now+CLOCK_SKEW_SECONDS || notBefore>now+CLOCK_SKEW_SECONDS)throw new IOException("LICENSE_CLOCK_SKEW");
        if(expires>issued+hours*3600 || (online ? now>=subscription : now>=expires)) throw new IOException("LICENSE_EXPIRED");
        var permissions=EnumSet.noneOf(Permission.class);
        JsonElement array=o.get("permissions");if(array==null || !array.isJsonArray())throw LicenseJson.invalid();
        try { for(JsonElement e:array.getAsJsonArray()) {if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw LicenseJson.invalid();Permission p=Permission.valueOf(e.getAsString().toUpperCase(Locale.ROOT));if(!permissions.add(p))throw LicenseJson.invalid();} }
        catch(IllegalArgumentException invalid) { throw LicenseJson.invalid(); }
        return new Lease(account,device,hash,revision,issued,expires,subscription,(int)devices,(int)shops,(int)hours,permissions);
    }
}
