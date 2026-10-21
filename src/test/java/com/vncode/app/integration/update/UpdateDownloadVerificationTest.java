package com.vncode.app.integration.update;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class UpdateDownloadVerificationTest {
    @TempDir Path root;MockWebServer server;UpdateInstallerService service;
    @BeforeEach void start()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());server=new MockWebServer();server.start();service=new UpdateInstallerService(new OkHttpClient(),true);}
    @AfterEach void close()throws Exception{server.shutdown();System.clearProperty("vncode.appdata.dir");}
    private UpdateInfo info(String body)throws Exception {
        var i=new UpdateInfo();i.setVersion("1.4.4");i.setDownloadUrls(Map.of("exe",server.url("/update.exe").toString(),"deb",server.url("/update.exe").toString()));
        i.setDownloadSizes(Map.of("exe",(long)body.length(),"deb",(long)body.length()));String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));i.setDownloadSha256(Map.of("exe",hash,"deb",hash));return i;
    }
    @Test void correctBytesDownloadButChangedOrTruncatedBytesAreRemoved()throws Exception {
        var i=info("verified-installer");server.enqueue(new MockResponse().setBody("verified-installer"));
        Path valid=service.downloadInstaller(i,null);assertEquals("verified-installer",Files.readString(valid));
        server.enqueue(new MockResponse().setBody("modified-installer"));
        assertEquals("UPDATE_CHECKSUM_MISMATCH",assertThrows(java.io.IOException.class,()->service.downloadInstaller(i,null)).getMessage());
        server.enqueue(new MockResponse().setBody("short"));
        assertEquals("UPDATE_SIZE_MISMATCH",assertThrows(java.io.IOException.class,()->service.downloadInstaller(i,null)).getMessage());
        Files.writeString(valid,"tampered");
        assertEquals("UPDATE_CHECKSUM_MISMATCH",assertThrows(java.io.IOException.class,()->service.launchInstallerAfterExit(valid)).getMessage());
        assertThrows(java.io.IOException.class,()->service.saveDownloadedInstaller(i,valid));
        Files.delete(valid);Files.delete(valid.getParent());
    }
    @Test void missingMetadataDoesNotDownloadOrLaunchArbitraryFiles()throws Exception {
        var i=info("binary");i.setDownloadSha256(Map.of());
        assertEquals("UPDATE_CHECKSUM_MISSING",assertThrows(java.io.IOException.class,()->service.downloadInstaller(i,null)).getMessage());
        assertEquals(0,server.getRequestCount());
        Path arbitrary=root.resolve("not-downloaded.exe");Files.writeString(arbitrary,"binary");
        assertEquals("UPDATE_CHECKSUM_MISMATCH",assertThrows(java.io.IOException.class,()->service.launchInstallerAfterExit(arbitrary)).getMessage());
    }
    @Test void downloadReportsVerificationAndPreparationNeverClaimsInstallerStartedBeforeBackup()throws Exception {
        var phases=new ArrayList<UpdateInstallerService.Phase>();var i=info("verified-installer");server.enqueue(new MockResponse().setBody("verified-installer"));
        Path file=service.downloadInstaller(i,new UpdateInstallerService.ProgressListener(){
            public void onProgress(long downloaded,long total){}
            public void onPhase(UpdateInstallerService.Phase phase){phases.add(phase);}
        });
        assertEquals(List.of(UpdateInstallerService.Phase.DOWNLOADING,UpdateInstallerService.Phase.VERIFYING),phases);
        phases.clear();assertEquals("UPDATE_BACKUP_UNAVAILABLE",assertThrows(java.io.IOException.class,()->service.launchInstallerAfterExit(file,phases::add)).getMessage());
        assertEquals(List.of(UpdateInstallerService.Phase.VERIFYING,UpdateInstallerService.Phase.BACKUP),phases);
    }
    @Test void cancelledPreparationCannotLaunchOrStartBackup()throws Exception {
        var phases=new ArrayList<UpdateInstallerService.Phase>();Thread.currentThread().interrupt();
        try{assertThrows(java.io.InterruptedIOException.class,()->service.launchInstallerAfterExit(root.resolve("not-downloaded.exe"),phases::add));}
        finally{Thread.interrupted();}
        assertTrue(phases.isEmpty());
    }
}
