package com.tuandev.fbsbarcode.integration.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.license.LicenseState.LicenseStatus;
import com.tuandev.fbsbarcode.shared.ConfigService;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseServiceTest {

    private static final String KEY = "WC-TEST1-TEST1-TEST1-TEST1";
    private static final String FINGERPRINT = "fp-test";
    private static final Gson GSON = new Gson();

    private static KeyPair keyPair;
    private static LicenseFileVerifier verifier;

    @TempDir Path temp;
    private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);

    @BeforeAll
    static void createKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier =
                new LicenseFileVerifier(
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }

    @BeforeEach
    void init() {
        System.setProperty("wcode.appdata.dir", temp.toString());
        Database.initDatabase();
    }

    @AfterEach
    void clear() {
        System.clearProperty("wcode.appdata.dir");
    }

    private LicenseService service(String baseUrl) {
        return new LicenseService(
                LicenseApiClient.withBaseUrl(baseUrl),
                verifier,
                new LicenseStorage(temp.resolve("license.json")),
                () -> FINGERPRINT,
                clock::get);
    }

    private SignedLicenseFile signedFile(String status, long issuedAt, long expiresAt)
            throws Exception {
        LicensePayload payload =
                new LicensePayload(1, KEY, FINGERPRINT, "standard", 1, status, issuedAt, expiresAt);
        byte[] json = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(json);
        return new SignedLicenseFile(
                Base64.getEncoder().encodeToString(json),
                Base64.getEncoder().encodeToString(signer.sign()),
                "Ed25519");
    }

    private String okBody(String status, long issuedAt, long expiresAt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("status", status);
        body.addProperty("expiresAt", expiresAt);
        body.addProperty("plan", "standard");
        body.add("licenseFile", GSON.toJsonTree(signedFile(status, issuedAt, expiresAt)));
        return GSON.toJson(body);
    }

    private static String errorBody(String code) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"test\"}}";
    }

    private interface ServerScenario {
        void run(String baseUrl) throws Exception;
    }

    private void withServer(String path, int status, String body, ServerScenario scenario)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                path,
                exchange -> {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                    exchange.close();
                });
        server.start();
        try {
            scenario.run("http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void activateStoresKeyAndReturnsValid() throws Exception {
        long now = clock.get();
        withServer(
                "/api/v1/activate",
                200,
                okBody("valid", now, now + TimeUnit.DAYS.toMillis(30)),
                baseUrl -> {
                    LicenseService service = service(baseUrl);
                    LicenseState state = service.activate(KEY);
                    assertEquals(LicenseStatus.VALID, state.status());
                    assertTrue(state.kizAllowed());
                    assertEquals(KEY, ConfigService.getLicenseKey());
                });
    }

    @Test
    void refreshReportsExpiredSubscription() throws Exception {
        ConfigService.setLicenseKey(KEY);
        long now = clock.get();
        withServer(
                "/api/v1/validate",
                200,
                okBody("expired", now, now - TimeUnit.DAYS.toMillis(1)),
                baseUrl -> {
                    LicenseState state = service(baseUrl).refresh();
                    assertEquals(LicenseStatus.EXPIRED, state.status());
                    assertFalse(state.kizAllowed());
                });
    }

    @Test
    void refreshWithoutKeyIsNotActivated() {
        LicenseState state = service("http://127.0.0.1:1").refresh();
        assertEquals(LicenseStatus.NOT_ACTIVATED, state.status());
    }

    @Test
    void offlineWithCachedFileWithinGraceAllowsKiz() throws Exception {
        ConfigService.setLicenseKey(KEY);
        long now = clock.get();
        LicenseStorage storage = new LicenseStorage(temp.resolve("license.json"));
        storage.save(signedFile("valid", now - TimeUnit.DAYS.toMillis(2), now + TimeUnit.DAYS.toMillis(20)));
        // Cổng 1 không có server → IOException → đánh giá offline
        LicenseState state = service("http://127.0.0.1:1").refresh();
        assertEquals(LicenseStatus.OFFLINE_GRACE, state.status());
        assertTrue(state.kizAllowed());
    }

    @Test
    void offlineBeyondGracePeriodBlocksKiz() throws Exception {
        ConfigService.setLicenseKey(KEY);
        long now = clock.get();
        LicenseStorage storage = new LicenseStorage(temp.resolve("license.json"));
        storage.save(
                signedFile(
                        "valid",
                        now - LicenseService.GRACE_MS - TimeUnit.DAYS.toMillis(1),
                        now + TimeUnit.DAYS.toMillis(20)));
        LicenseState state = service("http://127.0.0.1:1").refresh();
        assertEquals(LicenseStatus.NETWORK_ERROR, state.status());
        assertFalse(state.kizAllowed());
    }

    @Test
    void offlineWithExpiredCachedFileIsExpired() throws Exception {
        ConfigService.setLicenseKey(KEY);
        long now = clock.get();
        LicenseStorage storage = new LicenseStorage(temp.resolve("license.json"));
        storage.save(signedFile("valid", now - TimeUnit.DAYS.toMillis(2), now - TimeUnit.HOURS.toMillis(1)));
        LicenseState state = service("http://127.0.0.1:1").refresh();
        assertEquals(LicenseStatus.EXPIRED, state.status());
    }

    @Test
    void clockRollbackIsDetectedOffline() throws Exception {
        ConfigService.setLicenseKey(KEY);
        long issuedAt = clock.get();
        LicenseStorage storage = new LicenseStorage(temp.resolve("license.json"));
        storage.save(signedFile("valid", issuedAt, issuedAt + TimeUnit.DAYS.toMillis(30)));
        // Người dùng chỉnh đồng hồ lùi 3 ngày so với lần xác thực cuối
        clock.set(issuedAt - TimeUnit.DAYS.toMillis(3));
        LicenseState state = service("http://127.0.0.1:1").refresh();
        assertEquals(LicenseStatus.CLOCK_TAMPERED, state.status());
        assertFalse(state.kizAllowed());
    }

    @Test
    void revokedKeyBecomesInvalidAndCacheIsCleared() throws Exception {
        ConfigService.setLicenseKey(KEY);
        long now = clock.get();
        LicenseStorage storage = new LicenseStorage(temp.resolve("license.json"));
        storage.save(signedFile("valid", now, now + TimeUnit.DAYS.toMillis(30)));
        withServer(
                "/api/v1/validate",
                403,
                errorBody("invalid_license"),
                baseUrl -> {
                    LicenseState state = service(baseUrl).refresh();
                    assertEquals(LicenseStatus.INVALID, state.status());
                    // File cache đã bị xóa → offline sau đó cũng không còn "bằng chứng"
                    assertTrue(storage.load().isEmpty());
                });
    }

    @Test
    void deviceLimitReachedIsReported() throws Exception {
        withServer(
                "/api/v1/activate",
                403,
                errorBody("device_limit_reached"),
                baseUrl -> {
                    LicenseService service = service(baseUrl);
                    try {
                        service.activate(KEY);
                    } catch (LicenseApiClient.LicenseApiException e) {
                        assertEquals(
                                LicenseApiClient.LicenseApiException.CODE_DEVICE_LIMIT_REACHED, e.code());
                        return;
                    }
                    throw new AssertionError("activate phải ném LicenseApiException");
                });
    }

    @Test
    void rejectsServerResponseSignedWithWrongKey() throws Exception {
        ConfigService.setLicenseKey(KEY);
        long now = clock.get();
        // Server (giả mạo) ký bằng khóa khác → app không được tin
        KeyPair rogue = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        LicensePayload payload =
                new LicensePayload(
                        1, KEY, FINGERPRINT, "standard", 1, "valid", now, now + TimeUnit.DAYS.toMillis(30));
        byte[] json = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(rogue.getPrivate());
        signer.update(json);
        JsonObject body = new JsonObject();
        body.addProperty("status", "valid");
        body.addProperty("expiresAt", now + TimeUnit.DAYS.toMillis(30));
        body.addProperty("plan", "standard");
        body.add(
                "licenseFile",
                GSON.toJsonTree(
                        new SignedLicenseFile(
                                Base64.getEncoder().encodeToString(json),
                                Base64.getEncoder().encodeToString(signer.sign()),
                                "Ed25519")));
        withServer(
                "/api/v1/validate",
                200,
                GSON.toJson(body),
                baseUrl -> {
                    LicenseState state = service(baseUrl).refresh();
                    assertEquals(LicenseStatus.INVALID, state.status());
                    assertFalse(state.kizAllowed());
                });
    }
}
