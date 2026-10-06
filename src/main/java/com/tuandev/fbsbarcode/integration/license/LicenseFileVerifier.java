package com.tuandev.fbsbarcode.integration.license;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verify chữ ký Ed25519 của license file bằng public key nhúng cứng trong app.
 * Chỉ verify bytes trước rồi mới parse JSON — tuyệt đối không tin payload chưa verify.
 */
public class LicenseFileVerifier {

    private static final Logger log = LoggerFactory.getLogger(LicenseFileVerifier.class);

    /**
     * Public key SPKI base64 của license-server production (sinh trên VPS wcode.online).
     * Đổi khóa đồng nghĩa mọi bản app cũ không nhận license file mới — backup
     * keys/license-signing.key trên server thật cẩn thận.
     */
    static final String DEFAULT_PUBLIC_KEY_B64 =
            "MCowBQYDK2VwAyEANb0850xNtBhlYwnNaHU6Lh9RpULajzI/akwrnGk5dpc=";

    private final PublicKey publicKey;
    private final Gson gson = new Gson();

    public LicenseFileVerifier() {
        this(DEFAULT_PUBLIC_KEY_B64);
    }

    public LicenseFileVerifier(String publicKeySpkiBase64) {
        try {
            X509EncodedKeySpec spec =
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeySpkiBase64));
            this.publicKey = KeyFactory.getInstance("Ed25519").generatePublic(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("License public key không hợp lệ", e);
        }
    }

    /** Trả về payload đã parse nếu chữ ký hợp lệ, ngược lại {@link Optional#empty()}. */
    public Optional<LicensePayload> verify(SignedLicenseFile file) {
        if (file == null || file.payload() == null || file.signature() == null) {
            return Optional.empty();
        }
        try {
            byte[] payloadBytes = Base64.getDecoder().decode(file.payload());
            byte[] signatureBytes = Base64.getDecoder().decode(file.signature());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payloadBytes);
            if (!verifier.verify(signatureBytes)) {
                log.warn("License file có chữ ký không hợp lệ");
                return Optional.empty();
            }
            LicensePayload payload =
                    gson.fromJson(
                            new String(payloadBytes, StandardCharsets.UTF_8), LicensePayload.class);
            return Optional.ofNullable(payload);
        } catch (GeneralSecurityException | IllegalArgumentException | JsonSyntaxException e) {
            log.warn("Không verify được license file: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
