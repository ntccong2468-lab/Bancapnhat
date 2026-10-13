package com.vncode.app.features.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;

public final class LicenseDeviceKeys {
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private final KeyPair pair;
    private LicenseDeviceKeys(KeyPair pair) { this.pair = pair; }
    public static LicenseDeviceKeys create() throws IOException {
        try { return new LicenseDeviceKeys(KeyPairGenerator.getInstance("Ed25519").generateKeyPair()); }
        catch (GeneralSecurityException failure) { throw new IOException("LICENSE_DEVICE_KEY_INVALID"); }
    }
    public static LicenseDeviceKeys restore(String publicKey, String privateKey) throws IOException {
        try {
            var factory = KeyFactory.getInstance("Ed25519");
            var pair = new KeyPair(publicKey(publicKey), factory.generatePrivate(new PKCS8EncodedKeySpec(decode(privateKey, 256))));
            var result = new LicenseDeviceKeys(pair);
            var check = Signature.getInstance("Ed25519");check.initVerify(pair.getPublic());check.update("vncode-pair-check".getBytes(StandardCharsets.UTF_8));
            if (!check.verify(decode(result.sign("vncode-pair-check"), 64))) throw new GeneralSecurityException();
            return result;
        } catch (GeneralSecurityException | IllegalArgumentException failure) { throw new IOException("LICENSE_DEVICE_KEY_INVALID"); }
    }
    public String publicKey() { byte[] spki = pair.getPublic().getEncoded(); return encode(Arrays.copyOfRange(spki, spki.length - 32, spki.length)); }
    public String privateKey() { return encode(pair.getPrivate().getEncoded()); }
    public String hash() { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey().getBytes(StandardCharsets.UTF_8))); } catch (GeneralSecurityException impossible) { throw new IllegalStateException("LICENSE_CRYPTO_UNAVAILABLE"); } }
    public String sign(String message) throws IOException {
        if (message == null || message.length() > 4096) throw LicenseJson.invalid();
        try { var signature = Signature.getInstance("Ed25519"); signature.initSign(pair.getPrivate()); signature.update(message.getBytes(StandardCharsets.UTF_8)); return encode(signature.sign()); }
        catch (GeneralSecurityException failure) { throw new IOException("LICENSE_DEVICE_KEY_INVALID"); }
    }
    public static PublicKey publicKey(String raw) throws IOException {
        byte[] bytes = decode(raw, 32);if(bytes.length != 32) throw LicenseJson.invalid();
        byte[] spki = Arrays.copyOf(SPKI_PREFIX, SPKI_PREFIX.length + bytes.length);System.arraycopy(bytes, 0, spki, SPKI_PREFIX.length, bytes.length);
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki)); }
        catch (GeneralSecurityException failure) { throw LicenseJson.invalid(); }
    }
    public static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static byte[] decode(String value, int max) throws IOException {
        if (value == null || value.length() > (max * 4L / 3 + 4) || !value.matches("[A-Za-z0-9_-]+")) throw LicenseJson.invalid();
        try { byte[] bytes = Base64.getUrlDecoder().decode(value); if(bytes.length > max || !encode(bytes).equals(value))throw LicenseJson.invalid(); return bytes; }
        catch (IllegalArgumentException failure) { throw LicenseJson.invalid(); }
    }
    @Override public String toString() { return "LicenseDeviceKeys[redacted]"; }
}
