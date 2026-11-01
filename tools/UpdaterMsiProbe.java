package com.vncode.app.integration.update;

import com.sun.net.httpserver.HttpServer;
import com.vncode.app.BuildConfig;
import com.vncode.app.Launcher;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Runs only on an isolated Windows CI host. Uses the actual 1.5.19 updater classes. */
public final class UpdaterMsiProbe {
    private UpdaterMsiProbe() { }

    public static void main(String[] args) throws Exception {
        if(args.length != 3) throw new IllegalArgumentException("usage: candidate.msi expectedSha256 expectedBytes");
        Path candidate=Path.of(args[0]).toAbsolutePath();
        String expected=args[1].toLowerCase(java.util.Locale.ROOT);
        long expectedBytes=Long.parseLong(args[2]);
        boolean dryRun=Boolean.getBoolean("vncode.probe.dry");
        if(!dryRun&&!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win"))
            throw new IllegalStateException("This installation probe requires Windows");
        if(!dryRun&&(!"true".equals(System.getenv("GITHUB_ACTIONS"))||!"Windows".equals(System.getenv("RUNNER_OS"))||
                !"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))))
            throw new IllegalStateException("This installation probe requires a disposable GitHub-hosted Windows runner");
        Path data=dryRun?Path.of(System.getProperty("java.io.tmpdir"),"vncode-updater-probe-data"):
                Path.of(System.getenv("LOCALAPPDATA"),"VNcodeData").toAbsolutePath();
        Path proof=dryRun?Path.of(System.getProperty("java.io.tmpdir"),"vncode-updater-handoff.properties"):
                Path.of(System.getenv("RUNNER_TEMP"),"vncode-updater-handoff.properties").toAbsolutePath();
        if(!"1.5.19".equals(BuildConfig.getAppVersion()))throw new IllegalStateException("Expected the installed 1.5.19 updater binary");
        if(BuildConfig.getUpdateManifestPublicKey()!=null&&!BuildConfig.getUpdateManifestPublicKey().isBlank())
            throw new IllegalStateException("The installed binary requires a signed manifest; no key may be bypassed");
        if(!expected.matches("[a-f0-9]{64}")||expectedBytes<1||Files.size(candidate)!=expectedBytes||!expected.equals(sha256(candidate)))
            throw new IllegalStateException("Candidate bytes differ from verified artifact");
        if(VersionComparator.compare("1.6.0",BuildConfig.getAppVersion())<=0)throw new IllegalStateException("Expected a newer version");
        System.setProperty("vncode.appdata.dir",data.toString());
        String originalOs=System.getProperty("os.name");
        if(dryRun)System.setProperty("os.name","Windows 11");
        verifySyntheticSignedManifest(expected,expectedBytes);
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        AtomicInteger requests=new AtomicInteger();
        server.createContext("/VN-code-1.6.0-Windows-x64.msi",exchange->{
            requests.incrementAndGet();
            if(!"GET".equals(exchange.getRequestMethod())){exchange.sendResponseHeaders(405,-1);exchange.close();return;}
            exchange.sendResponseHeaders(200,expectedBytes);
            try(var input=Files.newInputStream(candidate);var output=exchange.getResponseBody()){input.transferTo(output);}finally{exchange.close();}
        });
        server.start();
        boolean sessionOpen=false;
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/VN-code-1.6.0-Windows-x64.msi";
            String body=release(url,expected,expectedBytes);
            UpdateInfo info=UpdateApiClient.parseGitHubRelease(body);
            if(info==null||!"1.6.0".equals(info.getVersion())||!url.equals(info.getBestDownloadUrl())||
                    !expected.equals(info.getBestDownloadSha256())||info.getBestDownloadSize()!=expectedBytes)
                throw new IllegalStateException("1.5.19 updater did not select verified MSI metadata");
            var client=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).build();
            var updater=new UpdateInstallerService(client,true);
            UpdateInfo invalid=UpdateApiClient.parseGitHubRelease(release(url,"0".repeat(64),expectedBytes));
            try {updater.downloadInstaller(invalid,null);throw new IllegalStateException("Incorrect SHA unexpectedly accepted");}
            catch(IOException rejected){if(!"UPDATE_CHECKSUM_MISMATCH".equals(rejected.getMessage()))throw rejected;}
            EnumSet<UpdateInstallerService.Phase> phases=EnumSet.noneOf(UpdateInstallerService.Phase.class);
            Path downloaded=updater.downloadInstaller(info,new UpdateInstallerService.ProgressListener(){
                public void onProgress(long downloaded,long total) { }
                public void onPhase(UpdateInstallerService.Phase phase){phases.add(phase);}
            });
            if(!phases.contains(UpdateInstallerService.Phase.VERIFYING)||!expected.equals(sha256(downloaded))||requests.get()!=2)
                throw new IllegalStateException("Candidate was not downloaded and verified exactly as expected");
            if(dryRun){System.out.println("UPDATER_DRY_RUN=passed; download and checksum only");return;}
            var prepare=Launcher.class.getDeclaredMethod("prepareDataSession",Consumer.class);prepare.setAccessible(true);
            prepare.invoke(null,(Consumer<String>) ignored->{});sessionOpen=true;
            updater.launchInstallerAfterExit(downloaded,phases::add);
            if(!phases.contains(UpdateInstallerService.Phase.BACKUP)||!phases.contains(UpdateInstallerService.Phase.STARTING_INSTALLER))
                throw new IllegalStateException("Updater did not perform snapshot and launch handoff");
            Files.writeString(proof,"updaterVersion=1.5.19\ncandidateVersion=1.6.0\nselected=msi\nsha256="+expected+
                    "\nbytes="+expectedBytes+"\nrejectedIncorrectChecksum=true\nsyntheticSignedManifestVerified=true\nsnapshotRequested=true\nhandoffLaunched=true\n",StandardCharsets.UTF_8);
            System.out.println("UPDATER_MSI_HANDOFF_STARTED");
        } finally {
            server.stop(0);
            if(sessionOpen){var field=Launcher.class.getDeclaredField("dataSession");field.setAccessible(true);Object session=field.get(null);if(session instanceof AutoCloseable closeable)closeable.close();field.set(null,null);}
            if(dryRun&&originalOs!=null)System.setProperty("os.name",originalOs);
        }
        // The 1.5.19 handoff waits for this exact process to exit before invoking msiexec.
        System.exit(0);
    }

    private static String release(String url,String hash,long bytes){
        String exe=url.replace(".msi",".exe");
        return "{\"tag_name\":\"v1.6.0\",\"draft\":false,\"prerelease\":false,\"assets\":["+
                "{\"name\":\"VN-code-1.6.0-Windows-x64.exe\",\"browser_download_url\":\""+exe+"\",\"size\":1,\"digest\":\"sha256:"+"f".repeat(64)+"\"},"+
                "{\"name\":\"VN-code-1.6.0-Windows-x64.msi\",\"browser_download_url\":\""+url+"\",\"size\":"+bytes+",\"digest\":\"sha256:"+hash+"\"}]}";
    }

    /** Tests 1.5.19 verification with a fresh throwaway key; it is not a seller key. */
    private static void verifySyntheticSignedManifest(String hash,long bytes)throws Exception {
        String url="https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.0/VN-code-1.6.0-Windows-x64.msi";
        String payload="{\"schemaVersion\":1,\"version\":\"1.6.0\",\"publishedAt\":\"2026-11-01T00:00:00Z\",\"mandatory\":false,\"notes\":[],\"assets\":["+
                "{\"platform\":\"windows-x64\",\"kind\":\"msi\",\"url\":\""+url+"\",\"sha256\":\""+hash+"\",\"size\":"+bytes+"}]}";
        var keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var signer=Signature.getInstance("Ed25519");signer.initSign(keys.getPrivate());
        byte[] contents=payload.getBytes(StandardCharsets.UTF_8);signer.update(contents);byte[] signature=signer.sign();
        String pin=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        UpdateInfo verified=SignedUpdateManifest.verify(envelope(contents,signature),pin,"1.6.0");
        if(!url.equals(verified.getBestDownloadUrl())||!hash.equals(verified.getBestDownloadSha256())||verified.getBestDownloadSize()!=bytes)
            throw new IllegalStateException("Signed manifest did not preserve the selected MSI identity");
        byte[] badSignature=signature.clone();badSignature[0]^=1;
        requireInvalidManifest(envelope(contents,badSignature),pin);
        String differentHash=(hash.charAt(0)=='0'?"1":"0")+hash.substring(1);
        requireInvalidManifest(envelope(payload.replace(hash,differentHash).getBytes(StandardCharsets.UTF_8),signature),pin);
        var unrelated=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        requireInvalidManifest(envelope(contents,signature),Base64.getEncoder().encodeToString(unrelated.getPublic().getEncoded()));
        System.out.println("SYNTHETIC_SIGNED_MANIFEST=passed");
    }

    private static String envelope(byte[] contents,byte[] signature){
        return "{\"format\":\"vncode-update-envelope-v1\",\"payload\":\""+Base64.getEncoder().encodeToString(contents)+
                "\",\"signature\":\""+Base64.getEncoder().encodeToString(signature)+"\"}";
    }

    private static void requireInvalidManifest(String envelope,String pin)throws IOException {
        try {SignedUpdateManifest.verify(envelope,pin,"1.6.0");throw new IllegalStateException("Invalid manifest unexpectedly accepted");}
        catch(IOException rejected){if(!"UPDATE_SIGNATURE_INVALID".equals(rejected.getMessage()))throw rejected;}
    }

    private static String sha256(Path file)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
