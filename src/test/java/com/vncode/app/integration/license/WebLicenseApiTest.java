package com.vncode.app.integration.license;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WebLicenseApiTest {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String TOKEN = "A".repeat(43);
    private static final String KEY = "A".repeat(43);
    private static final String SIGNATURE = "A".repeat(86);
    private static final String CODE = "VNC" + "-1234".repeat(8);
    private static final String EMAIL = "customer.name+shop@gmail.com";
    private MockWebServer server;
    private WebLicenseApi api;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        api = new WebLicenseApi(new OkHttpClient(), server.url("/"));
    }

    @AfterEach
    void tearDown() throws IOException { server.shutdown(); }

    @Test
    void sendsPasswordlessRegistrationAndDeviceProofUsingPortalContract() throws Exception {
        enqueue("{\"registration_id\":\"" + ID + "\",\"registration_token\":\"" + TOKEN
                + "\",\"expires_in\":600}", 201);
        assertEquals(TOKEN, api.start("  Customer.Name+Shop@Gmail.com  ").token());
        var start = request("POST", "/api/client/registration/start", false);
        assertEquals(EMAIL, json(start).get("email").getAsString());

        enqueue("{\"email\":\"" + EMAIL + "\",\"status\":\"pending\",\"registration_expires_in\":2592000}");
        assertEquals("pending", api.verify(TOKEN, "123456").status());
        assertEquals("123456", json(request("POST", "/api/client/registration/verify", true)).get("code").getAsString());

        enqueue(status("approved"));
        assertEquals("approved", api.status(TOKEN).status());
        assertEquals(0, request("GET", "/api/client/registration/status", true).getBodySize());

        String message = "vncode-device-v1\nactivate\n" + ID + "\nnonce";
        JsonObject challengeResponse = new JsonObject();
        challengeResponse.addProperty("challenge_id", ID);
        challengeResponse.addProperty("message", message);
        challengeResponse.addProperty("expires_at", 2000000120L);
        enqueue(challengeResponse.toString());
        var challenge = api.challenge(TOKEN, KEY, "activate");
        assertEquals(message, challenge.message(), "Message must retain the exact UTF-8 newline sequence for signing");
        var proofRequest = json(request("POST", "/api/client/license/challenge", true));
        assertEquals("activate", proofRequest.get("action").getAsString());
        assertEquals(KEY, proofRequest.get("public_key").getAsString());

        enqueue(grant(true).toString());
        var activated = api.activate(TOKEN, challenge, KEY, SIGNATURE, CODE.toLowerCase());
        assertEquals(TOKEN, activated.accessToken());
        assertEquals(ID, activated.account().id());
        assertEquals(EMAIL, activated.account().email());
        assertEquals("portal-v1", activated.envelope().keyId());
        assertEquals("e30", activated.envelope().payload(), "Transport returns the untrusted envelope for verification");
        assertEquals(2000000000L, activated.account().expiresAt());
        assertEquals(1900000300L, activated.onlineValidUntil());
        var activateRequest = json(request("POST", "/api/client/license/activate", true));
        assertEquals(ID, activateRequest.get("challenge_id").getAsString());
        assertEquals(KEY, activateRequest.get("public_key").getAsString());
        assertEquals(SIGNATURE, activateRequest.get("signature").getAsString());
        assertEquals(CODE, activateRequest.get("code").getAsString());
        assertFalse(activateRequest.has("email"));
        assertFalse(activateRequest.has("password"));

        enqueue(grant(false).toString());
        var renewed = api.renew(TOKEN, challenge, KEY, SIGNATURE);
        assertNull(renewed.accessToken(), "Renewal must preserve the existing device credential");
        assertFalse(json(request("POST", "/api/client/license/renew", true)).has("code"));
    }

    @Test
    void approvedRegistrationIsOnlyMetadataAndCannotSubstituteForGrant() throws Exception {
        enqueue(status("approved"));
        assertEquals("approved", api.status(TOKEN).status());
        enqueue("{\"user\":{\"status\":\"approved\",\"active\":true}}");
        assertProtocol(() -> activate());
    }

    @Test
    void rejectsInactiveOrCoercedAccountMetadataAndMissingLease() throws Exception {
        for (String status : List.of("pending", "suspended", "APPROVED")) {
            var response = grant(true);
            response.getAsJsonObject("user").addProperty("status", status);
            enqueue(response.toString());
            assertProtocol(() -> activate());
        }
        for (Object active : List.of(false, "true", 1)) {
            var response = grant(true);
            JsonObject user = response.getAsJsonObject("user");
            if (active instanceof Boolean b) user.addProperty("active", b);
            else if (active instanceof String s) user.addProperty("active", s);
            else user.addProperty("active", (Number) active);
            enqueue(response.toString());
            assertProtocol(() -> activate());
        }
        var response = grant(true);
        response.remove("lease");
        enqueue(response.toString());
        assertProtocol(() -> activate());
    }

    @Test
    void rejectsMissingAndWrongTypedProtocolFieldsWithoutCoercion() throws Exception {
        enqueue("{\"email\":\"" + EMAIL + "\",\"status\":\"approved\"}");
        assertProtocol(() -> api.status(TOKEN));
        var response = grant(true);
        response.getAsJsonObject("user").addProperty("expires_at", "2000000000");
        enqueue(response.toString());
        assertProtocol(() -> activate());
        response = grant(false);
        response.addProperty("access_token", TOKEN);
        enqueue(response.toString());
        assertProtocol(() -> api.renew(TOKEN, challenge(), KEY, SIGNATURE));
    }

    @Test
    void exposesOnlyKnownErrorCodesAndPreservesDenialStatus() throws Exception {
        enqueue("{\"error\":\"DEVICE_REVOKED\",\"detail\":\"" + EMAIL + "\"}", 403);
        var revoked = assertThrows(LicenseApi.Failure.class, () -> api.status(TOKEN));
        assertEquals("DEVICE_REVOKED", revoked.code());
        assertEquals(403, revoked.status());
        assertFalse(revoked.transientFailure());
        assertNull(revoked.getCause());
        assertFalse(revoked.toString().contains(EMAIL));

        enqueue("{\"error\":\"" + EMAIL + " token=" + TOKEN + "\"}", 403);
        var unknown = assertThrows(LicenseApi.Failure.class, () -> api.status(TOKEN));
        assertEquals("LICENSE_HTTP_ERROR", unknown.code());
        assertEquals(403, unknown.status());
        assertFalse(unknown.toString().contains(EMAIL));
        assertFalse(unknown.toString().contains(TOKEN));
    }

    @Test
    void configurationFailureCannotBeTreatedAsTransientOfflineFallback() throws Exception {
        enqueue("{\"error\":\"SIGNING_NOT_CONFIGURED\"}", 503);
        var failure = assertThrows(LicenseApi.Failure.class, () -> api.status(TOKEN));
        assertEquals("SIGNING_NOT_CONFIGURED", failure.code());
        assertFalse(failure.transientFailure());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void redirectNeverForwardsCredentialsOrRetries() throws Exception {
        try (var destination = new MockWebServer()) {
            destination.start();
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", destination.url("/collect")));
            var failure = assertThrows(LicenseApi.Failure.class, () -> api.status(TOKEN));
            assertEquals("LICENSE_REDIRECT_REJECTED", failure.code());
            assertEquals(307, failure.status());
            assertEquals(1, server.getRequestCount());
            assertNull(destination.takeRequest(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void boundedResponseAppliesToKnownLengthAndChunkedUtf8Bytes() throws Exception {
        String valid = status("pending");
        String atLimit = valid + " ".repeat(65536 - valid.getBytes(StandardCharsets.UTF_8).length);
        enqueue(atLimit);
        assertEquals("pending", api.status(TOKEN).status());
        enqueue(atLimit + " ");
        assertProtocol(() -> api.status(TOKEN));
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json; charset=utf-8")
                .setChunkedBody(valid + " ".repeat(65537), 17));
        assertProtocol(() -> api.status(TOKEN));
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json; charset=utf-8")
                .setChunkedBody("{\"error\":\"" + "é".repeat(33000) + "\"}", 23));
        assertProtocol(() -> api.status(TOKEN));
    }

    @Test
    void rejectsMalformedUtf8DuplicateFieldsAndMalformedServerErrors() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(new Buffer().write(new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'})));
        assertProtocol(() -> api.status(TOKEN));
        enqueue("{\"email\":\"" + EMAIL + "\",\"status\":\"pending\",\"status\":\"approved\",\"expires_at\":null}");
        assertProtocol(() -> api.status(TOKEN));
        enqueue("{\"error\":\"RATE_LIMIT\",\"error\":\"SIGNING_NOT_CONFIGURED\"}", 503);
        var malformed = assertProtocol(() -> api.status(TOKEN));
        assertFalse(malformed.transientFailure());
        enqueue("not JSON " + EMAIL, 503);
        assertProtocol(() -> api.status(TOKEN));
        enqueue("{}", 503);
        assertProtocol(() -> api.status(TOKEN));
    }

    @Test
    void rejectsWrongMediaTypeAndExplicitNonUtf8Charset() throws Exception {
        for (String mediaType : List.of("text/html", "application/json; charset=iso-8859-1", "application/json; charset=unknown")) {
            server.enqueue(new MockResponse().addHeader("Content-Type", mediaType).setBody(status("pending")));
            assertProtocol(() -> api.status(TOKEN));
        }
    }

    @Test
    void stripsInheritedInterceptorsBeforeSendingCredentials() throws Exception {
        OkHttpClient unsafe = new OkHttpClient.Builder()
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder().header("Injected-Secret", "unsafe").build()))
                .addNetworkInterceptor(chain -> chain.proceed(chain.request().newBuilder().header("Other-Secret", "unsafe").build()))
                .build();
        var isolated = new WebLicenseApi(unsafe, server.url("/"));
        enqueue(status("pending"));
        isolated.status(TOKEN);
        var request = request("GET", "/api/client/registration/status", true);
        assertNull(request.getHeader("Injected-Secret"));
        assertNull(request.getHeader("Other-Secret"));
        assertNull(request.getHeader("Cookie"));
    }

    @Test
    void validatesCredentialInputsBeforePerformingNetworkOperation() {
        assertThrows(LicenseApi.Failure.class, () -> api.status(TOKEN + "\r\nInjected: value"));
        assertThrows(LicenseApi.Failure.class, () -> api.status(null));
        assertThrows(LicenseApi.Failure.class, () -> api.start("customer@example.com"));
        assertThrows(LicenseApi.Failure.class, () -> api.verify(TOKEN, "1234567"));
        assertThrows(LicenseApi.Failure.class, () -> api.challenge(TOKEN, KEY, null));
        assertThrows(LicenseApi.Failure.class, () -> api.challenge(TOKEN, KEY, "delete"));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void testOriginCannotPointToRemoteServerOrCarryUrlCredentials() {
        for (String invalid : List.of("http://example.com/", "https://example.com/", "http://user:pass@localhost/",
                "http://localhost/subpath/", "http://localhost/?token=unsafe", "http://localhost/#unsafe")) {
            assertThrows(IllegalArgumentException.class, () -> new WebLicenseApi(new OkHttpClient(), HttpUrl.get(invalid)));
        }
    }

    @Test
    void connectionFailureDoesNotRetryAndContainsNoUpstreamDetails() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        var failure = assertThrows(LicenseApi.Failure.class, () -> api.status(TOKEN));
        assertEquals("LICENSE_NETWORK", failure.code());
        assertEquals(0, failure.status());
        assertNull(failure.getCause());
        assertEquals(1, server.getRequestCount());
    }

    private LicenseApi.Grant activate() throws IOException {
        return api.activate(TOKEN, challenge(), KEY, SIGNATURE, CODE);
    }

    private static LicenseApi.Challenge challenge() {
        return new LicenseApi.Challenge(ID, "challenge\nmessage", 2000000000L);
    }

    private static JsonObject grant(boolean activation) {
        JsonObject response = JsonParser.parseString("{\"user\":{\"id\":\"" + ID + "\",\"email\":\"" + EMAIL
                + "\",\"status\":\"approved\",\"active\":true,\"expires_at\":2000000000},\"device_id\":\"" + ID
                + "\",\"lease\":{\"key_id\":\"portal-v1\",\"payload\":\"e30\",\"signature\":\"" + SIGNATURE
                + "\"},\"online_valid_until\":1900000300}").getAsJsonObject();
        if (activation) {
            response.addProperty("access_token", TOKEN);
            response.addProperty("expires_in", 2592000);
        }
        return response;
    }

    private static String status(String status) {
        return "{\"email\":\"" + EMAIL + "\",\"status\":\"" + status + "\",\"expires_at\":null}";
    }

    private void enqueue(String body) { enqueue(body, 200); }

    private void enqueue(String body, int status) {
        server.enqueue(new MockResponse().setResponseCode(status)
                .addHeader("Content-Type", "application/json; charset=utf-8").setBody(body));
    }

    private RecordedRequest request(String method, String path, boolean authenticated) throws InterruptedException {
        var request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(method, request.getMethod());
        assertEquals(path, request.getPath());
        assertEquals(authenticated ? "Bearer " + TOKEN : null, request.getHeader("Authorization"));
        assertEquals("application/json", request.getHeader("Accept"));
        assertFalse(request.getPath().contains(TOKEN));
        assertFalse(request.getPath().contains(EMAIL));
        assertFalse(request.getPath().contains(CODE));
        return request;
    }

    private static JsonObject json(RecordedRequest request) {
        return JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
    }

    private static LicenseApi.Failure assertProtocol(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(LicenseApi.Failure.class, action);
        assertEquals("LICENSE_PROTOCOL_INVALID", failure.code());
        assertEquals(0, failure.status(), "Malformed responses must fail closed, regardless of HTTP status");
        return failure;
    }
}
