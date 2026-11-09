package com.vncode.app.integration.znack.signature;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.Certificate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Read-only discovery of public certificates, including certificates still on a token.
 * Discovery metadata is not proof of a usable private key or of account ownership. */
final class WindowsCadesCertificateDiscovery {
    static final String RECORD = "VNCODE_CERTIFICATE_DER:";
    static final String END = "VNCODE_CERTIFICATES_END";
    static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $opened = 0
            $attempts = @(
              @{ ProgId = 'CAdESCOM.Store'; Location = 2 },
              @{ ProgId = 'CAdESCOM.Store'; Location = 100 },
              @{ ProgId = 'CAPICOM.Store'; Location = 2 }
            )
            foreach ($attempt in $attempts) {
              $store = $null
              try {
                $store = New-Object -ComObject $attempt.ProgId
                if ($attempt.Location -eq 100) {
                  try { $store.Open(100) } catch { $store.Open(100, 'My', 0) }
                } else {
                  $store.Open($attempt.Location, 'My', 0)
                }
                $records = @()
                for ($i = 1; $i -le $store.Certificates.Count; $i++) {
                  try {
                    $certificate = $store.Certificates.Item($i)
                    $thumbprint = (([string]$certificate.Thumbprint) -replace '\\s', '').ToUpperInvariant()
                    $der = ([string]$certificate.Export(0)) -replace '\\s', ''
                    $records += 'VNCODE_CERTIFICATE_DER:' + $thumbprint + ':' + $der
                  } catch {
                    # One unreadable public certificate must not hide the other certificates.
                  }
                }
                $opened++
                foreach ($record in $records) { [Console]::Out.WriteLine($record) }
              } catch {
                # A missing COM provider/store must not suppress another readable source.
              } finally {
                if ($null -ne $store) { try { $store.Close() } catch { } }
              }
            }
            if ($opened -eq 0) {
              [Console]::Error.WriteLine('CryptoPro certificate stores are unavailable.')
              exit 1
            }
            [Console]::Out.WriteLine('VNCODE_CERTIFICATES_END')
            """;

    private final CryptoProCommandRunner runner;
    private final Map<String, String> environment;

    WindowsCadesCertificateDiscovery(CryptoProCommandRunner runner) {
        this(runner, System.getenv());
    }

    WindowsCadesCertificateDiscovery(CryptoProCommandRunner runner, Map<String, String> environment) {
        this.runner = runner;
        this.environment = Map.copyOf(environment);
    }

    List<CryptoProCertificateInfo> discover(Duration timeout) throws CryptoProException {
        var deadline = new Deadline(timeout);
        String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        var certificates = new ArrayList<CryptoProCertificateInfo>();
        boolean read = false;
        for (var command : WindowsCadesSignatureProvider.powerShellCandidates(true, environment, "-EncodedCommand", encoded)) {
            try {
                var result = runner.run(command, deadline.remaining());
                if (result.exitCode() == 0) {
                    certificates.addAll(parse(result.stdoutText()));
                    read = true;
                }
            } catch (CryptoProException failure) {
                if (failure.code() == CryptoProErrorCode.CANCELLED || failure.code() == CryptoProErrorCode.TIMEOUT) throw failure;
                // Enumeration has no write/sign side effect. Try the other host architecture.
            }
        }
        if (read) return List.copyOf(certificates);
        throw new CryptoProException(CryptoProErrorCode.DISCOVERY_FAILED,
                "Windows CryptoPro certificate stores could not be enumerated.");
    }

    static List<CryptoProCertificateInfo> parse(String output) throws CryptoProException {
        var result = new ArrayList<CryptoProCertificateInfo>();
        boolean complete = false;
        for (String line : output.replace("\r", "").split("\n")) {
            if (line.equals(END)) { complete = true; continue; }
            if (!line.startsWith(RECORD)) continue;
            if (complete || result.size() >= 4096) throw invalid();
            int separator = line.indexOf(':', RECORD.length());
            if (separator < 0) throw invalid();
            String thumbprint = line.substring(RECORD.length(), separator);
            if (!thumbprint.matches("[A-Fa-f0-9]{40}")) throw invalid();
            thumbprint = thumbprint.toUpperCase(java.util.Locale.ROOT);
            String encoded = line.substring(separator + 1);
            if (encoded.isEmpty() || encoded.length() > 128 * 1024) throw invalid();
            try {
                byte[] der = Base64.getDecoder().decode(encoded);
                try (var input = new ASN1InputStream(der)) {
                    Certificate certificate = Certificate.getInstance(input.readObject());
                    if (input.readObject() != null) throw invalid();
                    if (!thumbprint.equalsIgnoreCase(CryptoProSignedCertificateReader.windowsCertificateThumbprint(certificate)))
                        throw invalid();
                    String subject = name(certificate.getSubject());
                    result.add(new CryptoProCertificateInfo(thumbprint, thumbprint, subject,
                            name(certificate.getIssuer()), CryptoProCertificateInfo.enterpriseInn(subject),
                            certificate.getStartDate().getDate().toInstant(), certificate.getEndDate().getDate().toInstant(),
                            false, "CryptoPro CAdES store", ""));
                }
            } catch (Exception failure) {
                // Do not include public certificate/subject data in errors or logs.
                throw invalid();
            }
        }
        if (!complete) throw invalid();
        return List.copyOf(result);
    }

    private static String name(X500Name name) {
        var result = new StringBuilder();
        for (var rdn : name.getRDNs()) for (var attribute : rdn.getTypesAndValues()) {
            if (!result.isEmpty()) result.append(',');
            result.append(attribute.getType().getId()).append('=').append(IETFUtils.valueToString(attribute.getValue()));
        }
        return result.toString();
    }

    private static CryptoProException invalid() {
        return new CryptoProException(CryptoProErrorCode.DISCOVERY_FAILED,
                "Windows CryptoPro returned incomplete or invalid public certificate data.");
    }

    static final class Deadline {
        private final long started = System.nanoTime();
        private final long budget;

        Deadline(Duration timeout) {
            if (timeout == null || timeout.isZero() || timeout.isNegative())
                throw new IllegalArgumentException("CryptoPro timeout must be positive.");
            budget = timeout.toNanos();
        }

        Duration remaining() throws CryptoProException {
            if (Thread.currentThread().isInterrupted())
                throw new CryptoProException(CryptoProErrorCode.CANCELLED, "CryptoPro certificate discovery was cancelled.");
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) throw new CryptoProException(CryptoProErrorCode.TIMEOUT, "CryptoPro certificate discovery timed out.");
            return Duration.ofNanos(remaining);
        }
    }
}
