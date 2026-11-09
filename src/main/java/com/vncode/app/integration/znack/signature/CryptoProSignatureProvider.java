package com.vncode.app.integration.znack.signature;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.SignedData;
import org.bouncycastle.asn1.pkcs.SignerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.vncode.app.integration.znack.ZnackSanitizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public class CryptoProSignatureProvider implements ZnackSignatureProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(CryptoProSignatureProvider.class);
    private final CryptoProCommandRunner runner;
    private final String cryptcpOverride;
    private final String certificateSelector;
    private final Duration timeout;
    private final TimedSigner windowsFallback;
    private final CertificateStores certificateStores;
    private final TimedSigner cadesSigner;

    interface CertificateStores {
        List<CryptoProCertificateInfo> currentUser(Duration remaining, CryptoProCommandRunner boundedRunner) throws CryptoProException;
        List<CryptoProCertificateInfo> cades(Duration remaining, CryptoProCommandRunner boundedRunner) throws CryptoProException;
    }

    @FunctionalInterface
    interface TimedSigner {
        CryptoProSigningResult sign(byte[] payload, ZnackSignatureContext context, Duration remaining,
                                    CryptoProCommandRunner boundedRunner)
                throws CryptoProException;
    }

    public CryptoProSignatureProvider(String cryptcpOverride, String certificateSelector, Duration timeout) {
        this(new CryptoProCommandRunner(), cryptcpOverride, certificateSelector, timeout,
                WindowsCadesSignatureProvider.isWindows());
    }

    CryptoProSignatureProvider(CryptoProCommandRunner runner, String cryptcpOverride, String certificateSelector,
                               Duration timeout, boolean windowsRouting) {
        this(runner, cryptcpOverride, certificateSelector, timeout,
                (payload, context, remaining, boundedRunner) -> new CsptestSignatureProvider(boundedRunner, null,
                        certificateSelector, remaining,
                        windowsRouting ? new WindowsCadesSignatureProvider(boundedRunner, certificateSelector, remaining) : null)
                        .sign(payload, context),
                windowsRouting ? defaultCertificateStores() : null,
                windowsRouting ? (payload, context, remaining, boundedRunner) ->
                        new WindowsCadesSignatureProvider(boundedRunner, certificateSelector, remaining).sign(payload, context) : null);
    }

    CryptoProSignatureProvider(CryptoProCommandRunner runner, String cryptcpOverride, String certificateSelector, Duration timeout) {
        this(runner, cryptcpOverride, certificateSelector, timeout, (ZnackSignatureProvider) null);
    }

    CryptoProSignatureProvider(CryptoProCommandRunner runner, String cryptcpOverride, String certificateSelector,
                               Duration timeout, ZnackSignatureProvider windowsFallback) {
        this(runner, cryptcpOverride, certificateSelector, timeout,
                windowsFallback == null ? null : (payload, context, remaining, boundedRunner) -> windowsFallback.sign(payload, context),
                null, null);
    }

    CryptoProSignatureProvider(CryptoProCommandRunner runner, String cryptcpOverride, String certificateSelector,
                               Duration timeout, TimedSigner windowsFallback, CertificateStores certificateStores,
                               TimedSigner cadesSigner) {
        this.runner = runner;
        this.cryptcpOverride = cryptcpOverride;
        this.certificateSelector = certificateSelector == null ? "" : certificateSelector.trim();
        this.timeout = timeout;
        this.windowsFallback = windowsFallback;
        this.certificateStores = certificateStores;
        this.cadesSigner = cadesSigner;
    }

    private static CertificateStores defaultCertificateStores() {
        return new CertificateStores() {
            @Override public List<CryptoProCertificateInfo> currentUser(Duration remaining, CryptoProCommandRunner boundedRunner) throws CryptoProException {
                return new CryptoProCertificateDiscoveryService(boundedRunner).readCertmgr(null, remaining);
            }

            @Override public List<CryptoProCertificateInfo> cades(Duration remaining, CryptoProCommandRunner boundedRunner) throws CryptoProException {
                return new WindowsCadesCertificateDiscovery(boundedRunner).discover(remaining);
            }
        };
    }

    @Override
    public CryptoProSigningResult sign(byte[] payload, ZnackSignatureContext context) throws CryptoProException {
        return CertificateSigningQueue.run(certificateSelector, () -> signQueued(payload, context));
    }

    private CryptoProSigningResult signQueued(byte[] payload, ZnackSignatureContext context) throws CryptoProException {
        if (certificateSelector.isBlank()) {
            throw new CryptoProException(CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT, "Select a CryptoPro certificate before signing.");
        }
        SigningDeadline deadline = certificateStores == null ? null : new SigningDeadline(timeout);
        // Decide using fresh public-certificate reads before invoking either signing implementation.
        // A routed CAdES failure must never fall through to a second signing attempt.
        if (preferCades(deadline)) {
            CryptoProSigningResult signature = cadesSigner.sign(payload, context, deadline.remaining(), boundedRunner(deadline));
            deadline.remaining();
            return signature;
        }
        Path workDir = null;
        Path input = null;
        Path output = null;
        try {
            workDir = Files.createTempDirectory("vncode-znack-");
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
            CryptoProCommandRunner.Result result = runner.run(command, remaining(deadline));
            if (result.exitCode() != 0) throw failure(result);
            byte[] raw = Files.isRegularFile(output) && Files.size(output) > 0 ? Files.readAllBytes(output) : result.stdout();
            byte[] cms = cms(raw);
            if (deadline != null) deadline.remaining();
            return new CryptoProSigningResult(cms, result.diagnostic());
        } catch (CryptoProException e) {
            if ((e.code() == CryptoProErrorCode.CRYPTCP_MISSING
                    || e.code() == CryptoProErrorCode.CRYPTOPRO_MISSING
                    || e.code() == CryptoProErrorCode.CRYPTCP_LICENSE_INVALID)
                    && windowsFallback != null) {
                LOGGER.warn("cryptcp signing unavailable; trying csptest/CAdESCOM fallback. code={}, details={}",
                        e.code(), ZnackSanitizer.error(e));
                try {
                    CryptoProSigningResult signature = windowsFallback.sign(payload, context, remaining(deadline), boundedRunner(deadline));
                    if (deadline != null) deadline.remaining();
                    return signature;
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

    private boolean preferCades(SigningDeadline deadline) throws CryptoProException {
        String thumbprint = normalizedThumbprint(certificateSelector);
        if (certificateStores == null || cadesSigner == null || thumbprint.isEmpty()) return false;
        try {
            if (containsThumbprint(certificateStores.currentUser(deadline.remaining(), boundedRunner(deadline)), thumbprint)) return false;
        } catch (CryptoProException unavailable) {
            propagateTerminal(unavailable);
            // An unavailable read is not evidence of absence; require an exact CAdES match below.
        }
        try {
            return containsThumbprint(certificateStores.cades(deadline.remaining(), boundedRunner(deadline)), thumbprint);
        } catch (CryptoProException unavailable) {
            propagateTerminal(unavailable);
            return false;
        }
    }

    private static boolean containsThumbprint(List<CryptoProCertificateInfo> certificates, String selected) {
        return certificates.stream().anyMatch(certificate -> selected.equals(normalizedThumbprint(certificate.thumbprint())));
    }

    private static String normalizedThumbprint(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.matches("[A-F0-9]{40}") ? normalized : "";
    }

    private static void propagateTerminal(CryptoProException failure) throws CryptoProException {
        if (failure.code() == CryptoProErrorCode.CANCELLED || failure.code() == CryptoProErrorCode.TIMEOUT) throw failure;
    }

    private Duration remaining(SigningDeadline deadline) throws CryptoProException {
        return deadline == null ? timeout : deadline.remaining();
    }

    private CryptoProCommandRunner boundedRunner(SigningDeadline deadline) {
        if (deadline == null) return runner;
        return new CryptoProCommandRunner() {
            @Override public String resolve(String override, String command) throws CryptoProException {
                deadline.remaining();
                String resolved = runner.resolve(override, command);
                deadline.remaining();
                return resolved;
            }

            @Override public Result run(List<String> command, Duration requested) throws CryptoProException {
                Duration left = deadline.remaining();
                Result result = runner.run(command, requested.compareTo(left) < 0 ? requested : left);
                deadline.remaining();
                return result;
            }
        };
    }

    private static final class SigningDeadline {
        private final long started = System.nanoTime();
        private final long budget;

        SigningDeadline(Duration timeout) {
            if (timeout == null || timeout.isZero() || timeout.isNegative())
                throw new IllegalArgumentException("CryptoPro timeout must be positive.");
            budget = timeout.toNanos();
        }

        Duration remaining() throws CryptoProException {
            if (Thread.currentThread().isInterrupted())
                throw new CryptoProException(CryptoProErrorCode.CANCELLED, "CryptoPro signing was cancelled.");
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) throw new CryptoProException(CryptoProErrorCode.TIMEOUT, "CryptoPro signing timed out.");
            return Duration.ofNanos(remaining);
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
