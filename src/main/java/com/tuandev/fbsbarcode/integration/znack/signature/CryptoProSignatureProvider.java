package com.tuandev.fbsbarcode.integration.znack.signature;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.SignedData;
import org.bouncycastle.asn1.pkcs.SignerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class CryptoProSignatureProvider implements ZnackSignatureProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(CryptoProSignatureProvider.class);
    private final CryptoProCommandRunner runner;
    private final String cryptcpOverride;
    private final String certificateSelector;
    private final Duration timeout;
    private final ZnackSignatureProvider windowsFallback;

    public CryptoProSignatureProvider(String cryptcpOverride, String certificateSelector, Duration timeout) {
        this(new CryptoProCommandRunner(), cryptcpOverride, certificateSelector, timeout,
                // Fallback chain when cryptcp is absent: csptest (ships with every CryptoPro CSP) →
                // CAdESCOM (Windows-only, part of the CAdES Browser Plug-in).
                new CsptestSignatureProvider(certificateSelector, timeout,
                        WindowsCadesSignatureProvider.isWindows()
                                ? new WindowsCadesSignatureProvider(certificateSelector, timeout) : null));
    }

    CryptoProSignatureProvider(CryptoProCommandRunner runner, String cryptcpOverride, String certificateSelector, Duration timeout) {
        this(runner, cryptcpOverride, certificateSelector, timeout, null);
    }

    CryptoProSignatureProvider(CryptoProCommandRunner runner, String cryptcpOverride, String certificateSelector,
                               Duration timeout, ZnackSignatureProvider windowsFallback) {
        this.runner = runner;
        this.cryptcpOverride = cryptcpOverride;
        this.certificateSelector = certificateSelector == null ? "" : certificateSelector.trim();
        this.timeout = timeout;
        this.windowsFallback = windowsFallback;
    }

    @Override
    public CryptoProSigningResult sign(byte[] payload, ZnackSignatureContext context) throws CryptoProException {
        if (certificateSelector.isBlank()) {
            throw new CryptoProException(CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT, "Select a CryptoPro certificate before signing.");
        }
        Path workDir = null;
        Path input = null;
        Path output = null;
        try {
            workDir = Files.createTempDirectory("wcode-znack-");
            input = workDir.resolve("payload.bin");
            output = workDir.resolve("signature.p7s");
            Files.write(input, payload);
            List<String> command = new ArrayList<>(List.of(
                    runner.resolve(cryptcpOverride, "cryptcp"),
                    "-sign",
                    "-uMy",
                    "-thumbprint", certificateSelector,
                    "-der",
                    context.detached() ? "-detached" : "-attached",
                    input.toString(),
                    output.toString()));
            CryptoProCommandRunner.Result result = runner.run(command, timeout);
            if (result.exitCode() != 0) throw failure(result);
            byte[] raw = Files.isRegularFile(output) && Files.size(output) > 0 ? Files.readAllBytes(output) : result.stdout();
            byte[] cms = cms(raw);
            return new CryptoProSigningResult(cms, result.diagnostic());
        } catch (CryptoProException e) {
            if ((e.code() == CryptoProErrorCode.CRYPTCP_MISSING
                    || e.code() == CryptoProErrorCode.CRYPTOPRO_MISSING
                    || e.code() == CryptoProErrorCode.CRYPTCP_LICENSE_INVALID)
                    && windowsFallback != null) {
                LOGGER.warn("cryptcp signing unavailable; trying csptest/CAdESCOM fallback. code={}, details={}",
                        e.code(), ZnackSanitizer.error(e));
                try {
                    return windowsFallback.sign(payload, context);
                } catch (CryptoProException fallbackError) {
                    CryptoProException combined = new CryptoProException(fallbackError.code(),
                            "cryptcp failed before fallback: " + ZnackSanitizer.error(e)
                                    + "; fallback failed: " + ZnackSanitizer.error(fallbackError),
                            fallbackError);
                    logFailure(combined);
                    throw combined;
                }
            }
            logFailure(e);
            throw e;
        } catch (Exception e) {
            CryptoProException failure = new CryptoProException(CryptoProErrorCode.SIGNING_FAILED, "CryptoPro signing failed.", e);
            logFailure(failure);
            throw failure;
        } finally {
            try { if (input != null) Files.deleteIfExists(input); } catch (Exception ignored) {}
            try { if (output != null) Files.deleteIfExists(output); } catch (Exception ignored) {}
            try { if (workDir != null) Files.deleteIfExists(workDir); } catch (Exception ignored) {}
        }
    }

    public static void requireAvailable(String cryptcpOverride, Duration timeout) throws CryptoProException {
        CryptoProCommandRunner runner = new CryptoProCommandRunner();
        try {
            runner.resolve(cryptcpOverride, "cryptcp");
            return;
        } catch (CryptoProException cryptcpError) {
            if (cryptcpError.code() != CryptoProErrorCode.CRYPTCP_MISSING) throw cryptcpError;
        }
        // cryptcp absent — csptest (bundled with every CryptoPro CSP) can sign; CAdESCOM is the last resort.
        try {
            runner.resolve(null, "csptest");
        } catch (CryptoProException csptestError) {
            if (!WindowsCadesSignatureProvider.isWindows()) throw csptestError;
            WindowsCadesSignatureProvider.requireAvailable(timeout);
        }
    }

    static byte[] cms(byte[] raw) throws CryptoProException {
        if (raw == null || raw.length == 0) {
            throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, "CryptoPro returned an empty signature.");
        }
        byte[] value = raw;
        String text = new String(raw, StandardCharsets.US_ASCII).replaceAll("\\s+", "");
        if (text.matches("[A-Za-z0-9+/]+={0,2}")) {
            try { value = Base64.getDecoder().decode(text); }
            catch (IllegalArgumentException e) {
                throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, "CryptoPro returned invalid Base64 signature data.", e);
            }
        }
        if (!isCmsSignedData(value)) {
            throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, "CryptoPro returned invalid CMS/CAdES signature data.");
        }
        return value;
    }

    private static boolean isCmsSignedData(byte[] value) {
        try (ASN1InputStream input = new ASN1InputStream(value)) {
            ContentInfo contentInfo = ContentInfo.getInstance(input.readObject());
            if (input.readObject() != null || !PKCSObjectIdentifiers.signedData.equals(contentInfo.getContentType())) return false;
            SignedData signedData = SignedData.getInstance(contentInfo.getContent());
            if (signedData.getSignerInfos() == null || signedData.getSignerInfos().size() == 0) return false;
            for (int i = 0; i < signedData.getSignerInfos().size(); i++) {
                SignerInfo.getInstance(signedData.getSignerInfos().getObjectAt(i));
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private CryptoProException failure(CryptoProCommandRunner.Result result) {
        String diagnostic = result.diagnostic().toLowerCase();
        CryptoProErrorCode code = diagnostic.contains("license") || diagnostic.contains("licence")
                || diagnostic.contains("лиценз") || diagnostic.contains("0x0000065b")
                || diagnostic.contains("0x65b") || diagnostic.contains("0x20000324")
                ? CryptoProErrorCode.CRYPTCP_LICENSE_INVALID
                : diagnostic.contains("cancel") || diagnostic.contains("отмен")
                ? CryptoProErrorCode.CANCELLED
                : diagnostic.contains("expired") || diagnostic.contains("истек")
                ? CryptoProErrorCode.CERTIFICATE_EXPIRED
                : diagnostic.contains("private key") || diagnostic.contains("закрыт")
                ? CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE
                : diagnostic.contains("certificate") || diagnostic.contains("сертифик")
                ? CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT : CryptoProErrorCode.SIGNING_FAILED;
        return new CryptoProException(code, "CryptoPro signing failed (exit " + result.exitCode() + "): " + result.diagnostic());
    }

    private void logFailure(CryptoProException error) {
        LOGGER.error("CryptoPro signing failed. code={}, details={}", error.code(), ZnackSanitizer.error(error));
    }
}
