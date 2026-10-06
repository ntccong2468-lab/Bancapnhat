package com.tuandev.fbsbarcode.integration.znack.signature;

import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class WindowsCadesSignatureProvider implements ZnackSignatureProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowsCadesSignatureProvider.class);
    private static final String PROBE_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $signedData = New-Object -ComObject CAdESCOM.CadesSignedData
            if ($null -eq $signedData) { throw 'CAdESCOM.CadesSignedData is unavailable.' }
            """;
    private static final String VBS_PROBE_SCRIPT = """
            On Error Resume Next
            Dim signedData
            Set signedData = CreateObject("CAdESCOM.CadesSignedData")
            If Err.Number <> 0 Or signedData Is Nothing Then
              WScript.StdErr.WriteLine "CAdESCOM.CadesSignedData is unavailable: " & Err.Description
              WScript.Quit 1
            End If
            """;
    private static final String SIGN_SCRIPT = """
            param(
              [string]$InputPath,
              [string]$Thumbprint,
              [string]$Detached,
              [string]$OutputPath
            )
            $ErrorActionPreference = 'Stop'
            $store = $null
            $certificate = $null
            $stage = 'find selected certificate'
            try {
              $normalized = ($Thumbprint -replace '\\s', '').ToUpperInvariant()
              $openedAnyStore = $false
              $storeErrors = New-Object System.Collections.Generic.List[string]
              $attempts = @(
                @{ Label = 'CAdESCOM current-user My'; ProgId = 'CAdESCOM.Store'; Location = 2 },
                @{ Label = 'CAdESCOM private-key containers'; ProgId = 'CAdESCOM.Store'; Location = 100 },
                @{ Label = 'CAPICOM current-user My'; ProgId = 'CAPICOM.Store'; Location = 2 }
              )
              foreach ($attempt in $attempts) {
                $candidateStore = $null
                try {
                  $candidateStore = New-Object -ComObject $attempt.ProgId
                  if ($attempt.Location -eq 100) {
                    try { $candidateStore.Open(100) } catch { $candidateStore.Open(100, 'My', 2) }
                  } else {
                    try { $candidateStore.Open($attempt.Location) } catch { $candidateStore.Open($attempt.Location, 'My', 2) }
                  }
                  $openedAnyStore = $true
                  for ($i = 1; $i -le $candidateStore.Certificates.Count; $i++) {
                    $candidate = $candidateStore.Certificates.Item($i)
                    if ((($candidate.Thumbprint -replace '\\s', '').ToUpperInvariant()) -eq $normalized) {
                      $store = $candidateStore
                      $certificate = $candidate
                      break
                    }
                  }
                  if ($null -ne $certificate) { break }
                  $storeErrors.Add("$($attempt.Label): selected certificate not found")
                } catch {
                  $storeErrors.Add("$($attempt.Label): $($_.Exception.Message)")
                } finally {
                  if ($null -ne $candidateStore -and $candidateStore -ne $store) {
                    try { $candidateStore.Close() } catch { }
                  }
                }
              }
              if ($null -eq $certificate) {
                $summary = [string]::Join('; ', $storeErrors)
                if ($openedAnyStore) { throw "Selected certificate was not found. Store attempts: $summary" }
                throw "Unable to open CryptoPro certificate stores. Store attempts: $summary"
              }
              $stage = 'create signer'
              $signer = New-Object -ComObject CAdESCOM.CPSigner
              $stage = 'assign certificate to signer'
              $signer.Certificate = $certificate
              $stage = 'create signed-data object'
              $signedData = New-Object -ComObject CAdESCOM.CadesSignedData
              $signedData.ContentEncoding = 1
              $stage = 'load payload'
              $signedData.Content = [Convert]::ToBase64String([IO.File]::ReadAllBytes($InputPath))
              $stage = 'sign payload'
              $signature = $signedData.SignCades($signer, 1, ($Detached -eq 'true'))
              $stage = 'write signature'
              [IO.File]::WriteAllText($OutputPath, $signature, [Text.Encoding]::ASCII)
            } catch {
              throw "CAdESCOM stage '$stage' failed: $($_.Exception.Message)"
            } finally {
              if ($null -ne $store) {
                try { $store.Close() } catch { }
              }
            }
            """;
    private static final String VBS_SIGN_SCRIPT = """
            Option Explicit
            Dim inputPath, thumbprint, detached, outputPath
            inputPath = WScript.Arguments(0)
            thumbprint = NormalizeThumbprint(WScript.Arguments(1))
            detached = LCase(WScript.Arguments(2)) = "true"
            outputPath = WScript.Arguments(3)

            Dim store, certificate, signer, signedData, signature, storeErrors, stage
            Set store = Nothing
            Set certificate = Nothing
            storeErrors = ""
            stage = "find selected certificate"

            FindCertificate "CAdESCOM current-user My", "CAdESCOM.Store", 2, thumbprint, store, certificate, storeErrors
            If certificate Is Nothing Then FindCertificate "CAdESCOM private-key containers", "CAdESCOM.Store", 100, thumbprint, store, certificate, storeErrors
            If certificate Is Nothing Then FindCertificate "CAPICOM current-user My", "CAPICOM.Store", 2, thumbprint, store, certificate, storeErrors
            If certificate Is Nothing Then Fail stage, "Selected certificate was not found. Store attempts: " & storeErrors

            On Error Resume Next
            Err.Clear
            stage = "create signer"
            Set signer = CreateObject("CAdESCOM.CPSigner")
            CheckStage stage

            Err.Clear
            stage = "assign certificate to signer"
            signer.Certificate = certificate
            CheckStage stage

            Err.Clear
            stage = "create signed-data object"
            Set signedData = CreateObject("CAdESCOM.CadesSignedData")
            CheckStage stage

            Err.Clear
            stage = "load payload"
            signedData.ContentEncoding = 1
            signedData.Content = ReadBase64File(inputPath)
            CheckStage stage

            Err.Clear
            stage = "sign payload"
            signature = signedData.SignCades(signer, 1, detached)
            CheckStage stage

            Err.Clear
            stage = "write signature"
            WriteAsciiFile outputPath, signature
            CheckStage stage
            If Not store Is Nothing Then store.Close
            Err.Clear
            WScript.Quit 0

            Sub FindCertificate(label, progId, location, normalized, ByRef selectedStore, ByRef selectedCertificate, ByRef errors)
              On Error Resume Next
              Dim candidateStore, candidate, openError
              Set candidateStore = Nothing
              Err.Clear
              Set candidateStore = CreateObject(progId)
              If Err.Number <> 0 Or candidateStore Is Nothing Then
                AppendError errors, label & ": " & Err.Description
                Err.Clear
                Exit Sub
              End If

              Err.Clear
              candidateStore.Open location
              If Err.Number <> 0 Then
                openError = Err.Description
                Err.Clear
                candidateStore.Open location, "My", 2
                If Err.Number <> 0 Then
                  AppendError errors, label & ": " & openError & "; " & Err.Description
                  Err.Clear
                  candidateStore.Close
                  Exit Sub
                End If
              End If

              For Each candidate In candidateStore.Certificates
                If NormalizeThumbprint(candidate.Thumbprint) = normalized Then
                  Set selectedStore = candidateStore
                  Set selectedCertificate = candidate
                  Exit Sub
                End If
              Next
              AppendError errors, label & ": selected certificate not found"
              candidateStore.Close
              Err.Clear
            End Sub

            Function NormalizeThumbprint(value)
              NormalizeThumbprint = UCase(Replace(Replace(Replace(CStr(value), " ", ""), vbTab, ""), vbCrLf, ""))
            End Function

            Function ReadBase64File(path)
              On Error Resume Next
              Dim stream, document, node
              Set stream = CreateObject("ADODB.Stream")
              stream.Type = 1
              stream.Open
              stream.LoadFromFile path
              Set document = CreateObject("Msxml2.DOMDocument.6.0")
              Set node = document.createElement("base64")
              node.dataType = "bin.base64"
              node.nodeTypedValue = stream.Read
              stream.Close
              ReadBase64File = Replace(Replace(node.Text, vbCr, ""), vbLf, "")
            End Function

            Sub WriteAsciiFile(path, content)
              On Error Resume Next
              Dim fileSystem, output
              Set fileSystem = CreateObject("Scripting.FileSystemObject")
              Set output = fileSystem.CreateTextFile(path, True, False)
              output.Write content
              output.Close
            End Sub

            Sub AppendError(ByRef errors, value)
              If Len(errors) > 0 Then errors = errors & "; "
              errors = errors & value
            End Sub

            Sub CheckStage(currentStage)
              If Err.Number <> 0 Then
                Dim message
                message = Err.Description
                Err.Clear
                Fail currentStage, message
              End If
            End Sub

            Sub Fail(currentStage, message)
              WScript.StdErr.WriteLine "CAdESCOM stage '" & currentStage & "' failed: " & message
              WScript.Quit 1
            End Sub
            """;

    private final CryptoProCommandRunner runner;
    private final String certificateSelector;
    private final Duration timeout;

    WindowsCadesSignatureProvider(String certificateSelector, Duration timeout) {
        this(new CryptoProCommandRunner(), certificateSelector, timeout);
    }

    WindowsCadesSignatureProvider(CryptoProCommandRunner runner, String certificateSelector, Duration timeout) {
        this.runner = runner;
        this.certificateSelector = certificateSelector == null ? "" : certificateSelector.trim();
        this.timeout = timeout;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static void requireAvailable(Duration timeout) throws CryptoProException {
        CryptoProCommandRunner.Result result;
        try {
            result = runWithPowerShellFallback(new CryptoProCommandRunner(), "-Command",
                    new String[]{PROBE_SCRIPT}, timeout);
            if (result.exitCode() != 0 && safeToRetryInOtherPowerShell(result)) {
                Path probe = Files.createTempFile("wcode-cades-probe-", ".vbs");
                try {
                    Files.writeString(probe, VBS_PROBE_SCRIPT, StandardCharsets.UTF_8);
                    result = runWithCscriptFallback(new CryptoProCommandRunner(),
                            new String[]{probe.toString()}, timeout);
                } finally {
                    Files.deleteIfExists(probe);
                }
            }
        } catch (CryptoProException error) {
            throw unavailable(error);
        } catch (Exception error) {
            throw unavailable(new CryptoProException(CryptoProErrorCode.CADESCOM_MISSING,
                    "Could not probe CryptoPro CAdESCOM.", error));
        }
        if (result.exitCode() != 0) throw unavailable(result);
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
        Path powerShellScript = null;
        Path vbsScript = null;
        try {
            workDir = Files.createTempDirectory("wcode-cades-");
            input = workDir.resolve("payload.bin");
            output = workDir.resolve("signature.p7s");
            powerShellScript = workDir.resolve("sign.ps1");
            vbsScript = workDir.resolve("sign.vbs");
            Files.write(input, payload);
            Files.writeString(powerShellScript, SIGN_SCRIPT, StandardCharsets.UTF_8);
            Files.writeString(vbsScript, VBS_SIGN_SCRIPT, StandardCharsets.UTF_8);
            CryptoProCommandRunner.Result result = runWithPowerShellFallback(runner, "-File", new String[]{powerShellScript.toString(),
                    input.toString(), certificateSelector, Boolean.toString(context.detached()), output.toString()}, timeout);
            if (result.exitCode() != 0 && safeToRetryInOtherPowerShell(result)) {
                try {
                    CryptoProCommandRunner.Result vbsResult = runWithCscriptFallback(runner, new String[]{vbsScript.toString(),
                            input.toString(), certificateSelector, Boolean.toString(context.detached()), output.toString()}, timeout);
                    result = vbsResult.exitCode() == 0 ? vbsResult : combined(result, vbsResult);
                } catch (CryptoProException vbsUnavailable) {
                    if (vbsUnavailable.code() != CryptoProErrorCode.CRYPTOPRO_MISSING) throw vbsUnavailable;
                    result = combined(result, new CryptoProCommandRunner.Result(1, new byte[0],
                            ZnackSanitizer.error(vbsUnavailable).getBytes(StandardCharsets.UTF_8)));
                }
            }
            if (result.exitCode() != 0) throw failure(result);
            byte[] raw = Files.isRegularFile(output) ? Files.readAllBytes(output) : new byte[0];
            return new CryptoProSigningResult(CryptoProSignatureProvider.cms(raw), result.diagnostic());
        } catch (CryptoProException error) {
            if (error.code() == CryptoProErrorCode.CRYPTOPRO_MISSING) error = unavailable(error);
            LOGGER.error("CAdESCOM signing failed. code={}, details={}", error.code(), ZnackSanitizer.error(error));
            throw error;
        } catch (Exception error) {
            CryptoProException failure = new CryptoProException(CryptoProErrorCode.SIGNING_FAILED, "CAdESCOM signing failed.", error);
            LOGGER.error("CAdESCOM signing failed. code={}, details={}", failure.code(), ZnackSanitizer.error(failure));
            throw failure;
        } finally {
            try { if (input != null) Files.deleteIfExists(input); } catch (Exception ignored) {}
            try { if (output != null) Files.deleteIfExists(output); } catch (Exception ignored) {}
            try { if (powerShellScript != null) Files.deleteIfExists(powerShellScript); } catch (Exception ignored) {}
            try { if (vbsScript != null) Files.deleteIfExists(vbsScript); } catch (Exception ignored) {}
            try { if (workDir != null) Files.deleteIfExists(workDir); } catch (Exception ignored) {}
        }
    }

    private CryptoProException failure(CryptoProCommandRunner.Result result) {
        String diagnostic = result.diagnostic().toLowerCase(Locale.ROOT);
        CryptoProErrorCode code = diagnostic.contains("class not registered") || diagnostic.contains("класс не зарегистрирован")
                || diagnostic.contains("0x80040154") || diagnostic.contains("com class factory")
                || diagnostic.contains("cadescom") && diagnostic.contains("unavailable")
                ? CryptoProErrorCode.CADESCOM_MISSING
                : diagnostic.contains("cancel") || diagnostic.contains("отмен")
                ? CryptoProErrorCode.CANCELLED
                : diagnostic.contains("expired") || diagnostic.contains("истек")
                ? CryptoProErrorCode.CERTIFICATE_EXPIRED
                : diagnostic.contains("private key") || diagnostic.contains("закрыт")
                ? CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE
                : diagnostic.contains("selected certificate was not found")
                || diagnostic.contains("certificate not found") || diagnostic.contains("сертификат не найден")
                || diagnostic.contains("stage 'find selected certificate'")
                ? CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT : CryptoProErrorCode.SIGNING_FAILED;
        return new CryptoProException(code, "CAdESCOM signing failed (exit " + result.exitCode() + "): " + result.diagnostic());
    }

    private static CryptoProException unavailable(CryptoProCommandRunner.Result result) {
        return new CryptoProException(CryptoProErrorCode.CADESCOM_MISSING,
                "CryptoPro CAdESCOM signing component is unavailable: " + result.diagnostic());
    }

    private static CryptoProException unavailable(CryptoProException error) {
        return new CryptoProException(CryptoProErrorCode.CADESCOM_MISSING,
                "CryptoPro CAdESCOM signing component is unavailable.", error);
    }

    private static CryptoProCommandRunner.Result runWithPowerShellFallback(CryptoProCommandRunner runner, String mode,
                                                                           String[] arguments, Duration timeout)
            throws CryptoProException {
        return runWithHostFallback(runner, powerShellCandidates(mode, arguments), timeout);
    }

    private static CryptoProCommandRunner.Result runWithCscriptFallback(CryptoProCommandRunner runner,
                                                                        String[] arguments, Duration timeout)
            throws CryptoProException {
        return runWithHostFallback(runner, cscriptCandidates(isWindows(), System.getenv(), arguments), timeout);
    }

    private static CryptoProCommandRunner.Result runWithHostFallback(CryptoProCommandRunner runner,
                                                                     List<List<String>> commands, Duration timeout)
            throws CryptoProException {
        CryptoProCommandRunner.Result lastResult = null;
        CryptoProException lastError = null;
        for (List<String> command : commands) {
            try {
                CryptoProCommandRunner.Result result = runner.run(command, timeout);
                if (result.exitCode() == 0) return result;
                CryptoProCommandRunner.Result labeled = labeled(command.getFirst(), result);
                if (!safeToRetryInOtherPowerShell(result)) return labeled;
                lastResult = lastResult == null ? labeled : combined(lastResult, labeled);
            } catch (CryptoProException error) {
                if (error.code() != CryptoProErrorCode.CRYPTOPRO_MISSING) throw error;
                lastError = error;
            }
        }
        if (lastResult != null) return lastResult;
        throw lastError == null
                ? new CryptoProException(CryptoProErrorCode.CRYPTOPRO_MISSING, "Windows PowerShell was not found.")
                : lastError;
    }

    private static CryptoProCommandRunner.Result labeled(String host, CryptoProCommandRunner.Result result) {
        String diagnostic = "Windows signing host " + host + ": " + result.diagnostic();
        return new CryptoProCommandRunner.Result(result.exitCode(), new byte[0],
                diagnostic.getBytes(StandardCharsets.UTF_8));
    }

    private static CryptoProCommandRunner.Result combined(CryptoProCommandRunner.Result first,
                                                           CryptoProCommandRunner.Result second) {
        String diagnostic = "Previous Windows signing host: " + first.diagnostic()
                + System.lineSeparator() + "Last Windows signing host: " + second.diagnostic();
        return new CryptoProCommandRunner.Result(second.exitCode(), new byte[0],
                diagnostic.getBytes(StandardCharsets.UTF_8));
    }

    static boolean safeToRetryInOtherPowerShell(CryptoProCommandRunner.Result result) {
        String diagnostic = result.diagnostic().toLowerCase(Locale.ROOT);
        if (diagnostic.contains("stage 'sign payload'") || diagnostic.contains("stage 'write signature'")) {
            return false;
        }
        return diagnostic.contains("stage 'find selected certificate'")
                || diagnostic.contains("stage 'create signer'")
                || diagnostic.contains("stage 'assign certificate to signer'")
                || diagnostic.contains("stage 'create signed-data object'")
                || diagnostic.contains("stage 'load payload'")
                || diagnostic.contains("class not registered") || diagnostic.contains("0x80040154");
    }

    private static List<List<String>> powerShellCandidates(String mode, String... arguments) {
        return powerShellCandidates(isWindows(), System.getenv(), mode, arguments);
    }

    static List<List<String>> powerShellCandidates(boolean windows, Map<String, String> environment,
                                                    String mode, String... arguments) {
        Set<String> executables = new LinkedHashSet<>();
        executables.add("powershell.exe");
        if (windows) {
            String windowsRoot = environment.get("WINDIR");
            if (windowsRoot == null || windowsRoot.isBlank()) windowsRoot = environment.get("SystemRoot");
            if (windowsRoot != null && !windowsRoot.isBlank()) {
                executables.add(Path.of(windowsRoot, "SysWOW64", "WindowsPowerShell", "v1.0", "powershell.exe").toString());
            }
        }
        List<List<String>> result = new ArrayList<>();
        for (String executable : executables) result.add(powerShell(executable, mode, arguments));
        return List.copyOf(result);
    }

    static List<List<String>> cscriptCandidates(boolean windows, Map<String, String> environment, String... arguments) {
        Set<String> executables = new LinkedHashSet<>();
        executables.add("cscript.exe");
        if (windows) {
            String windowsRoot = environment.get("WINDIR");
            if (windowsRoot == null || windowsRoot.isBlank()) windowsRoot = environment.get("SystemRoot");
            if (windowsRoot != null && !windowsRoot.isBlank()) {
                executables.add(Path.of(windowsRoot, "SysWOW64", "cscript.exe").toString());
            }
        }
        List<List<String>> result = new ArrayList<>();
        for (String executable : executables) {
            ArrayList<String> command = new ArrayList<>(List.of(executable, "//Nologo"));
            command.addAll(List.of(arguments));
            result.add(List.copyOf(command));
        }
        return List.copyOf(result);
    }

    private static List<String> powerShell(String executable, String mode, String... arguments) {
        ArrayList<String> command = new ArrayList<>(List.of(
                executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Sta", "-ExecutionPolicy", "Bypass", mode));
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }
}
