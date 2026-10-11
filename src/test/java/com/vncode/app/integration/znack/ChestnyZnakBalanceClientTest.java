package com.vncode.app.integration.znack;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class ChestnyZnakBalanceClientTest {
    private MockWebServer server;private ChestnyZnakBalanceClient client;
    @BeforeEach void setup()throws Exception{server=new MockWebServer();server.start();client=new ChestnyZnakBalanceClient(new OkHttpClient(),server.url("/").uri(),uri->uri.getHost().equals("localhost"));}
    @AfterEach void close()throws Exception{server.shutdown();}
    private ChestnyZnakBalanceClient.ServiceSession session(){return new ChestnyZnakBalanceClient.ServiceSession("ELK",server.url("/").uri(),"7707083893","production","secret-fixture-token",Instant.now().plusSeconds(1200));}
    private void body(String value){server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(value));}
    @Test void certificateLoginSignsTextAttachedAndChoosesOnlyMatchingEnterprise()throws Exception {
        body("{\"uuid\":\"u1\",\"data\":\"challenge-one\"}");body("{\"organisations\":[{\"inn\":\"7811089030\"},{\"inn\":\"7707083893\"}]}");
        body("{\"uuid\":\"u2\",\"data\":\"challenge-two\"}");body("{\"access_token\":\"fixture-access\"}");body("{\"inn\":\"7707083893\"}");
        var signed=new java.util.ArrayList<String>();var session=client.authenticate("7707083893","A".repeat(40),(payload,context)->{
            assertEquals(com.vncode.app.integration.znack.signature.ZnackSignatureContext.AUTH_CHALLENGE,context);assertFalse(context.detached());signed.add(new String(payload,java.nio.charset.StandardCharsets.UTF_8));return new com.vncode.app.integration.znack.signature.CryptoProSigningResult(new byte[]{1,2,3},"");
        });assertEquals(java.util.List.of("challenge-one","challenge-two"),signed);assertEquals("7707083893",session.verifiedInn());
        server.takeRequest();var first=com.google.gson.JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject().getAsJsonObject("kep");assertFalse(first.get("mrdToken").getAsBoolean());assertFalse(first.has("inn"));
        server.takeRequest();var second=com.google.gson.JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject().getAsJsonObject("kep");assertTrue(second.get("mrdToken").getAsBoolean());assertEquals("7707083893",second.get("inn").getAsString());assertEquals("Bearer fixture-access",server.takeRequest().getHeader("Authorization"));
    }
    @Test void rateLimitAndRedirectNeverRetryOrForwardToken()throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","120"));assertThrows(java.io.IOException.class,()->client.read(new BalanceScope("7707083893","production","2","3"),session()));assertEquals(1,server.getRequestCount());
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","https://foreign.invalid/secret"));assertThrows(java.io.IOException.class,()->client.read(new BalanceScope("7707083893","production","2","3"),session()));assertEquals(2,server.getRequestCount());
    }
    @Test void zeroAndMinorUnitsStayExact()throws Exception{for(String minor:new String[]{"0","12345","-17"}){body("{\"inn\":\"7707083893\"}");body("[{\"organisationId\":1,\"productGroupId\":2,\"contractId\":3,\"balance\":"+minor+"}]");var result=client.read(new BalanceScope("7707083893","production","2","3"),session());assertEquals(new BigDecimal(minor).movePointLeft(2).setScale(2),result.amount());assertEquals("RUB",result.currency());}}
    @Test void foreignInnOrGroupRejected()throws Exception{body("{\"inn\":\"7811089030\"}");assertThrows(java.io.IOException.class,()->client.read(new BalanceScope("7707083893","production","2","3"),session()));assertEquals(1,server.getRequestCount());body("{\"inn\":\"7707083893\"}");body("[{\"organisationId\":1,\"productGroupId\":9,\"contractId\":3,\"balance\":0}]");assertThrows(java.io.IOException.class,()->client.read(new BalanceScope("7707083893","production","2","3"),session()));}
    @Test void separateServiceSessionsNeverTransmitTrueApiToken(){var wrong=new ChestnyZnakBalanceClient.ServiceSession("TRUE_API",server.url("/").uri(),"7707083893","production","secret",Instant.now().plusSeconds(1200));assertThrows(java.io.IOException.class,()->client.read(new BalanceScope("7707083893","production","2","3"),wrong));assertEquals(0,server.getRequestCount());assertFalse(wrong.toString().contains("secret"));}
    @Test void failuresAndMissingBalanceAreUnknown()throws Exception{server.enqueue(new MockResponse().setResponseCode(401).setBody("secret-fixture-token"));var error=assertThrows(java.io.IOException.class,()->client.read(new BalanceScope("7707083893","production","2","3"),session()));assertFalse(error.toString().contains("secret-fixture-token"));body("{\"inn\":\"7707083893\"}");body("[{\"organisationId\":1,\"productGroupId\":2,\"contractId\":3}]");assertThrows(java.io.IOException.class,()->client.read(new BalanceScope("7707083893","production","2","3"),session()));}
    @Test void expiredSessionAndForeignOriginSendNothing(){var expired=new ChestnyZnakBalanceClient.ServiceSession("ELK",server.url("/").uri(),"7707083893","production","secret",Instant.now().minusSeconds(1));assertThrows(java.io.IOException.class,()->client.read(new BalanceScope("7707083893","production","2","3"),expired));var wrong=new ChestnyZnakBalanceClient.ServiceSession("ELK",URI.create("https://foreign.invalid/"),"7707083893","production","secret",Instant.now().plusSeconds(1200));assertThrows(java.io.IOException.class,()->client.read(new BalanceScope("7707083893","production","2","3"),wrong));assertEquals(0,server.getRequestCount());}
}
