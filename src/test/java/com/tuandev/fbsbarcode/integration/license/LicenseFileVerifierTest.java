package com.tuandev.fbsbarcode.integration.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LicenseFileVerifierTest {

    private static KeyPair keyPair;
    private static LicenseFileVerifier verifier;
    private static final Gson GSON = new Gson();

    @BeforeAll
    static void createKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier =
                new LicenseFileVerifier(
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }

    static SignedLicenseFile sign(LicensePayload payload) throws Exception {
        byte[] json = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(json);
        return new SignedLicenseFile(
                Base64.getEncoder().encodeToString(json),
                Base64.getEncoder().encodeToString(signer.sign()),
                "Ed25519");
    }

    private static LicensePayload payload(String status, long issuedAt, long expiresAt) {
        return new LicensePayload(
                1, "WC-TEST1-TEST1-TEST1-TEST1", "fp-test", "standard", 1, status, issuedAt, expiresAt);
    }

    @Test
    void acceptsCorrectlySignedFile() throws Exception {
        LicensePayload original = payload("valid", 1_000L, 2_000L);
        Optional<LicensePayload> verified = verifier.verify(sign(original));
        assertTrue(verified.isPresent());
        assertEquals(original, verified.get());
    }

    @Test
    void rejectsTamperedPayload() throws Exception {
        SignedLicenseFile file = sign(payload("valid", 1_000L, 2_000L));
        // Kẻ tấn công sửa expiresAt trong payload nhưng giữ chữ ký cũ
        LicensePayload forged = payload("valid", 1_000L, 9_999_999_999_999L);
        String forgedPayloadB64 =
                Base64.getEncoder()
                        .encodeToString(GSON.toJson(forged).getBytes(StandardCharsets.UTF_8));
        SignedLicenseFile tampered =
                new SignedLicenseFile(forgedPayloadB64, file.signature(), file.algorithm());
        assertTrue(verifier.verify(tampered).isEmpty());
    }

    @Test
    void rejectsSignatureFromDifferentKey() throws Exception {
        KeyPair otherPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        LicensePayload original = payload("valid", 1_000L, 2_000L);
        byte[] json = GSON.toJson(original).getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(otherPair.getPrivate());
        signer.update(json);
        SignedLicenseFile foreignSigned =
                new SignedLicenseFile(
                        Base64.getEncoder().encodeToString(json),
                        Base64.getEncoder().encodeToString(signer.sign()),
                        "Ed25519");
        assertTrue(verifier.verify(foreignSigned).isEmpty());
    }

    @Test
    void rejectsGarbageInput() {
        assertTrue(verifier.verify(new SignedLicenseFile("not-base64!!", "x", "Ed25519")).isEmpty());
        assertTrue(verifier.verify(new SignedLicenseFile(null, null, null)).isEmpty());
        assertTrue(verifier.verify(null).isEmpty());
    }
}
