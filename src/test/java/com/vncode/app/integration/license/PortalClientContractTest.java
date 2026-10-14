package com.vncode.app.integration.license;

import com.google.gson.*;
import com.vncode.app.features.license.*;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import static com.vncode.app.features.license.LicenseModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Portal Worker + in-memory D1 adapter → HTTP → shipped Java transport and verifier. */
class PortalClientContractTest {
    @TempDir Path root;
    private Process bridge;
    private LicenseService service;
    private LicenseVerifier verifier;
    private HttpUrl origin;
    private final OkHttpClient control=new OkHttpClient();
    @BeforeEach void start()throws Exception {
        bridge=new ProcessBuilder("node","website/license-portal/test/java-client-fixture.mjs")
                .redirectError(root.resolve("bridge-stderr.log").toFile()).start();
        var reader=new BufferedReader(new InputStreamReader(bridge.getInputStream(),StandardCharsets.UTF_8));
        String line=CompletableFuture.supplyAsync(()->{try{return reader.readLine();}catch(IOException e){throw new UncheckedIOException(e);}}).get(15,TimeUnit.SECONDS);
        assertNotNull(line,"Portal bridge did not start");var config=JsonParser.parseString(line).getAsJsonObject();
        origin=HttpUrl.get("http://127.0.0.1:"+config.get("port").getAsInt()+"/");
        verifier=new LicenseVerifier(Map.of(config.get("keyId").getAsString(),LicenseDeviceKeys.publicKey(config.get("publicKey").getAsString())));
        service=createService("test-state",Clock.systemUTC());
        service.initialize().get(15,TimeUnit.SECONDS);
    }
    private LicenseService createService(String directory,Clock clock) {
        var codec=new LicenseStore.SecretCodec(){public String protect(String value){return value;}public String reveal(String value){return value;}};
        return new LicenseService(new WebLicenseApi(new OkHttpClient(),origin),verifier,new LicenseStore(root.resolve(directory),codec),clock,System::nanoTime);
    }
    @AfterEach void stop()throws Exception {
        if(service!=null)service.close();
        if(bridge!=null){bridge.destroy();if(!bridge.waitFor(5,TimeUnit.SECONDS))bridge.destroyForcibly();}
        control.dispatcher().executorService().shutdown();control.connectionPool().evictAll();
    }
    private JsonObject fixture(String path)throws IOException {
        try(var response=control.newCall(new Request.Builder().url(origin.resolve("__fixture/"+path)).post(RequestBody.create("{}",MediaType.get("application/json"))).build()).execute()){
            assertEquals(200,response.code());return JsonParser.parseString(response.body().string()).getAsJsonObject();
        }
    }
    private String registerAndApprove()throws Exception {
        assertEquals(Status.PENDING,service.startRegistration("bridge-client@gmail.com").get(15,TimeUnit.SECONDS).status());
        String code=fixture("approve").get("code").getAsString();
        assertEquals(Status.APPROVED,service.checkStatus().get(15,TimeUnit.SECONDS).status());return code;
    }
    @Test void realPortalGrantActivatesAndRenewsInJavaWithAllSellerPermissions()throws Exception {
        String code=registerAndApprove();var snapshot=service.activate(code).get(15,TimeUnit.SECONDS);
        assertEquals(Status.ACTIVE,snapshot.status(),"Real Portal response rejected: "+snapshot.errorCode());
        assertEquals(10,snapshot.maxShops());assertEquals(2,snapshot.maxDevices());
        for(var permission:Permission.values())assertTrue(service.allows(permission),permission.name());
        assertEquals(Status.ACTIVE,service.refresh().get(15,TimeUnit.SECONDS).status());
        var metrics=fixture("metrics");assertEquals("redeemed",metrics.get("state").getAsString());assertEquals(1,metrics.get("devices").getAsInt());
    }
    @Test void freshPortalLeaseAllowsSmallWindowsClockDifferenceWithoutRejectingRedeemedCode()throws Exception {
        service.close();service=createService("slightly-slow-clock",Clock.offset(Clock.systemUTC(),Duration.ofSeconds(-3)));
        service.initialize().get(15,TimeUnit.SECONDS);
        String code=registerAndApprove();var snapshot=service.activate(code).get(15,TimeUnit.SECONDS);
        var metrics=fixture("metrics");assertEquals("redeemed",metrics.get("state").getAsString());
        assertEquals(Status.ACTIVE,snapshot.status(),"Portal consumed the code, but Java rejected its fresh lease: "+snapshot.errorCode());
        assertTrue(service.allows(Permission.PRINT_NEW));
    }
    @Test void badlySkewedClockGetsTimeErrorBeforeTheCodeIsConsumed()throws Exception {
        service.close();service=createService("bad-clock",Clock.offset(Clock.systemUTC(),Duration.ofMinutes(-10)));
        service.initialize().get(15,TimeUnit.SECONDS);
        String code=registerAndApprove();var snapshot=service.activate(code).get(15,TimeUnit.SECONDS);
        assertEquals("LICENSE_CLOCK_SKEW",snapshot.errorCode());assertFalse(service.allows(Permission.PRINT_NEW));
        var metrics=fixture("metrics");assertEquals("unused",metrics.get("state").getAsString());assertEquals(0,metrics.get("devices").getAsInt());
    }
    @Test void protocolFailureAfterRedemptionCanRetryTheSameCodeWithoutAddingDaysOrDevices()throws Exception {
        String code=registerAndApprove();fixture("corrupt-next");
        var failed=service.activate(code).get(15,TimeUnit.SECONDS);assertEquals("LICENSE_PROTOCOL_INVALID",failed.errorCode());assertFalse(service.allows(Permission.PRINT_NEW));
        var before=fixture("metrics");assertEquals("redeemed",before.get("state").getAsString());
        var retried=service.activate(code).get(15,TimeUnit.SECONDS);assertEquals(Status.ACTIVE,retried.status(),retried.errorCode());
        var after=fixture("metrics");assertEquals(before.get("expires"),after.get("expires"));assertEquals(1,after.get("devices").getAsInt());assertEquals(1,after.get("redemptions").getAsInt());
    }
}
