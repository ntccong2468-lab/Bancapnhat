package com.vncode.app.integration.znack.signature;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CryptoProCertificateDiscoveryServiceTest {
    private static final String OLD = fingerprint(1);
    private static final String RENEWED = fingerprint(2);

    @Test void discoversRenewedTokenCertificateEvenWhenCurrentUserStoreStillContainsOldCertificate() throws Exception {
        var runner = new DiscoveryRunner(certmgr(OLD), records(RENEWED));
        List<CryptoProCertificateInfo> discovered = onWindows(() -> new CryptoProCertificateDiscoveryService(runner)
                .discover("", "", Duration.ofSeconds(2)));

        assertEquals(List.of(OLD, RENEWED), discovered.stream().map(CryptoProCertificateInfo::selector).toList());
        assertTrue(runner.commands.stream().anyMatch(command -> command.contains("-EncodedCommand")),
                "A nonempty uMy store must not suppress token discovery.");
        var renewed = discovered.get(1);
        assertEquals("7707083893", renewed.inn());
        assertEquals("Fixture company", renewed.ownerName());
        assertFalse(renewed.hasPrivateKey(), "Discovery must not manufacture CMS/private-key proof.");
        assertEquals("CryptoPro CAdES store", renewed.provider());
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), renewed.validTo());
    }

    @Test void deduplicatesSameCertificateAcrossStoresAndNormalizesThumbprintForComparison() throws Exception {
        var spaced = new StringBuilder();
        for (int i = 0; i < OLD.length(); i += 2) spaced.append(OLD.substring(i, i + 2).toLowerCase(java.util.Locale.ROOT)).append(' ');
        var runner = new DiscoveryRunner(certmgr(spaced.toString()), records(OLD, RENEWED, RENEWED.toLowerCase(java.util.Locale.ROOT)));
        var discovered = onWindows(() -> new CryptoProCertificateDiscoveryService(runner).discover("", "", Duration.ofSeconds(2)));
        assertEquals(2, discovered.size());
        assertEquals(OLD.toLowerCase(), discovered.getFirst().selector());
        assertEquals(RENEWED, discovered.getLast().selector());
    }

    @Test void preservesDistinctAliasesWhenNoActualThumbprintIsAvailable() throws Exception {
        var aliases = List.of("Token Alias", "TOKEN ALIAS", "TokenAlias");
        var output = new StringBuilder();
        for (var alias : aliases) output.append("selector: ").append(alias).append("\nSubject: CN=Fixture\n");
        var runner = new DiscoveryRunner(success(output.toString()), records());
        var discovered = onWindows(() -> new CryptoProCertificateDiscoveryService(runner)
                .discover("", "", Duration.ofSeconds(2)));
        assertEquals(aliases, discovered.stream().map(CryptoProCertificateInfo::selector).toList());
    }

    @Test void discoversTokenCertificateWhenCertmgrIsMissingOrStoreIsEmpty() throws Exception {
        for (var result : List.of(new CryptoProCommandRunner.Result(44, new byte[0], "Empty certificate list".getBytes(StandardCharsets.UTF_8)),
                new CryptoProCommandRunner.Result(0, new byte[0], new byte[0]))) {
            var runner = new DiscoveryRunner(result, records(RENEWED));
            assertEquals(RENEWED, onWindows(() -> new CryptoProCertificateDiscoveryService(runner)
                    .discover("", "", Duration.ofSeconds(2))).getFirst().selector());
        }
        var missing = new DiscoveryRunner(null, records(RENEWED));
        assertEquals(RENEWED, onWindows(() -> new CryptoProCertificateDiscoveryService(missing)
                .discover("", "", Duration.ofSeconds(2))).getFirst().selector());
    }

    @Test void preservesCertmgrOnlyInstallWhenComIsMissingOrReturnsMalformedData() throws Exception {
        for (var response : List.of(new CryptoProCommandRunner.Result(1, new byte[0], new byte[0]),
                success("VNCODE_CERTIFICATE_DER:malformed"))) {
            var runner = new DiscoveryRunner(certmgr(OLD), response);
            assertEquals(OLD, onWindows(() -> new CryptoProCertificateDiscoveryService(runner)
                    .discover("", "", Duration.ofSeconds(2))).getFirst().selector());
        }
    }

    @Test void preservesCancellationAndTimeoutEvenWhenCertmgrAlreadyReturnedAnOldCertificate() throws Exception {
        for (var code : List.of(CryptoProErrorCode.CANCELLED, CryptoProErrorCode.TIMEOUT)) {
            var runner = new DiscoveryRunner(certmgr(OLD), records(RENEWED)) {
                @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
                    if (command.contains("-EncodedCommand")) throw new CryptoProException(code, "Fixture cancellation/timeout");
                    return super.run(command, timeout);
                }
            };
            var failure = assertThrows(CryptoProException.class, () -> onWindows(() -> new CryptoProCertificateDiscoveryService(runner)
                    .discover("", "", Duration.ofSeconds(2))));
            assertEquals(code, failure.code());
            assertEquals(1, runner.commands.size(), "Cancellation/timeout must not start another source.");
        }
    }

    @Test void sharesOneTimeBudgetAcrossCertmgrAndAllReadOnlyHosts() throws Exception {
        var durations = new ArrayList<Duration>();
        var runner = new DiscoveryRunner(certmgr(OLD), records(RENEWED)) {
            @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
                durations.add(timeout);
                if (!command.contains("-EncodedCommand")) elapse(Duration.ofMillis(30));
                return super.run(command, timeout);
            }
        };
        onWindows(() -> new CryptoProCertificateDiscoveryService(runner).discover("", "", Duration.ofSeconds(2)));
        assertTrue(durations.size() >= 2);
        assertTrue(durations.get(1).compareTo(durations.getFirst().minusMillis(10)) < 0,
                "COM enumeration must receive the remaining budget, not a fresh full timeout.");

        var hostDurations = new ArrayList<Duration>();
        var architectureRunner = new DiscoveryRunner(null, records(RENEWED)) {
            @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
                hostDurations.add(timeout);
                elapse(Duration.ofMillis(20));
                return super.run(command, timeout);
            }
        };
        new WindowsCadesCertificateDiscovery(architectureRunner, Map.of("WINDIR", "C:\\Windows")).discover(Duration.ofSeconds(2));
        assertEquals(2, hostDurations.size());
        assertTrue(hostDurations.getLast().compareTo(hostDurations.getFirst().minusMillis(5)) < 0);
    }

    @Test void readsOtherHostArchitectureEvenWhenFirstHostSuccessfullyFindsOnlyOldCertificates() throws Exception {
        var oldRecords = records(OLD);
        var renewedRecords = records(RENEWED);
        var runner = new DiscoveryRunner(null, oldRecords) {
            @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
                commands.add(command);
                return command.getFirst().contains("SysWOW64") ? renewedRecords : oldRecords;
            }
        };
        var discovered = new WindowsCadesCertificateDiscovery(runner, Map.of("WINDIR", "C:\\Windows"))
                .discover(Duration.ofSeconds(2));
        assertEquals(List.of(OLD, RENEWED), discovered.stream().map(CryptoProCertificateInfo::selector).toList());
        assertEquals(2, runner.commands.size());
    }

    @Test void rejectsUnstructuredIncompleteOrInvalidCertificatesWithoutLeakingPayload() {
        for (var output : List.of("", WindowsCadesCertificateDiscovery.RECORD + OLD + ":AQID\n" + WindowsCadesCertificateDiscovery.END,
                WindowsCadesCertificateDiscovery.RECORD + "bad-thumbprint:" + "Confidential public certificate",
                WindowsCadesCertificateDiscovery.END + "\n" + WindowsCadesCertificateDiscovery.RECORD + OLD + ":AQID")) {
            var error = assertThrows(CryptoProException.class, () -> WindowsCadesCertificateDiscovery.parse(output));
            assertEquals(CryptoProErrorCode.DISCOVERY_FAILED, error.code());
            assertFalse(error.getMessage().contains(OLD));
            assertFalse(error.getMessage().contains("Confidential"));
        }
    }

    @Test void rejectsThumbprintOfAnotherPublicCertificateBeforeOfferingItForSelection() throws Exception {
        String output = WindowsCadesCertificateDiscovery.RECORD + OLD + ':'
                + Base64.getEncoder().encodeToString(certificate(2)) + '\n' + WindowsCadesCertificateDiscovery.END;
        var failure = assertThrows(CryptoProException.class, () -> WindowsCadesCertificateDiscovery.parse(output));
        assertEquals(CryptoProErrorCode.DISCOVERY_FAILED, failure.code());
        assertFalse(failure.getMessage().contains(OLD));
        assertFalse(failure.getMessage().contains("Fixture company"));
    }

    @Test void discoveryScriptIsReadOnlyAndParsesWithActualWindowsPowerShellWhenAvailable() throws Exception {
        String script = WindowsCadesCertificateDiscovery.SCRIPT;
        assertTrue(script.contains("Location = 2"));
        assertTrue(script.contains("Location = 100"));
        assertTrue(script.contains("CAPICOM.Store"));
        assertTrue(script.contains("$certificate.Export(0)"));
        assertTrue(script.contains("'My', 0"), "Normal stores must be opened read-only.");
        assertFalse(script.contains("SignCades"));
        assertFalse(script.contains(".Add("));
        assertFalse(script.contains(".Import("));
        assertFalse(script.contains("PrivateKey"));
        if (!WindowsCadesSignatureProvider.isWindows()) return;
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        String parser = "$source=[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('" + encodedScript + "'));"
                + "$tokens=$null;$errors=$null;[void][Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors);"
                + "if($errors.Count -ne 0){[Console]::Error.WriteLine('Invalid discovery script');exit 1}";
        String encodedParser = Base64.getEncoder().encodeToString(parser.getBytes(StandardCharsets.UTF_16LE));
        var command = WindowsCadesSignatureProvider.powerShellCandidates(true, System.getenv(), "-EncodedCommand", encodedParser).getFirst();
        var result = new CryptoProCommandRunner().run(command, Duration.ofSeconds(10));
        assertEquals(0, result.exitCode(), result::diagnostic);
    }

    @Test void actualWindowsScriptContinuesPastOneUnreadablePublicCertificateUsingSyntheticComObjects() throws Exception {
        String script = WindowsCadesCertificateDiscovery.SCRIPT;
        assertTrue(script.contains("One unreadable public certificate must not hide"));
        if (!WindowsCadesSignatureProvider.isWindows()) return;
        String first = Base64.getEncoder().encodeToString(certificate(1));
        String last = Base64.getEncoder().encodeToString(certificate(2));
        String fixture = """
                $ProgressPreference = 'SilentlyContinue'
                $script:fixtureCalls = 0
                function Fixture-Certificate([string]$thumbprint, [string]$encoded, [bool]$broken) {
                  $item = [pscustomobject]@{ Thumbprint = $thumbprint; Encoded = $encoded; Broken = $broken }
                  Add-Member -InputObject $item -MemberType ScriptMethod -Name Export -Value {
                    param($encoding)
                    if ($this.Broken) { throw 'Synthetic unreadable public certificate' }
                    if ($encoding -ne 0) { throw 'Only public certificate export is allowed' }
                    return $this.Encoded
                  }
                  return $item
                }
                function New-Object {
                  param([string]$ComObject)
                  if ($ComObject -notin @('CAdESCOM.Store', 'CAPICOM.Store')) { throw 'Unexpected COM object' }
                  $values = @()
                  if ($script:fixtureCalls -eq 0) {
                    $values = @(
                      (Fixture-Certificate '%s' '%s' $false),
                      (Fixture-Certificate 'unreadable' '' $true),
                      (Fixture-Certificate '%s' '%s' $false)
                    )
                  }
                  $script:fixtureCalls++
                  $collection = [pscustomobject]@{ Count = $values.Count; Values = $values }
                  Add-Member -InputObject $collection -MemberType ScriptMethod -Name Item -Value {
                    param($index)
                    return $this.Values[$index - 1]
                  }
                  $store = [pscustomobject]@{ Certificates = $collection }
                  Add-Member -InputObject $store -MemberType ScriptMethod -Name Open -Value {
                    param($location, $name, $flags)
                    if ($location -notin @(2,100)) { throw 'Unexpected store scope' }
                    if ($null -ne $flags -and $flags -ne 0) { throw 'Store must be read-only' }
                  }
                  Add-Member -InputObject $store -MemberType ScriptMethod -Name Close -Value { }
                  return $store
                }
                """.formatted(OLD, first, RENEWED, last);
        String encoded = Base64.getEncoder().encodeToString((fixture + script).getBytes(StandardCharsets.UTF_16LE));
        var command = WindowsCadesSignatureProvider.powerShellCandidates(true, System.getenv(), "-EncodedCommand", encoded).getFirst();
        var result = new CryptoProCommandRunner().run(command, Duration.ofSeconds(10));
        assertEquals(0, result.exitCode(), result::diagnostic);
        assertEquals(List.of(OLD, RENEWED), WindowsCadesCertificateDiscovery.parse(result.stdoutText()).stream()
                .map(CryptoProCertificateInfo::selector).toList());
        assertEquals(0, result.stderr().length, "Read-only fixture must not log certificate identities or export errors.");
    }

    private static CryptoProCommandRunner.Result certmgr(String thumbprint) {
        return success("SHA1 Thumbprint: " + thumbprint + "\nSubject: CN=Old cert, INNLE=7707083893\nNot valid after: 01.01.2027\n");
    }

    private static CryptoProCommandRunner.Result records(String... thumbprints) throws Exception {
        var output = new StringBuilder();
        for (var thumbprint : thumbprints) {
            int serial = OLD.equalsIgnoreCase(thumbprint) ? 1 : RENEWED.equalsIgnoreCase(thumbprint) ? 2 : 0;
            if (serial == 0) throw new IllegalArgumentException("Unknown synthetic certificate");
            output.append(WindowsCadesCertificateDiscovery.RECORD).append(thumbprint).append(':')
                    .append(Base64.getEncoder().encodeToString(certificate(serial))).append('\n');
        }
        output.append(WindowsCadesCertificateDiscovery.END).append('\n');
        return success(output.toString());
    }

    private static byte[] certificate(int serial) throws Exception {
        var signature = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);
        var generator = new V3TBSCertificateGenerator();
        generator.setSerialNumber(new ASN1Integer(serial));
        generator.setSignature(signature);
        generator.setIssuer(new X500Name("CN=Fixture CA"));
        generator.setSubject(new X500Name("CN=Fixture signer,O=Fixture company,1.2.643.100.4=7707083893"));
        generator.setStartDate(new Time(Date.from(Instant.parse("2026-01-01T00:00:00Z"))));
        generator.setEndDate(new Time(Date.from(Instant.parse("2027-01-01T00:00:00Z"))));
        generator.setSubjectPublicKeyInfo(new SubjectPublicKeyInfo(new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE), new byte[] {1, 2, 3}));
        return Certificate.getInstance(new DERSequence(new ASN1Encodable[] {generator.generateTBSCertificate(), signature, new DERBitString(new byte[] {1})})).getEncoded("DER");
    }

    private static String fingerprint(int serial) {
        try {
            return CryptoProSignedCertificateReader.windowsCertificateThumbprint(Certificate.getInstance(certificate(serial)))
                    .toUpperCase(java.util.Locale.ROOT);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void elapse(Duration duration) {
        long started = System.nanoTime(), budget = duration.toNanos();
        long remaining;
        while ((remaining = budget - (System.nanoTime() - started)) > 0) LockSupport.parkNanos(remaining);
    }

    private static CryptoProCommandRunner.Result success(String stdout) {
        return new CryptoProCommandRunner.Result(0, stdout.getBytes(StandardCharsets.UTF_8), new byte[0]);
    }

    private static class DiscoveryRunner extends CryptoProCommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        private final Result certmgrResult;
        private final Result comResult;

        DiscoveryRunner(Result certmgrResult, Result comResult) {
            this.certmgrResult = certmgrResult;
            this.comResult = comResult;
        }

        @Override public String resolve(String override, String command) throws CryptoProException {
            if (certmgrResult == null) throw new CryptoProException(CryptoProErrorCode.CERTMGR_MISSING, "Fixture missing certmgr");
            return "certmgr";
        }

        @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
            commands.add(command);
            return command.contains("-EncodedCommand") ? comResult : certmgrResult;
        }
    }

    @FunctionalInterface private interface Checked<T> { T run() throws Exception; }

    private static <T> T onWindows(Checked<T> work) throws Exception {
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            return work.run();
        } finally {
            if (previous == null) System.clearProperty("os.name"); else System.setProperty("os.name", previous);
        }
    }
}
