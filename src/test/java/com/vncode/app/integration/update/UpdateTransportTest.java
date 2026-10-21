package com.vncode.app.integration.update;

import com.vncode.app.config.Database;
import com.vncode.app.shared.ConfigService;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class UpdateTransportTest {
    @TempDir Path root;
    private MockWebServer server;
    @BeforeEach void start()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        server=new MockWebServer();server.start();ConfigService.setConfigValue("update_api_url",server.url("/").toString().replaceFirst("/$",""));
    }
    @AfterEach void close()throws Exception{server.shutdown();System.clearProperty("vncode.appdata.dir");}
    @Test void manualCheckReportsHttpFailureInsteadOfUpToDateAndDoesNotRetryRateLimit()throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429));
        var api=new UpdateApiClient(new OkHttpClient(),"");var result=new UpdateService(api,()->"1.4.3",()->"").check(true);
        assertEquals(UpdateService.Status.FAILED,result.status());assertEquals("UPDATE_HTTP_429",result.errorCode());
        assertEquals("/api/versions/latest",server.takeRequest(2,TimeUnit.SECONDS).getPath());assertEquals(1,server.getRequestCount());
    }
    @Test void malformedOrOversizedResponsesFailAndEmptyResponseMeansNoPublishedRelease() {
        var api=new UpdateApiClient(new OkHttpClient(),"");
        server.enqueue(new MockResponse().setBody("<html>Worker error</html>"));
        assertEquals("UPDATE_INVALID_RESPONSE",assertThrows(java.io.IOException.class,api::fetchLatestVersion).getMessage());
        server.enqueue(new MockResponse().setBody(" ".repeat(1024*1024+1)));
        assertEquals("UPDATE_INVALID_RESPONSE",assertThrows(java.io.IOException.class,api::fetchLatestVersion).getMessage());
        server.enqueue(new MockResponse().setResponseCode(204));assertDoesNotThrow(()->assertNull(api.fetchLatestVersion()));
    }
    @Test void customMetadataCannotBypassConfiguredManifestSignature() {
        server.enqueue(new MockResponse().setBody("{\"version\":\"1.4.4\",\"downloadUrls\":{\"exe\":\"https://example.com/app.exe\"}}"));
        var api=new UpdateApiClient(new OkHttpClient(),"configured-public-pin");
        assertEquals("UPDATE_SIGNATURE_MISSING",assertThrows(java.io.IOException.class,api::fetchLatestVersion).getMessage());
    }
}
