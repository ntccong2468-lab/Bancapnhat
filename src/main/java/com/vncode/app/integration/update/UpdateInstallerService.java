package com.vncode.app.integration.update;

import com.vncode.app.shared.AppPaths;
import com.vncode.app.shared.I18nService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class UpdateInstallerService {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build();
    private final OkHttpClient http;
    private final boolean localTest;
    private record VerifiedInstaller(String sha256,String version) { }
    private final java.util.Map<Path,VerifiedInstaller> verifiedFiles=new java.util.concurrent.ConcurrentHashMap<>();
    public enum Phase { DOWNLOADING, VERIFYING, BACKUP, STARTING_INSTALLER, SAVING }
    public UpdateInstallerService(){this(CLIENT,false);}
    UpdateInstallerService(OkHttpClient http,boolean localTest){this.http=http;this.localTest=localTest;}

    public boolean supportsInAppInstall(UpdateInfo info) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String url = info == null ? null : info.getBestDownloadUrl();
        return os.contains("win")
                && url != null
                && hasVerification(info)
                && (url.toLowerCase(Locale.ROOT).endsWith(".exe") || url.toLowerCase(Locale.ROOT).endsWith(".msi"));
    }
    public boolean hasVerification(UpdateInfo info){
        return info!=null&&info.getBestDownloadSha256()!=null&&info.getBestDownloadSha256().matches("[a-fA-F0-9]{64}")
                &&info.getBestDownloadSize()!=null&&info.getBestDownloadSize()>0&&info.getBestDownloadSize()<=1024L*1024*1024;
    }

    public Path downloadInstaller(UpdateInfo info, ProgressListener progressListener) throws IOException {
        String url = info==null?null:info.getBestDownloadUrl();
        if (url == null || url.isBlank()) {
            throw new IOException(I18nService.getInstance().tr("update.error.missing_url"));
        }

        if(!hasVerification(info))throw new IOException("UPDATE_CHECKSUM_MISSING");
        okhttp3.HttpUrl parsed=okhttp3.HttpUrl.parse(url);
        if(parsed==null||!parsed.username().isEmpty()||!parsed.password().isEmpty()
                ||!parsed.isHttps()&&!(localTest&&java.util.Set.of("localhost","127.0.0.1","::1").contains(parsed.host())))throw new IOException("UPDATE_URL_INVALID");
        String extension = guessExtension(url);
        Path tempBase = AppPaths.nativeTempDir();
        Files.createDirectories(tempBase);
        Path tempDir = Files.createTempDirectory(tempBase, "vncode-update-");
        Path installerFile = tempDir.resolve("VN-code-update" + extension);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "VN-code-Updater")
                .get()
                .build();

        var call=http.newCall(request);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException(I18nService.getInstance().tr("update.error.download_failed") + " HTTP " + response.code());
            }
            long totalBytes = response.body().contentLength();
            long expected=info.getBestDownloadSize();
            if(totalBytes>=0&&totalBytes!=expected)throw new IOException("UPDATE_SIZE_MISMATCH");
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            if (progressListener != null) {
                progressListener.onPhase(Phase.DOWNLOADING);
                progressListener.onProgress(0L, totalBytes);
            }
            try (InputStream input = response.body().byteStream();
                 OutputStream output = Files.newOutputStream(installerFile)) {
                byte[] buffer = new byte[65536];
                long downloadedBytes = 0L;
                long lastProgress=System.nanoTime();
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if(Thread.currentThread().isInterrupted()){call.cancel();throw new java.io.InterruptedIOException("UPDATE_CANCELLED");}
                    if(downloadedBytes+read>expected)throw new IOException("UPDATE_SIZE_MISMATCH");
                    output.write(buffer, 0, read);
                    digest.update(buffer,0,read);
                    downloadedBytes += read;
                    if (progressListener != null && (downloadedBytes==expected || System.nanoTime()-lastProgress>=100_000_000L)) {
                        progressListener.onProgress(downloadedBytes, totalBytes);
                        lastProgress=System.nanoTime();
                    }
                }
                if(downloadedBytes!=expected)throw new IOException("UPDATE_SIZE_MISMATCH");
                if(progressListener!=null)progressListener.onPhase(Phase.VERIFYING);
            }
            String actual=java.util.HexFormat.of().formatHex(digest.digest());
            if(!actual.equalsIgnoreCase(info.getBestDownloadSha256()))throw new IOException("UPDATE_CHECKSUM_MISMATCH");
            verifiedFiles.put(installerFile.toAbsolutePath().normalize(),new VerifiedInstaller(actual,info.getVersion()));
        }catch(IOException|java.security.GeneralSecurityException failure){
            Files.deleteIfExists(installerFile);Files.deleteIfExists(tempDir);
            if(failure instanceof IOException io)throw io;
            throw new IOException("UPDATE_VERIFICATION_FAILED");
        }

        return installerFile;
    }

    public void launchInstallerAfterExit(Path installerFile) throws IOException {
        launchInstallerAfterExit(installerFile,phase->{});
    }
    public void launchInstallerAfterExit(Path installerFile,java.util.function.Consumer<Phase> phase) throws IOException {
        launchInstallerAfterExit(installerFile,phase,progress->{});
    }
    public void launchInstallerAfterExit(Path installerFile,java.util.function.Consumer<Phase> phase,java.util.function.Consumer<com.vncode.app.shared.LocalDataSnapshotService.Progress> progress) throws IOException {
        checkCancelled();phase.accept(Phase.VERIFYING);
        var verified=verifiedFiles.get(installerFile.toAbsolutePath().normalize());
        if(verified==null||!verified.sha256().equals(fileHash(installerFile)))throw new IOException("UPDATE_CHECKSUM_MISMATCH");
        checkCancelled();phase.accept(Phase.BACKUP);
        com.vncode.app.Launcher.prepareUpdateSnapshot(verified.version(),progress);
        checkCancelled();phase.accept(Phase.STARTING_INSTALLER);
        String command = buildWindowsInstallCommand(installerFile);
        checkCancelled();
        new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden",
                "-Command", command
        ).start();
    }
    public Path saveDownloadedInstaller(UpdateInfo info,Path file)throws IOException {
        if(!info.getVersion().matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}"))throw new IOException("UPDATE_INVALID_RESPONSE");
        var verified=verifiedFiles.get(file.toAbsolutePath().normalize());
        if(verified==null||!verified.sha256().equals(fileHash(file)))throw new IOException("UPDATE_CHECKSUM_MISMATCH");
        String expected=verified.sha256();
        Path saved=Files.createTempFile(AppPaths.preferredDownloadsDirectory().toPath(),"VN-code-"+info.getVersion()+"-",guessExtension(info.getBestDownloadUrl()));
        try{Files.copy(file,saved,java.nio.file.StandardCopyOption.REPLACE_EXISTING);if(!expected.equals(fileHash(saved)))throw new IOException("UPDATE_CHECKSUM_MISMATCH");return saved;}
        catch(IOException failure){Files.deleteIfExists(saved);throw failure;}
    }

    String buildWindowsInstallCommand(Path installerFile) {
        String escapedInstaller = escapePowerShellSingleQuoted(installerFile.toAbsolutePath().toString());
        boolean testProfile = AppPaths.isZnackRegistrationTestProfile();
        String appName = testProfile ? "VN code Znack Test" : "VN code";
        String appExecutable = appName + ".exe";
        String installDirectory = testProfile ? "VNcodeZnackRegistrationTestApp" : "VNcodeApp";
        // OpenJDK25's jpackage EXE forwards its arguments to msiexec after adding /i.
        // Use the same passive/no-restart options as the direct MSI path.
        return """
                $ErrorActionPreference = 'Stop';
                $oldProcess = Get-Process -Id %d -ErrorAction SilentlyContinue;
                if ($null -ne $oldProcess) { Wait-Process -Id %d -Timeout 120 -ErrorAction Stop; }
                $installer = '%s';
                $extension = [System.IO.Path]::GetExtension($installer).ToLowerInvariant();
                if ($extension -eq '.msi') {
                    $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ('"' + $installer + '"'), '/passive', '/norestart') -Wait -PassThru;
                } else {
                    $process = Start-Process -FilePath $installer -ArgumentList @('/passive', '/norestart') -Wait -PassThru;
                }
                if ($null -ne $process -and $process.ExitCode -in @(0,3010)) {
                    $candidates = @(
                        (Join-Path $env:LOCALAPPDATA '%s\\%s'),
                        (Join-Path $env:LOCALAPPDATA 'Programs\\%s\\%s'),
                        (Join-Path $env:ProgramFiles '%s\\%s'),
                        (Join-Path ${env:ProgramFiles(x86)} '%s\\%s')
                    );
                    foreach ($candidate in $candidates) {
                        if ($candidate -and (Test-Path $candidate)) {
                            Start-Process -FilePath $candidate;
                            break;
                        }
                    }
                }
                """.formatted(
                ProcessHandle.current().pid(),ProcessHandle.current().pid(),escapedInstaller,
                installDirectory, appExecutable,
                appName, appExecutable,
                appName, appExecutable,
                appName, appExecutable
        );
    }

    private String escapePowerShellSingleQuoted(String value) {
        return value.replace("'", "''");
    }
    private String fileHash(Path file)throws IOException {
        if(Files.size(file)>1024L*1024*1024)throw new IOException("UPDATE_SIZE_MISMATCH");
        try(var input=Files.newInputStream(file)){
            var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;
            while((n=input.read(buffer))!=-1){checkCancelled();digest.update(buffer,0,n);}
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.GeneralSecurityException impossible){throw new IOException("UPDATE_VERIFICATION_FAILED");}
    }
    private static void checkCancelled()throws java.io.InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("UPDATE_CANCELLED");
    }

    private String guessExtension(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".msi")) return ".msi";
        if (lower.endsWith(".zip")) return ".zip";
        return ".exe";
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
        default void onPhase(Phase phase){}
    }
}
