package com.vncode.app.integration.gs1;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.features.gs1.Gs1ApplicationService;
import com.vncode.app.features.gs1.Gs1Repository;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP attempts, synthetic XML only; no certificate, Catalog or mailbox writes. */
class NationalCatalogGs1ReplayTest {
    private static final String INN="7707083893", FINGERPRINT="A".repeat(40);
    private static final String XML="<application><inn>7707083893</inn><companyName>Fixture</companyName></application>";
    @TempDir Path directory;

    @Test void signaturePostCannotBeReplayedByRetryAfterZero()throws Exception{
        try(var server=new MockWebServer()){
            var session=session(server);
            server.enqueue(new MockResponse().setResponseCode(503).addHeader("Retry-After","0"));
            server.enqueue(new MockResponse().setBody("{}"));
            IOException failure=null;
            try{session.submitDocument(XML,xml->{assertEquals(XML,xml);return "<signed-synthetic/>";});}catch(IOException refused){failure=refused;}
            assertEquals(3,server.getRequestCount(),"The two login requests must be followed by only one signature POST");
            assertNotNull(failure,"A first-response 503 must remain unconfirmed rather than turn into success by retry");
            server.takeRequest();server.takeRequest();var request=server.takeRequest();
            assertEquals("/rest/gs1/sign-form",request.getPath());assertEquals("POST",request.getMethod());
            assertEquals("fixture-csrf",request.getHeader("X-Csrf-Token"));assertEquals("session=fixture",request.getHeader("Cookie"));
            assertEquals("<signed-synthetic/>",JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().get("signedXML").getAsString());
            assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
        }
    }
    @Test void savingTheDraftAlsoUsesOneNetworkAttempt()throws Exception{
        try(var server=new MockWebServer()){
            var session=session(server);
            server.enqueue(new MockResponse().setResponseCode(503).addHeader("Retry-After","0"));
            server.enqueue(new MockResponse().setBody("{}"));
            IOException failure=null;try{session.saveForm(form(false));}catch(IOException refused){failure=refused;}
            assertEquals(3,server.getRequestCount(),"A reviewed form must not be saved a second time by transport follow-up");
            assertNotNull(failure);server.takeRequest();server.takeRequest();assertEquals("/rest/gs1/form",server.takeRequest().getPath());
            assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
        }
    }
    @Test void anAcceptedButUnacknowledgedSignatureIsReconciledWithoutAnotherPostOrSignature()throws Exception{
        try(var server=new MockWebServer()){
            var posts=new AtomicInteger();
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
                return switch(request.getPath()){
                    case "/rest/signed-data"->new MockResponse().setBody("{\"data\":\"YWJj\"}");
                    case "/rest/certlogin"->new MockResponse().setBody("{\"csrfToken\":\"fixture-csrf\",\"gs1Member\":false}");
                    case "/rest/gs1/form"->new MockResponse().setBody(request.getMethod().equals("GET")?form(posts.get()>0).toString():"{}");
                    case "/rest/gs1/sign-form"->{
                        if(request.getMethod().equals("GET")){var result=new JsonObject();result.addProperty("XMLForSign",XML);yield new MockResponse().setBody(result.toString());}
                        yield posts.incrementAndGet()==1?new MockResponse().setResponseCode(503).addHeader("Retry-After","0"):new MockResponse().setBody("{}");
                    }
                    default->new MockResponse().setResponseCode(404);
                };
            }});
            var session=client(server).login(INN,FINGERPRINT,(payload,context)->new CryptoProSigningResult(new byte[]{1},""));
            var repository=new Gs1Repository(directory.resolve("reconcile.db"));var service=new Gs1ApplicationService(repository);
            var reviewed=service.prepareSigning(session,form(false));var signs=new AtomicInteger();
            assertThrows(IOException.class,()->service.submit(session,reviewed,xml->{signs.incrementAndGet();assertEquals(XML,xml);return "<signed-synthetic/>";}));
            assertEquals(1,posts.get());assertEquals(1,signs.get());
            assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,repository.request(INN,reviewed.requestId()).state());
            assertThrows(IllegalStateException.class,()->service.prepareSigning(session,form(false)));
            assertEquals(Gs1Repository.State.SUBMITTED,service.reconcile(session,reviewed.requestId()));
            assertEquals(1,posts.get());assertEquals(1,signs.get());assertFalse(repository.claimSend(INN,reviewed.requestId()));
        }
    }
    @Test void httpOneMisdirectedResponseDoesNotSendAnotherBusinessPost()throws Exception{
        // This proves HTTP/1.1 behavior only, not the separate HTTP/2 coalesced-connection branch.
        for(boolean submit:List.of(false,true))try(var server=new MockWebServer()){
            var session=session(server);server.enqueue(new MockResponse().setResponseCode(421));server.enqueue(new MockResponse().setBody("{}"));
            assertThrows(IOException.class,()->{if(submit)session.submitDocument(XML,xml->"<signed-synthetic/>");else session.saveForm(form(false));});
            assertEquals(3,server.getRequestCount());
        }
    }
    @Test void certificateAuthenticationAndReadOnlyFollowUpsKeepTheirExistingSemantics()throws Exception{
        try(var server=new MockWebServer()){
            server.enqueue(new MockResponse().setBody("{\"data\":\"YWJj\"}"));
            server.enqueue(new MockResponse().setResponseCode(503).addHeader("Retry-After","0"));
            server.enqueue(new MockResponse().addHeader("Set-Cookie","session=fixture; Path=/rest; HttpOnly").setBody("{\"csrfToken\":\"fixture-csrf\",\"gs1Member\":false}"));
            var signs=new AtomicInteger();var session=client(server).login(INN,FINGERPRINT,(payload,context)->{signs.incrementAndGet();return new CryptoProSigningResult(new byte[]{1},"");});
            server.enqueue(new MockResponse().setResponseCode(503).addHeader("Retry-After","0"));server.enqueue(new MockResponse().setBody(form(false).toString()));
            assertEquals(INN,session.form().getAsJsonObject("applicant").get("inn").getAsString());assertEquals(5,server.getRequestCount());assertEquals(1,signs.get());
            server.takeRequest();server.takeRequest();server.takeRequest();
            for(int index=0;index<2;index++){var read=server.takeRequest();assertEquals("GET",read.getMethod());assertEquals("fixture-csrf",read.getHeader("X-Csrf-Token"));assertEquals("session=fixture",read.getHeader("Cookie"));}
        }
    }
    @Test void oneCompletedCallDoesNotBlockTheNextExplicitDraftSave()throws Exception{
        try(var server=new MockWebServer()){
            var session=session(server);server.enqueue(new MockResponse().setBody("{}"));server.enqueue(new MockResponse().setBody("{}"));
            session.saveForm(form(false));session.saveForm(form(false));assertEquals(4,server.getRequestCount());
        }
    }
    private static JsonObject form(boolean sent){var value=new JsonObject();var applicant=new JsonObject();applicant.addProperty("inn",INN);applicant.addProperty("companyName","Fixture");value.add("applicant",applicant);value.addProperty("isSent",sent);return value;}
    private static NationalCatalogGs1Client client(MockWebServer server){return new NationalCatalogGs1Client(new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(2)).build(),server.url("/rest/"));}
    private static NationalCatalogGs1Client.Session session(MockWebServer server)throws Exception{
        server.enqueue(new MockResponse().setBody("{\"data\":\"YWJj\"}"));
        server.enqueue(new MockResponse().addHeader("Set-Cookie","session=fixture; Path=/rest; HttpOnly").setBody("{\"csrfToken\":\"fixture-csrf\",\"gs1Member\":false}"));
        return client(server).login(INN,FINGERPRINT,(payload,context)->new CryptoProSigningResult(new byte[]{1},""));
    }
}
