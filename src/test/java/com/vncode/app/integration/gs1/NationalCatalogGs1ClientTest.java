package com.vncode.app.integration.gs1;

import com.google.gson.JsonParser;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class NationalCatalogGs1ClientTest {
    private static final String INN = "7707083893";
    private static final String FINGERPRINT = "A".repeat(40);
    private NationalCatalogGs1Client client(MockWebServer server) {
        return new NationalCatalogGs1Client(new OkHttpClient.Builder().callTimeout(Duration.ofMillis(350)).build(), server.url("/rest/"));
    }
    private void loginResponses(MockWebServer server) {
        server.enqueue(new MockResponse().setBody("{\"data\":\"YWJj\"}"));
        server.enqueue(new MockResponse().setHeader("Set-Cookie", "session=fixture; Path=/rest; HttpOnly")
                .setBody("{\"csrfToken\":\"fixture-csrf\",\"gs1Member\":true}"));
    }
    @Test void sessionIsolationAndCsrf() throws Exception {
        try (var server = new MockWebServer()) {
            loginResponses(server);
            server.enqueue(new MockResponse().setBody("{\"gcps\":[{\"gcp\":\"4601234\",\"gtinsLeft\":50,\"glns\":[]}],\"expiryDate\":\"2027-01-01\"}"));
            server.enqueue(new MockResponse().setBody("{\"applicant\":{\"inn\":\"7707083893\",\"companyName\":\"Fixture\"}}"));
            var session = client(server).login(INN, FINGERPRINT, (payload, context) -> {
                assertArrayEquals(new byte[]{97,98,99}, payload);
                assertTrue(context.detached());
                return new CryptoProSigningResult(new byte[]{1,2,3}, "");
            });
            var value = session.membership();
            assertEquals(50, value.prefixes().getFirst().gtinsLeft());
            assertEquals(Gs1Membership.Status.ACTIVE, value.status(LocalDate.of(2026,10,10)));
            assertEquals("/rest/signed-data", server.takeRequest().getPath());
            var login = server.takeRequest();
            assertEquals(INN, JsonParser.parseString(login.getBody().readUtf8()).getAsJsonObject().get("inn").getAsString());
            var request = server.takeRequest();
            assertEquals("fixture-csrf", request.getHeader("X-Csrf-Token"));
            assertEquals("session=fixture", request.getHeader("Cookie"));
            assertEquals("/rest/gs1/gcp-gln", request.getPath());
            server.takeRequest();
            loginResponses(server);
            client(server).login(INN, FINGERPRINT, (p,c) -> new CryptoProSigningResult(new byte[]{1}, ""));
            assertNull(server.takeRequest().getHeader("Cookie"), "A second company session must not inherit another login cookie");
        }
    }
    @Test void mutationTimeoutDoesNotRetry() throws Exception {
        try (var server = new MockWebServer()) {
            loginResponses(server);
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var session=client(server).login(INN,FINGERPRINT,(p,c)->new CryptoProSigningResult(new byte[]{1},""));
            assertThrows(IOException.class, () -> session.saveForm(JsonParser.parseString("{\"applicant\":{\"inn\":\"7707083893\"}}").getAsJsonObject()));
            assertEquals(3, server.getRequestCount());
            server.takeRequest();server.takeRequest();server.takeRequest();
            assertNull(server.takeRequest(150,TimeUnit.MILLISECONDS));
        }
    }
    @Test void foreignInnAndInvalidDateNeverProduceActiveMembership() throws Exception {
        try(var server=new MockWebServer()) {
            loginResponses(server);
            server.enqueue(new MockResponse().setBody("{\"gcps\":[],\"expiryDate\":\"garbage\"}"));
            server.enqueue(new MockResponse().setBody("{\"applicant\":{\"inn\":\"7811089030\"}}"));
            var session=client(server).login(INN,FINGERPRINT,(p,c)->new CryptoProSigningResult(new byte[]{1},""));
            assertThrows(IOException.class,session::membership);
        }
    }
    @Test void missingAssertionIsUnknownEvenWithQuotaAndExpiry() throws Exception {
        try(var server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"data\":\"YWJj\"}"));
            server.enqueue(new MockResponse().setBody("{\"csrfToken\":\"fixture\"}"));
            server.enqueue(new MockResponse().setBody("{\"gcps\":[],\"expiryDate\":\"2027-01-01\"}"));
            server.enqueue(new MockResponse().setBody("{\"applicant\":{\"inn\":\"7707083893\"}}"));
            assertEquals(Gs1Membership.Status.UNKNOWN,client(server).login(INN,FINGERPRINT,(p,c)->new CryptoProSigningResult(new byte[]{1},"")).membership().status(LocalDate.of(2026,10,10)));
        }
    }
    @Test void upstreamSensitiveErrorsAreNotReturned() throws Exception {
        try(var server=new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"token=secret company=private\"}"));
            var error=assertThrows(IOException.class,()->client(server).login(INN,FINGERPRINT,(p,c)->new CryptoProSigningResult(new byte[]{1},"")));
            assertFalse(error.getMessage().contains("secret"));
            assertFalse(error.getMessage().contains("private"));
        }
    }
}
