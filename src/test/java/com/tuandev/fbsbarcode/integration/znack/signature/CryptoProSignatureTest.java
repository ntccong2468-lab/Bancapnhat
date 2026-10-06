package com.tuandev.fbsbarcode.integration.znack.signature;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CryptoProSignatureTest {
    @Test void parsesRussianAndEnglishCertmgrAndFiltersUnusable() {
        String output = """
                SHA1 Hash: AABB
                Subject: CN=Example LLC, INN=7701234567
                Issuer: CN=CA
                Not valid after: 01.01.2099 00:00:00
                Private key: present
                Provider: Crypto-Pro GOST

                Отпечаток: CCDD
                Субъект: CN=ООО Истек, ИНН=7812345678
                Издатель: CN=УЦ
                Действителен до: 01.01.2020 00:00:00
                Закрытый ключ: есть
                Провайдер: CryptoPro

                Отпечаток: EEFF
                Субъект: CN=ООО Без ключа, ИНН=7812345679
                Действителен до: 01.01.2099 00:00:00
                Закрытый ключ: нет
                """;
        CryptoProCertificateDiscoveryService service = new CryptoProCertificateDiscoveryService();
        List<CryptoProCertificateInfo> certificates = service.parse(output);
        assertEquals(3, certificates.size());
        assertEquals("7701234567", certificates.getFirst().inn());
        assertEquals(List.of("AABB", "EEFF"), service.usable(certificates, Instant.now()).stream().map(CryptoProCertificateInfo::selector).toList());
    }

    @Test void parsesActualCertmgrListBlockWhereThumbprintFollowsSubject() {
        String output = """
                Certmgr 1.1 (c) "Crypto-Pro", 2007-2021.
                =============================================================================
                1-------
                Issuer              : CN=CryptoPro Test CA
                Subject             : INN=007814508921, CN=Example Owner
                Serial              : 0x1234
                SHA1 Thumbprint     : cd321b87fdabb503829f88db68d893b59a7c5dd3
                Not valid before    : 01/01/2025 00:00:00
                Not valid after     : 01/01/2029 00:00:00
                PrivateKey Link     : Yes
                Container           : HDIMAGE\\\\container
                """;
        CryptoProCertificateInfo certificate = new CryptoProCertificateDiscoveryService().parse(output).getFirst();
        assertEquals("cd321b87fdabb503829f88db68d893b59a7c5dd3", certificate.thumbprint());
        assertEquals("007814508921", certificate.inn());
        assertTrue(certificate.hasPrivateKey());
    }

    @Test void parsesWrappedWindowsSubjectNameAndExpiryWithTimezoneSuffix() {
        String output = """
                1-------
                Subject             : INN=007814508921,
                                      SN=ДИНЬ,
                                      G=ТХИ МАЙ
                SHA1 Thumbprint     : aabbccdd
                Not valid after     : 24.06.2027 13:45:51 UTC
                PrivateKey Link     : Yes
                """;

        CryptoProCertificateInfo certificate = new CryptoProCertificateDiscoveryService().parse(output).getFirst();

        assertEquals("ДИНЬ ТХИ МАЙ", certificate.ownerName());
        assertEquals(LocalDate.of(2027, 6, 24), certificate.validToDate());
        assertEquals("ДИНЬ ТХИ МАЙ / INN 007814508921 / 24.06.2027", certificate.displayName());
    }

    @Test void parsesCertmgr5RussianBlockWhereThumbprintLabelIsSha1Otpechatok() {
        // Certmgr 5.0.x (uMy) emits the mixed "SHA1 отпечаток" label plus "Идентификатор ключа"
        // and "Ссылка на ключ"; the selector must come from the thumbprint, not the key id.
        String output = """
                Certmgr Ver:5.0.13003 OS:Windows CPU:AMD64 (c) "КРИПТО-ПРО", 2007-2024.
                =============================================================================
                1-------
                Издатель            : ИНН ЮЛ=7707329152, CN=Федеральная налоговая служба
                Субъект             : ОГРНИП=323508100561415, ИНН=771004718687, CN=ФАМ ВАН ТАМ, G=ВАН ТАМ, SN=ФАМ
                Серийный номер      : 0x0238D434017FB3FEAB4D71F4D8FBC87175
                SHA1 отпечаток      : 32b818904e2434b26e6541d20e2e277bab112350
                Идентификатор ключа : 93a9e8b1997e1c6edc9e6e9821b48b72ef19a2d9
                Выдан               : 23/10/2025 18:34:25 UTC
                Истекает            : 23/01/2027 18:44:25 UTC
                Ссылка на ключ      : Есть
                Имя провайдера      : Crypto-Pro GOST R 34.10-2012 Cryptographic Service Provider
                =============================================================================
                """;
        CryptoProCertificateInfo certificate = new CryptoProCertificateDiscoveryService().parse(output).getFirst();
        assertEquals("32b818904e2434b26e6541d20e2e277bab112350", certificate.selector());
        assertEquals("32b818904e2434b26e6541d20e2e277bab112350", certificate.thumbprint());
        assertEquals("771004718687", certificate.inn());
        assertEquals(LocalDate.of(2027, 1, 23), certificate.validToDate());
        assertTrue(certificate.hasPrivateKey());
    }

    @Test void decodesCp866CertmgrOutputSoRussianLabelsParse() throws Exception {
        // certmgr on Russian Windows emits its labels in the OEM codepage (cp866). Decoding the raw
        // bytes as cp1251 turns "SHA1 отпечаток" into mojibake, dropping every certificate.
        String block = """
                Certmgr Ver:5.0.13003 OS:Windows CPU:AMD64 (c) "КРИПТО-ПРО", 2007-2024.
                =============================================================================
                1-------
                Издатель            : CN=Федеральная налоговая служба
                Субъект             : ИНН=773396310603, CN=ЛЕ МИНЬ НГОК
                SHA1 отпечаток      : 192ac028f2b1838102f08dfb7ddf001e1bbae9a8
                Выдан               : 14/01/2026 08:01:11 UTC
                Истекает            : 14/04/2027 08:11:11 UTC
                Ссылка на ключ      : Есть
                =============================================================================
                """;
        byte[] cp866 = block.getBytes(java.nio.charset.Charset.forName("IBM866"));
        CryptoProCommandRunner runner = new CryptoProCommandRunner() {
            @Override public String resolve(String override, String command) {
                return "certmgr";
            }

            @Override public Result run(List<String> command, Duration timeout) {
                return new Result(0, cp866, new byte[0]);
            }
        };
        List<CryptoProCertificateInfo> certificates = new CryptoProCertificateDiscoveryService(runner)
                .discover("", "", Duration.ofSeconds(1));
        assertEquals(1, certificates.size());
        assertEquals("192ac028f2b1838102f08dfb7ddf001e1bbae9a8", certificates.getFirst().selector());
        assertEquals("773396310603", certificates.getFirst().inn());
    }

    @Test void treatsCertmgrEmptyStoreExitAsNoCertificatesInsteadOfDiscoveryFailure() throws Exception {
        CryptoProCommandRunner runner = new CryptoProCommandRunner() {
            @Override public String resolve(String override, String command) {
                return "certmgr";
            }

            @Override public Result run(List<String> command, Duration timeout) {
                return new Result(44, new byte[0], "Empty certificate list".getBytes());
            }
        };

        assertTrue(new CryptoProCertificateDiscoveryService(runner)
                .discover("", "", Duration.ofSeconds(1)).isEmpty());
    }

    @Test void signsWithOverrideAndValidatesCms() throws Exception {
        OutputRunner runner = new OutputRunner(cmsFixture());
        CryptoProSigningResult result = new CryptoProSignatureProvider(runner, "cryptcp", "AABB", Duration.ofSeconds(2))
                .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST);
        assertArrayEquals(cmsFixture(), result.cms());
        List<String> args = runner.command;
        assertEquals(List.of("-sign", "-uMy", "-thumbprint", "AABB", "-der", "-detached"), args.subList(0, 6));
        assertFalse(args.contains("-pin"));
        assertFalse(args.contains("-askpin"));
    }

    @Test void rejectsInvalidOutputAndMapsTimeout() throws Exception {
        OutputRunner invalid = new OutputRunner("invalid".getBytes());
        CryptoProException invalidError = assertThrows(CryptoProException.class,
                () -> new CryptoProSignatureProvider(invalid, "cryptcp", "AABB", Duration.ofSeconds(2))
                        .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST));
        assertEquals(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, invalidError.code());

        CryptoProCommandRunner slow = new CryptoProCommandRunner() {
            @Override public String resolve(String override, String command) {
                return "cryptcp";
            }

            @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
                throw new CryptoProException(CryptoProErrorCode.TIMEOUT, "fixture timeout");
            }
        };
        CryptoProException timeout = assertThrows(CryptoProException.class,
                () -> new CryptoProSignatureProvider(slow, "cryptcp", "AABB", Duration.ofMillis(50))
                        .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST));
        assertEquals(CryptoProErrorCode.TIMEOUT, timeout.code());
    }

    @Test void fallsBackToWindowsCadesWhenCryptcpIsMissing() throws Exception {
        CryptoProCommandRunner missingCryptcp = new CryptoProCommandRunner() {
            @Override public String resolve(String override, String command) throws CryptoProException {
                throw new CryptoProException(CryptoProErrorCode.CRYPTCP_MISSING, "fixture missing");
            }
        };
        byte[] fixture = cmsFixture();
        ZnackSignatureProvider fallback = (payload, context) -> new CryptoProSigningResult(fixture, "CAdESCOM");

        CryptoProSigningResult result = new CryptoProSignatureProvider(
                missingCryptcp, "", "AABB", Duration.ofSeconds(2), fallback)
                .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST);

        assertArrayEquals(fixture, result.cms());
        assertEquals("CAdESCOM", result.diagnostic());
    }

    @Test void fallsBackToWindowsCadesWhenCryptcpLicenseIsInvalid() throws Exception {
        CryptoProCommandRunner unlicensedCryptcp = new FailureRunner(
                "Error: License for this product is expired. [ErrorCode: 0x0000065b]");
        byte[] fixture = cmsFixture();
        ZnackSignatureProvider fallback = (payload, context) -> new CryptoProSigningResult(fixture, "CAdESCOM");

        CryptoProSigningResult result = new CryptoProSignatureProvider(
                unlicensedCryptcp, "cryptcp", "AABB", Duration.ofSeconds(2), fallback)
                .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST);

        assertArrayEquals(fixture, result.cms());
        assertEquals("CAdESCOM", result.diagnostic());
    }

    @Test void csptestSignsByThumbprintWithoutInteractivePrompt() throws Exception {
        CsptestRunner runner = new CsptestRunner(cmsFixture());
        CryptoProSigningResult result = new CsptestSignatureProvider(runner, null, "AABB", Duration.ofSeconds(2), null)
                .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST);
        assertArrayEquals(cmsFixture(), result.cms());
        List<String> args = runner.command;
        assertTrue(args.containsAll(List.of("-sfsign", "-sign", "-my", "AABB", "-add", "-cades_strict", "-detached")));
        // Thumbprint goes to -my, so csptest matches exactly one certificate and never prompts "Enter certificate number".
        assertEquals("AABB", args.get(args.indexOf("-my") + 1));
    }

    @Test void csptestFallsBackToCadesWhenCsptestBinaryMissing() throws Exception {
        CryptoProCommandRunner missing = new CryptoProCommandRunner() {
            @Override public String resolve(String override, String command) throws CryptoProException {
                throw new CryptoProException(CryptoProErrorCode.CRYPTOPRO_MISSING, "fixture missing csptest");
            }
        };
        byte[] fixture = cmsFixture();
        ZnackSignatureProvider cades = (payload, context) -> new CryptoProSigningResult(fixture, "CAdESCOM");
        CryptoProSigningResult result = new CsptestSignatureProvider(missing, null, "AABB", Duration.ofSeconds(2), cades)
                .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST);
        assertArrayEquals(fixture, result.cms());
        assertEquals("CAdESCOM", result.diagnostic());
    }

    @Test void csptestTreatsNonZeroErrorCodeWithoutOutputAsFailure() {
        CryptoProCommandRunner errored = new CryptoProCommandRunner() {
            @Override public String resolve(String override, String command) { return "csptest"; }
            @Override public Result run(List<String> command, Duration timeout) {
                return new Result(0, "[ErrorCode: 0x8010006e]".getBytes(), new byte[0]);
            }
        };
        CryptoProException error = assertThrows(CryptoProException.class,
                () -> new CsptestSignatureProvider(errored, null, "AABB", Duration.ofSeconds(2), null)
                        .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST));
        assertNotEquals(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT, error.code());
    }

    @Test void doesNotRetryUnclassifiedCryptcpSigningFailureThroughCades() {
        boolean[] fallbackCalled = {false};
        ZnackSignatureProvider fallback = (payload, context) -> {
            fallbackCalled[0] = true;
            return new CryptoProSigningResult(new byte[0], "unexpected");
        };

        CryptoProException error = assertThrows(CryptoProException.class,
                () -> new CryptoProSignatureProvider(new FailureRunner("Unexpected provider failure"),
                        "cryptcp", "AABB", Duration.ofSeconds(2), fallback)
                        .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST));

        assertEquals(CryptoProErrorCode.SIGNING_FAILED, error.code());
        assertTrue(error.getMessage().contains("Unexpected provider failure"));
        assertFalse(fallbackCalled[0]);
    }

    @Test void windowsCadesSignsThroughGeneratedLocalScriptWithoutPuttingPayloadOnCommandLine() throws Exception {
        WindowsCadesRunner runner = new WindowsCadesRunner(cmsFixture());

        CryptoProSigningResult result = new WindowsCadesSignatureProvider(runner, "AABB", Duration.ofSeconds(2))
                .sign("secret-payload".getBytes(), ZnackSignatureContext.SUZ_POST_BODY);

        assertArrayEquals(cmsFixture(), result.cms());
        assertTrue(runner.script.contains("CAdESCOM.CadesSignedData"));
        assertTrue(runner.script.contains("SignCades"));
        assertTrue(runner.script.contains("$stage = 'sign payload'"));
        assertTrue(runner.script.contains("ProgId = 'CAPICOM.Store'"));
        assertTrue(runner.script.contains("$candidateStore.Open(100)"));
        assertTrue(runner.script.contains("$candidateStore.Open($attempt.Location)"));
        assertTrue(runner.script.contains("try { $store.Close() } catch { }"));
        assertArrayEquals("secret-payload".getBytes(), runner.payload);
        assertTrue(runner.command.contains("true"));
        assertFalse(String.join(" ", runner.command).contains("secret-payload"));
    }

    @Test void windowsCadesFallsBackToNativeVbsHostWhenPowerShellInteropFailsBeforeSigning() throws Exception {
        VbsFallbackRunner runner = new VbsFallbackRunner(cmsFixture());

        CryptoProSigningResult result = new WindowsCadesSignatureProvider(runner, "AABB", Duration.ofSeconds(2))
                .sign("secret-payload".getBytes(), ZnackSignatureContext.SUZ_POST_BODY);

        assertArrayEquals(cmsFixture(), result.cms());
        assertTrue(runner.vbsScript.contains("CreateObject(\"CAdESCOM.CadesSignedData\")"));
        assertTrue(runner.vbsScript.contains("signature = signedData.SignCades"));
        assertTrue(runner.vbsScript.contains("CAdESCOM private-key containers"));
        assertArrayEquals("secret-payload".getBytes(), runner.payload);
        assertTrue(runner.commands.stream().anyMatch(command -> command.getFirst().equals("powershell.exe")));
        assertTrue(runner.commands.stream().anyMatch(command -> command.getFirst().equals("cscript.exe")));
    }

    @Test void windowsCadesMapsMissingComComponent() {
        CryptoProCommandRunner runner = new CryptoProCommandRunner() {
            @Override public Result run(List<String> command, Duration timeout) {
                return new Result(1, new byte[0], "Class not registered: CAdESCOM".getBytes());
            }
        };

        CryptoProException error = assertThrows(CryptoProException.class,
                () -> new WindowsCadesSignatureProvider(runner, "AABB", Duration.ofSeconds(2))
                        .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST));

        assertEquals(CryptoProErrorCode.CADESCOM_MISSING, error.code());
    }

    @Test void windowsCadesDoesNotMisclassifyCertificateStoreOpenFailureAsMissingCertificate() {
        CryptoProCommandRunner runner = new CryptoProCommandRunner() {
            @Override public Result run(List<String> command, Duration timeout) {
                return new Result(1, new byte[0],
                        "CAdESCOM stage 'open current-user My certificate store' failed: Object reference not set".getBytes());
            }
        };

        CryptoProException error = assertThrows(CryptoProException.class,
                () -> new WindowsCadesSignatureProvider(runner, "AABB", Duration.ofSeconds(2))
                        .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST));

        assertEquals(CryptoProErrorCode.SIGNING_FAILED, error.code());
    }

    @Test void windowsCadesMapsSelectedCertificateLookupFailureToCertificateAbsent() {
        CryptoProCommandRunner runner = new CryptoProCommandRunner() {
            @Override public Result run(List<String> command, Duration timeout) {
                return new Result(1, new byte[0],
                        ("CAdESCOM stage 'find selected certificate' failed: Unable to open CryptoPro certificate stores. "
                                + "Store attempts: CAdESCOM current-user My: Object reference not set to an instance of an object.")
                                .getBytes());
            }
        };

        CryptoProException error = assertThrows(CryptoProException.class,
                () -> new WindowsCadesSignatureProvider(runner, "AABB", Duration.ofSeconds(2))
                        .sign("payload".getBytes(), ZnackSignatureContext.SIGNATURE_TEST));

        assertEquals(CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT, error.code());
    }

    @Test void windowsCadesRetriesOnlyFailuresBeforeSigningIn32BitPowerShell() {
        CryptoProCommandRunner.Result storeFailure = new CryptoProCommandRunner.Result(1, new byte[0],
                "CAdESCOM stage 'find selected certificate' failed: Unable to open CryptoPro certificate stores".getBytes());
        CryptoProCommandRunner.Result signedDataFailure = new CryptoProCommandRunner.Result(1, new byte[0],
                "CAdESCOM stage 'create signed-data object' failed: Object reference not set".getBytes());
        CryptoProCommandRunner.Result signingFailure = new CryptoProCommandRunner.Result(1, new byte[0],
                "CAdESCOM stage 'sign payload' failed: Class not registered".getBytes());
        CryptoProCommandRunner.Result outputFailure = new CryptoProCommandRunner.Result(1, new byte[0],
                "CAdESCOM stage 'write signature' failed: access denied".getBytes());

        assertTrue(WindowsCadesSignatureProvider.safeToRetryInOtherPowerShell(storeFailure));
        assertTrue(WindowsCadesSignatureProvider.safeToRetryInOtherPowerShell(signedDataFailure));
        assertFalse(WindowsCadesSignatureProvider.safeToRetryInOtherPowerShell(signingFailure));
        assertFalse(WindowsCadesSignatureProvider.safeToRetryInOtherPowerShell(outputFailure));

        List<List<String>> commands = WindowsCadesSignatureProvider.powerShellCandidates(
                true, Map.of("WINDIR", "C:\\Windows"), "-Command", "probe");
        assertEquals(2, commands.size());
        assertEquals("powershell.exe", commands.getFirst().getFirst());
        assertTrue(commands.getFirst().contains("-Sta"));
        assertTrue(commands.getLast().getFirst().contains("SysWOW64"));

        List<List<String>> cscriptCommands = WindowsCadesSignatureProvider.cscriptCandidates(
                true, Map.of("WINDIR", "C:\\Windows"), "sign.vbs", "payload.bin");
        assertEquals(2, cscriptCommands.size());
        assertEquals("cscript.exe", cscriptCommands.getFirst().getFirst());
        assertTrue(cscriptCommands.getFirst().contains("//Nologo"));
        assertTrue(cscriptCommands.getLast().getFirst().contains("SysWOW64"));
    }

    @Test void certificateSelectionUsesExpiryAndTestSigningValidatesPrivateKey() {
        Instant now = Instant.now();
        CryptoProCertificateInfo expired = new CryptoProCertificateInfo("a", "a", "", "", "", null,
                now.minus(1, ChronoUnit.DAYS), true, "", "");
        CryptoProCertificateInfo noKey = new CryptoProCertificateInfo("b", "b", "", "", "", null,
                now.plus(1, ChronoUnit.DAYS), false, "", "");
        assertFalse(expired.usable(now));
        assertTrue(noKey.usable(now));
    }

    private static final class OutputRunner extends CryptoProCommandRunner {
        private final byte[] output;
        private List<String> command = List.of();

        private OutputRunner(byte[] output) {
            this.output = output;
        }

        @Override public String resolve(String override, String command) {
            return "cryptcp";
        }

        @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
            this.command = List.copyOf(command.subList(1, command.size() - 2));
            try {
                Files.write(Path.of(command.getLast()), output);
                return new Result(0, new byte[0], new byte[0]);
            } catch (Exception e) {
                throw new CryptoProException(CryptoProErrorCode.SIGNING_FAILED, "Could not write fixture signature.", e);
            }
        }
    }

    private static final class CsptestRunner extends CryptoProCommandRunner {
        private final byte[] output;
        private List<String> command = List.of();

        private CsptestRunner(byte[] output) {
            this.output = output;
        }

        @Override public String resolve(String override, String command) {
            return "csptest";
        }

        @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
            this.command = List.copyOf(command);
            try {
                Files.write(Path.of(command.get(command.indexOf("-out") + 1)), output);
                return new Result(0, "[ErrorCode: 0x00000000]".getBytes(), new byte[0]);
            } catch (Exception e) {
                throw new CryptoProException(CryptoProErrorCode.SIGNING_FAILED, "Could not write csptest fixture.", e);
            }
        }
    }

    private static final class FailureRunner extends CryptoProCommandRunner {
        private final String diagnostic;

        private FailureRunner(String diagnostic) {
            this.diagnostic = diagnostic;
        }

        @Override public String resolve(String override, String command) {
            return "cryptcp";
        }

        @Override public Result run(List<String> command, Duration timeout) {
            return new Result(1, new byte[0], diagnostic.getBytes());
        }
    }

    private static final class WindowsCadesRunner extends CryptoProCommandRunner {
        private final byte[] output;
        private List<String> command = List.of();
        private String script = "";
        private byte[] payload = new byte[0];

        private WindowsCadesRunner(byte[] output) {
            this.output = output;
        }

        @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
            this.command = List.copyOf(command);
            try {
                int fileIndex = command.indexOf("-File");
                script = Files.readString(Path.of(command.get(fileIndex + 1)));
                payload = Files.readAllBytes(Path.of(command.get(fileIndex + 2)));
                Files.writeString(Path.of(command.getLast()), Base64.getEncoder().encodeToString(output));
                return new Result(0, new byte[0], new byte[0]);
            } catch (Exception e) {
                throw new CryptoProException(CryptoProErrorCode.SIGNING_FAILED, "Could not run CAdES fixture.", e);
            }
        }
    }

    private static final class VbsFallbackRunner extends CryptoProCommandRunner {
        private final byte[] output;
        private final List<List<String>> commands = new ArrayList<>();
        private String vbsScript = "";
        private byte[] payload = new byte[0];

        private VbsFallbackRunner(byte[] output) {
            this.output = output;
        }

        @Override public Result run(List<String> command, Duration timeout) throws CryptoProException {
            commands.add(List.copyOf(command));
            if (command.getFirst().toLowerCase().contains("powershell")) {
                return new Result(1, new byte[0],
                        "CAdESCOM stage 'create signed-data object' failed: Object reference not set".getBytes());
            }
            try {
                vbsScript = Files.readString(Path.of(command.get(2)));
                payload = Files.readAllBytes(Path.of(command.get(3)));
                Files.writeString(Path.of(command.getLast()), Base64.getEncoder().encodeToString(output));
                return new Result(0, new byte[0], new byte[0]);
            } catch (Exception e) {
                throw new CryptoProException(CryptoProErrorCode.SIGNING_FAILED,
                        "Could not run VBS CAdES fixture.", e);
            }
        }
    }

    private byte[] cmsFixture() throws Exception {
        AlgorithmIdentifier digest = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
        SignerInfo signer = new SignerInfo(new ASN1Integer(1),
                new IssuerAndSerialNumber(new X500Name("CN=Fixture"), BigInteger.ONE), digest, null,
                new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption), new DEROctetString(new byte[]{1}), null);
        SignedData signedData = new SignedData(new ASN1Integer(1), new DERSet(digest),
                new ContentInfo(PKCSObjectIdentifiers.data, null), null, null, new DERSet(signer));
        return new ContentInfo(PKCSObjectIdentifiers.signedData, signedData).getEncoded(ASN1Encoding.DER);
    }
}
