package com.vncode.app.integration.znack.signature;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CryptoProCertificateRoutingTest {
    private static final String SELECTED = "0123456789ABCDEF0123456789ABCDEF01234567";
    private static final String OTHER = "89ABCDEF0123456789ABCDEF0123456789ABCDEF";
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final byte[] PAYLOAD = "local synthetic signing fixture".getBytes(StandardCharsets.UTF_8);

    @Test void currentUserMatchKeepsCryptcpAndDoesNotEnumerateCades() throws Exception {
        var stores = new Stores();
        stores.current = List.of(certificate("other selector", SELECTED.toLowerCase()));
        var primary = new PrimaryRunner();
        var cades = new Signer();
        var fallback = new Signer();

        assertArrayEquals(cms(), provider(primary, stores, cades, fallback, SELECTED).sign(PAYLOAD,
                ZnackSignatureContext.SIGNATURE_TEST).cms());

        assertEquals(1, stores.currentReads);
        assertEquals(0, stores.cadesReads);
        assertEquals(1, primary.signs);
        assertEquals(0, cades.signs);
        assertEquals(0, fallback.signs);
    }

    @Test void productionWindowsLookupReadsOnlyUmyWhenExactCertificateIsThere() throws Exception {
        var primary = new PrimaryRunner();
        primary.currentOutput = "SHA1 Thumbprint: " + SELECTED + "\nSubject: CN=Synthetic\n";

        new CryptoProSignatureProvider(primary, "cryptcp", SELECTED, BUDGET, true)
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST);

        assertEquals(List.of("certmgr", "cryptcp"), primary.commands.stream().map(List::getFirst).toList());
        assertEquals(List.of("certmgr", "-list", "-store", "uMy"), primary.commands.getFirst());
        assertEquals(1, primary.signs);
    }

    @Test void tokenCertificateIsChosenBeforeAnySigningEvenWhenOldUmyCertificateExists() throws Exception {
        var stores = new Stores();
        stores.current = List.of(certificate(OTHER, OTHER));
        stores.windows = List.of(certificate(SELECTED, SELECTED));
        var primary = new PrimaryRunner();
        var cades = new Signer();
        var fallback = new Signer();

        assertEquals("routed synthetic signer", provider(primary, stores, cades, fallback, SELECTED)
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST).diagnostic());

        assertEquals(1, stores.currentReads);
        assertEquals(1, stores.cadesReads);
        assertEquals(1, cades.signs);
        assertEquals(0, primary.signs);
        assertEquals(0, fallback.signs);
        assertArrayEquals(PAYLOAD, cades.payload);
        assertEquals(ZnackSignatureContext.SIGNATURE_TEST, cades.context);
    }

    @Test void exactFingerprintNormalizesCaseAndWhitespace() throws Exception {
        var stores = new Stores();
        stores.windows = List.of(certificate("untrusted display alias", "0123456789abcdef 0123456789abcdef 01234567"));
        var primary = new PrimaryRunner();
        var cades = new Signer();

        provider(primary, stores, cades, new Signer(), "0123456789abcdef 0123456789abcdef 01234567")
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST);

        assertEquals(1, cades.signs);
        assertEquals(0, primary.signs);
    }

    @Test void selectorOrProviderLabelCannotSubstituteForMatchingThumbprint() throws Exception {
        var stores = new Stores();
        stores.windows = List.of(certificate(SELECTED, OTHER), certificate(SELECTED, "01234567"));
        var primary = new PrimaryRunner();
        var cades = new Signer();

        provider(primary, stores, cades, new Signer(), SELECTED)
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST);

        assertEquals(1, primary.signs);
        assertEquals(0, cades.signs);
    }

    @ParameterizedTest @ValueSource(strings = {"CN=Synthetic", "AABB", "0123456789ABCDEF0123456789ABCDEF01234567-alias"})
    void aliasesAndPartialFingerprintsKeepOriginalPathWithoutDiscovery(String selector) throws Exception {
        var stores = new Stores();
        stores.windows = List.of(certificate(selector, SELECTED));
        var primary = new PrimaryRunner();
        var cades = new Signer();

        provider(primary, stores, cades, new Signer(), selector)
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST);

        assertEquals(0, stores.currentReads);
        assertEquals(0, stores.cadesReads);
        assertEquals(1, primary.signs);
        assertEquals(0, cades.signs);
    }

    @ParameterizedTest @EnumSource(value = CryptoProErrorCode.class,
            names = {"CERTMGR_MISSING", "DISCOVERY_FAILED", "CRYPTOPRO_MISSING"})
    void unavailableUmyStillRequiresExactCadesProof(CryptoProErrorCode code) throws Exception {
        var stores = new Stores();
        stores.currentFailure = code;
        stores.windows = List.of(certificate(SELECTED, SELECTED));
        var primary = new PrimaryRunner();
        var cades = new Signer();

        provider(primary, stores, cades, new Signer(), SELECTED)
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST);

        assertEquals(1, cades.signs);
        assertEquals(0, primary.signs);
    }

    @Test void unavailableDiscoveryWithoutExactProofRetainsPrimaryBehavior() throws Exception {
        var stores = new Stores();
        stores.currentFailure = CryptoProErrorCode.DISCOVERY_FAILED;
        stores.cadesFailure = CryptoProErrorCode.DISCOVERY_FAILED;
        var primary = new PrimaryRunner();
        var cades = new Signer();

        provider(primary, stores, cades, new Signer(), SELECTED)
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST);

        assertEquals(1, primary.signs);
        assertEquals(0, cades.signs);
    }

    @ParameterizedTest @EnumSource(value = CryptoProErrorCode.class, names = {"TIMEOUT", "CANCELLED"})
    void terminalUmyReadFailureDoesNotStartAnotherReadOrSign(CryptoProErrorCode code) {
        var stores = new Stores();
        stores.currentFailure = code;
        var primary = new PrimaryRunner();
        var cades = new Signer();

        assertEquals(code, assertThrows(CryptoProException.class, () ->
                provider(primary, stores, cades, new Signer(), SELECTED)
                        .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST)).code());

        assertEquals(0, stores.cadesReads);
        assertEquals(0, primary.signs);
        assertEquals(0, cades.signs);
    }

    @ParameterizedTest @EnumSource(value = CryptoProErrorCode.class, names = {"TIMEOUT", "CANCELLED"})
    void terminalCadesReadFailureDoesNotStartSigning(CryptoProErrorCode code) {
        var stores = new Stores();
        stores.cadesFailure = code;
        var primary = new PrimaryRunner();
        var cades = new Signer();

        assertEquals(code, assertThrows(CryptoProException.class, () ->
                provider(primary, stores, cades, new Signer(), SELECTED)
                        .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST)).code());

        assertEquals(0, primary.signs);
        assertEquals(0, cades.signs);
    }

    @ParameterizedTest @EnumSource(CryptoProErrorCode.class)
    void failureAfterRoutedSigningNeverStartsAnotherSigningAttempt(CryptoProErrorCode code) {
        var stores = new Stores();
        stores.windows = List.of(certificate(SELECTED, SELECTED));
        var primary = new PrimaryRunner();
        var cades = new Signer();
        cades.failure = code;
        var fallback = new Signer();

        assertEquals(code, assertThrows(CryptoProException.class, () ->
                provider(primary, stores, cades, fallback, SELECTED)
                        .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST)).code());

        assertEquals(1, cades.signs);
        assertEquals(0, primary.signs);
        assertEquals(0, fallback.signs);
    }

    @ParameterizedTest @EnumSource(value = CryptoProErrorCode.class,
            names = {"TIMEOUT", "CANCELLED", "PRIVATE_KEY_UNAVAILABLE", "TOKEN_OR_CERTIFICATE_ABSENT", "SIGNING_FAILED"})
    void primarySigningFailuresDoNotTriggerTheNewRoute(CryptoProErrorCode code) {
        var stores = new Stores();
        stores.current = List.of(certificate(SELECTED, SELECTED));
        var primary = new PrimaryRunner();
        primary.failure = code;
        var cades = new Signer();
        var fallback = new Signer();

        assertEquals(code, assertThrows(CryptoProException.class, () ->
                provider(primary, stores, cades, fallback, SELECTED)
                        .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST)).code());

        assertEquals(1, primary.signs);
        assertEquals(0, stores.cadesReads);
        assertEquals(0, cades.signs);
        assertEquals(0, fallback.signs);
    }

    @Test void probesAndRoutedSignatureUseOneDecreasingTimeBudget() throws Exception {
        var stores = new Stores();
        stores.windows = List.of(certificate(SELECTED, SELECTED));
        var cades = new Signer();

        provider(new PrimaryRunner(), stores, cades, new Signer(), SELECTED)
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST);

        assertRemainingBudgets(List.of(stores.budgets.get(0), stores.budgets.get(1), cades.remaining));
    }

    @Test void existingLicenseFallbackReceivesOnlyTheBudgetLeftAfterProbesAndPrimary() throws Exception {
        var stores = new Stores();
        var primary = new PrimaryRunner();
        primary.diagnostic = "cryptcp license unavailable";
        var fallback = new Signer();
        var cades = new Signer();

        provider(primary, stores, cades, fallback, SELECTED)
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST);

        assertEquals(1, primary.signs);
        assertEquals(1, fallback.signs);
        assertEquals(0, cades.signs);
        assertRemainingBudgets(List.of(stores.budgets.get(0), stores.budgets.get(1),
                primary.remaining, fallback.remaining));
    }

    @Test void routedSignerCannotResetTheDeadlineForSubsequentHosts() throws Exception {
        var stores = new Stores();
        stores.windows = List.of(certificate(SELECTED, SELECTED));
        var primary = new PrimaryRunner();
        var cades = new Signer();
        cades.exerciseSubprocessBudget = true;

        provider(primary, stores, cades, new Signer(), SELECTED)
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST);

        assertEquals(3, primary.subprocessBudgets.size());
        assertEquals(Duration.ofSeconds(1), primary.subprocessBudgets.getFirst());
        assertRemainingBudgets(List.of(cades.remaining, primary.subprocessBudgets.get(1),
                primary.subprocessBudgets.get(2)));
        assertEquals(0, primary.signs);
        assertEquals(1, cades.signs);
    }

    @Test void legacyInjectedConstructorDoesNotEnableNewDiscovery() throws Exception {
        var primary = new PrimaryRunner();
        new CryptoProSignatureProvider(primary, "cryptcp", SELECTED, BUDGET)
                .sign(PAYLOAD, ZnackSignatureContext.SIGNATURE_TEST);
        assertEquals(List.of("cryptcp"), primary.commands.stream().map(List::getFirst).toList());
        assertEquals(BUDGET, primary.remaining);
    }

    private static CryptoProSignatureProvider provider(PrimaryRunner primary, Stores stores, Signer cades,
                                                      Signer fallback, String selector) {
        return new CryptoProSignatureProvider(primary, "cryptcp", selector, BUDGET, fallback, stores, cades);
    }

    private static void assertRemainingBudgets(List<Duration> budgets) {
        Duration previous = BUDGET;
        for (Duration remaining : budgets) {
            assertTrue(remaining.isPositive());
            assertTrue(remaining.compareTo(BUDGET) < 0);
            assertTrue(remaining.compareTo(previous) <= 0);
            previous = remaining;
        }
    }

    private static CryptoProCertificateInfo certificate(String selector, String thumbprint) {
        return new CryptoProCertificateInfo(selector, thumbprint, "CN=Synthetic", "CN=Synthetic CA", "",
                null, null, false, "CryptoPro CAdES store", "");
    }

    private static final class Stores implements CryptoProSignatureProvider.CertificateStores {
        List<CryptoProCertificateInfo> current = List.of();
        List<CryptoProCertificateInfo> windows = List.of();
        CryptoProErrorCode currentFailure;
        CryptoProErrorCode cadesFailure;
        int currentReads;
        int cadesReads;
        final List<Duration> budgets = new ArrayList<>();

        @Override public List<CryptoProCertificateInfo> currentUser(Duration remaining, CryptoProCommandRunner boundedRunner) throws CryptoProException {
            currentReads++;
            budgets.add(remaining);
            if (currentFailure != null) throw new CryptoProException(currentFailure, "synthetic read failure");
            return current;
        }

        @Override public List<CryptoProCertificateInfo> cades(Duration remaining, CryptoProCommandRunner boundedRunner) throws CryptoProException {
            cadesReads++;
            budgets.add(remaining);
            if (cadesFailure != null) throw new CryptoProException(cadesFailure, "synthetic read failure");
            return windows;
        }
    }

    private static final class Signer implements CryptoProSignatureProvider.TimedSigner {
        int signs;
        CryptoProErrorCode failure;
        Duration remaining;
        byte[] payload;
        ZnackSignatureContext context;
        boolean exerciseSubprocessBudget;

        @Override public CryptoProSigningResult sign(byte[] payload, ZnackSignatureContext context, Duration remaining,
                                                    CryptoProCommandRunner boundedRunner)
                throws CryptoProException {
            signs++;
            this.remaining = remaining;
            this.payload = payload.clone();
            this.context = context;
            if (failure != null) throw new CryptoProException(failure, "synthetic signing failure");
            if (exerciseSubprocessBudget) {
                boundedRunner.run(List.of("synthetic-host-one"), Duration.ofSeconds(1));
                boundedRunner.run(List.of("synthetic-host-two"), Duration.ofMinutes(1));
                boundedRunner.run(List.of("synthetic-host-three"), Duration.ofMinutes(1));
            }
            try { return new CryptoProSigningResult(cms(), "routed synthetic signer"); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
    }

    private static final class PrimaryRunner extends CryptoProCommandRunner {
        int signs;
        CryptoProErrorCode failure;
        String diagnostic;
        String currentOutput;
        Duration remaining;
        final List<List<String>> commands = new ArrayList<>();
        final List<Duration> subprocessBudgets = new ArrayList<>();

        @Override public String resolve(String override, String command) { return command; }

        @Override public Result run(List<String> command, Duration remaining) throws CryptoProException {
            commands.add(List.copyOf(command));
            if (command.getFirst().startsWith("synthetic-host-")) {
                subprocessBudgets.add(remaining);
                return new Result(0, new byte[0], new byte[0]);
            }
            if (command.contains("-list")) return new Result(0,
                    currentOutput.getBytes(StandardCharsets.UTF_8), new byte[0]);
            assertEquals("cryptcp", command.getFirst());
            signs++;
            this.remaining = remaining;
            if (failure != null) throw new CryptoProException(failure, "synthetic signing failure");
            if (diagnostic != null) return new Result(1, new byte[0], diagnostic.getBytes(StandardCharsets.UTF_8));
            try {
                Files.write(Path.of(command.getLast()), cms());
                return new Result(0, new byte[0], new byte[0]);
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
    }

    private static byte[] cms() throws Exception {
        var digest = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
        var signer = new SignerInfo(new ASN1Integer(1),
                new IssuerAndSerialNumber(new X500Name("CN=Synthetic"), BigInteger.ONE), digest, null,
                new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption), new DEROctetString(new byte[]{1}), null);
        var data = new SignedData(new ASN1Integer(1), new DERSet(digest),
                new ContentInfo(PKCSObjectIdentifiers.data, null), null, null, new DERSet(signer));
        return new ContentInfo(PKCSObjectIdentifiers.signedData, data).getEncoded(ASN1Encoding.DER);
    }
}
