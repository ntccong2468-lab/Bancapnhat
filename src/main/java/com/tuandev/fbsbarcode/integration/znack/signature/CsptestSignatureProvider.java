package com.tuandev.fbsbarcode.integration.znack.signature;

import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Signs Znack documents with CryptoPro's {@code csptest -sfsign} utility.
 *
 * <p>Unlike {@code cryptcp} (a separate CryptoPro product that ships in its own installer) and the
 * CAdESCOM COM component (part of the CAdES Browser Plug-in, which is often present for browser use
 * yet fails to open the desktop certificate store), {@code csptest} is bundled with <em>every</em>
 * CryptoPro CSP installation. It therefore works on customer machines that have only CSP + the
 * browser plug-in. {@code csptest -sfsign -my <thumbprint>} selects the certificate by SHA-1
 * thumbprint exactly like {@code cryptcp -thumbprint}, so it never shows the interactive
 * "Enter certificate number" prompt.
 */
final class CsptestSignatureProvider implements ZnackSignatureProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(CsptestSignatureProvider.class);
    private final CryptoProCommandRunner runner;
    private final String csptestOverride;
    private final String certificateSelector;
    private final Duration timeout;
    private final ZnackSignatureProvider fallback;

    CsptestSignatureProvider(String certificateSelector, Duration timeout, ZnackSignatureProvider fallback) {
        this(new CryptoProCommandRunner(), null, certificateSelector, timeout, fallback);
    }

    CsptestSignatureProvider(CryptoProCommandRunner runner, String csptestOverride, String certificateSelector,
                             Duration timeout, ZnackSignatureProvider fallback) {
        this.runner = runner;
        this.csptestOverride = csptestOverride;
        this.certificateSelector = certificateSelector == null ? "" : certificateSelector.trim();
        this.timeout = timeout;
        this.fallback = fallback;
    }

    @Override
    public CryptoProSigningResult sign(byte[] payload, ZnackSignatureContext context) throws CryptoProException {
        if (certificateSelector.isBlank()) {
            throw new CryptoProException(CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT,
                    "Select a CryptoPro certificate before signing.");
        }
        Path workDir = null;
        Path input = null;
        Path output = null;
        try {
            String csptest = runner.resolve(csptestOverride, "csptest");
            workDir = Files.createTempDirectory("wcode-csptest-");
            input = workDir.resolve("payload.bin");
            output = workDir.resolve("signature.p7s");
            Files.write(input, payload);
            List<String> command = new ArrayList<>(List.of(
                    csptest,
                    "-sfsign", "-sign",
                    "-in", input.toString(),
                    "-out", output.toString(),
                    "-my", certificateSelector,
                    "-add",
                    "-cades_strict"));
            if (context.detached()) command.add("-detached");
            CryptoProCommandRunner.Result result = runner.run(command, timeout);
            byte[] raw = Files.isRegularFile(output) && Files.size(output) > 0 ? Files.readAllBytes(output) : new byte[0];
            if (raw.length == 0 && (result.exitCode() != 0 || hasErrorCode(result.stdoutText()))) throw failure(result);
            return new CryptoProSigningResult(CryptoProSignatureProvider.cms(raw), result.diagnostic());
        } catch (CryptoProException e) {
            if (e.code() == CryptoProErrorCode.CRYPTOPRO_MISSING && fallback != null) {
                LOGGER.warn("csptest signing unavailable; trying CAdESCOM fallback. details={}", ZnackSanitizer.error(e));
                try {
                    return fallback.sign(payload, context);
                } catch (CryptoProException fallbackError) {
                    CryptoProException combined = new CryptoProException(fallbackError.code(),
                            "csptest unavailable: " + ZnackSanitizer.error(e)
                                    + "; CAdESCOM fallback failed: " + ZnackSanitizer.error(fallbackError), fallbackError);
                    logFailure(combined);
                    throw combined;
                }
            }
            logFailure(e);
            throw e;
        } catch (Exception e) {
            CryptoProException failure = new CryptoProException(CryptoProErrorCode.SIGNING_FAILED, "csptest signing failed.", e);
            logFailure(failure);
            throw failure;
        } finally {
            try { if (input != null) Files.deleteIfExists(input); } catch (Exception ignored) {}
            try { if (output != null) Files.deleteIfExists(output); } catch (Exception ignored) {}
            try { if (workDir != null) Files.deleteIfExists(workDir); } catch (Exception ignored) {}
        }
    }

    /** csptest reports failures via a trailing {@code [ErrorCode: 0x........]} even when it exits 0. */
    private static boolean hasErrorCode(String stdout) {
        String text = stdout.toLowerCase(Locale.ROOT);
        int index = text.lastIndexOf("[errorcode:");
        if (index < 0) return false;
        return !text.substring(index).replaceAll("[^0-9a-fx:\\]]", "").contains("0x00000000");
    }

    private CryptoProException failure(CryptoProCommandRunner.Result result) {
        String diagnostic = result.diagnostic().toLowerCase(Locale.ROOT);
        CryptoProErrorCode code = diagnostic.contains("license") || diagnostic.contains("licence")
                || diagnostic.contains("лиценз") || diagnostic.contains("0x0000065b") || diagnostic.contains("0x65b")
                ? CryptoProErrorCode.CRYPTCP_LICENSE_INVALID
                : diagnostic.contains("cancel") || diagnostic.contains("отмен")
                ? CryptoProErrorCode.CANCELLED
                : diagnostic.contains("expired") || diagnostic.contains("истек")
                ? CryptoProErrorCode.CERTIFICATE_EXPIRED
                : diagnostic.contains("private key") || diagnostic.contains("закрыт")
                ? CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE
                : diagnostic.contains("certificate") || diagnostic.contains("сертифик") || diagnostic.contains("not found")
                ? CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT : CryptoProErrorCode.SIGNING_FAILED;
        return new CryptoProException(code, "csptest signing failed (exit " + result.exitCode() + "): " + result.diagnostic());
    }

    private void logFailure(CryptoProException error) {
        LOGGER.error("csptest signing failed. code={}, details={}", error.code(), ZnackSanitizer.error(error));
    }
}
