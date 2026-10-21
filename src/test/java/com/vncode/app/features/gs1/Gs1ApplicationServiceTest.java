package com.vncode.app.features.gs1;

import com.google.gson.*;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Gs1ApplicationServiceTest {
    @TempDir Path directory;
    private static final String XML="<application><inn>7707083893</inn><companyName>Fixture</companyName></application>";
    private JsonObject form(){return JsonParser.parseString("{\"applicant\":{\"inn\":\"7707083893\",\"companyName\":\"Fixture\"}}").getAsJsonObject();}
    private NationalCatalogGs1Client.Session session(MockWebServer server)throws Exception{
        server.enqueue(new MockResponse().setBody("{\"data\":\"YWJj\"}"));
        server.enqueue(new MockResponse().setBody("{\"csrfToken\":\"fixture\",\"gs1Member\":false}"));
        return new NationalCatalogGs1Client(new OkHttpClient.Builder().callTimeout(Duration.ofMillis(500)).build(),server.url("/rest/")).login("7707083893","A".repeat(40),(p,c)->new CryptoProSigningResult(new byte[]{1},""));
    }
    private void preparation(MockWebServer server,String xml){
        server.enqueue(new MockResponse().setBody(form().toString()));
        server.enqueue(new MockResponse().setBody("{}"));server.enqueue(new MockResponse().setBody(form().toString()));
        var response=new JsonObject();response.addProperty("XMLForSign",xml);server.enqueue(new MockResponse().setBody(response.toString()));
    }
    @Test void changedFormCannotSignPreviousDocumentReview()throws Exception{
        try(var server=new MockWebServer()){
            var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1ApplicationService(repo);
            var session=session(server);preparation(server,XML);var preview=service.prepareSigning(session,form());
            var changed=form();changed.getAsJsonObject("applicant").addProperty("companyName","Changed");server.enqueue(new MockResponse().setBody(changed.toString()));
            var calls=new AtomicInteger();
            assertThrows(IllegalStateException.class,()->service.submit(session,preview,xml->{calls.incrementAndGet();return "signed";}));
            assertEquals(0,calls.get());assertEquals(Gs1Repository.State.DRAFT,repo.request(session.inn(),preview.requestId()).state());
        }
    }
    @Test void submittedSignsExactlyThePreparedXmlAndRequiresReadback()throws Exception{
        try(var server=new MockWebServer()){
            var session=session(server);preparation(server,XML);
            var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1ApplicationService(repo);var preview=service.prepareSigning(session,form());
            assertEquals(XML,preview.xml());
            server.enqueue(new MockResponse().setBody(form().toString()));server.enqueue(new MockResponse().setBody("{}"));
            var after=form();after.addProperty("isSent",true);server.enqueue(new MockResponse().setBody(after.toString()));
            assertEquals(Gs1Repository.State.SUBMITTED,service.submit(session,preview,xml->{assertEquals(XML,xml);return "<signed/>";}));
            assertTrue(repo.membership(session.inn()).isEmpty());assertFalse(repo.claimSend(session.inn(),preview.requestId()));
            int documentFetches=0;
            for(int i=0;i<server.getRequestCount();i++){var request=server.takeRequest();if(request.getPath().endsWith("/gs1/sign-form")&&request.getMethod().equals("GET"))documentFetches++;}
            assertEquals(1,documentFetches,"No replacement document may be fetched after review");
        }
    }
    @Test void foreignXmlAndCommentOnlyIdentityCannotReachTheSigner()throws Exception{
        try(var server=new MockWebServer()){
            var session=session(server);var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1ApplicationService(repo);
            preparation(server,"<application><!-- 7707083893 Fixture --><inn>7811089030</inn><companyName>Other</companyName></application>");
            assertThrows(java.io.IOException.class,()->service.prepareSigning(session,form()));assertTrue(repo.requests(session.inn()).isEmpty());
        }
    }
    @Test void ambiguousSubmissionBlocksNewPreparationWithoutAnotherWrite()throws Exception{
        try(var server=new MockWebServer()){
            var session=session(server);preparation(server,XML);var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1ApplicationService(repo);
            var preview=service.prepareSigning(session,form());server.enqueue(new MockResponse().setBody(form().toString()));server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            assertThrows(java.io.IOException.class,()->service.submit(session,preview,xml->"signed"));
            assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,repo.request(session.inn(),preview.requestId()).state());
            assertThrows(IllegalStateException.class,()->service.prepareSigning(session,form()));assertEquals(8,server.getRequestCount());
        }
    }
    @Test void xmlCannotHideForeignOwnerBesideExpectedValues()throws Exception{
        try(var server=new MockWebServer()){
            var session=session(server);var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1ApplicationService(repo);
            preparation(server,"<application><inn>7811089030</inn><companyName>Other</companyName><unrelated><value>7707083893</value><value>Fixture</value></unrelated></application>");
            assertThrows(java.io.IOException.class,()->service.prepareSigning(session,form()));assertTrue(repo.requests(session.inn()).isEmpty());
        }
    }
    @Test void aCancelledSignatureCanBeRetriedWithoutReconcilingAnUnsentApplication()throws Exception{
        try(var server=new MockWebServer()){
            var session=session(server);preparation(server,XML);
            var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1ApplicationService(repo);var preview=service.prepareSigning(session,form());
            server.enqueue(new MockResponse().setBody(form().toString()));
            assertThrows(java.io.IOException.class,()->service.submit(session,preview,xml->{throw new Exception("User cancelled signing");}));
            assertEquals(Gs1Repository.State.DRAFT,repo.request(session.inn(),preview.requestId()).state());assertEquals(7,server.getRequestCount());
            server.enqueue(new MockResponse().setBody(form().toString()));server.enqueue(new MockResponse().setBody("{}"));
            var after=form();after.addProperty("isSent",true);server.enqueue(new MockResponse().setBody(after.toString()));
            assertEquals(Gs1Repository.State.SUBMITTED,service.submit(session,preview,xml->"<signed/>"));
        }
    }
    @Test void remotelySubmittedApplicationBlocksStaleEditorBeforeAnyDraftWrite() throws Exception {
        for(boolean saveOnly:java.util.List.of(false,true))for(boolean malformedError:java.util.List.of(false,true))try(var server=new MockWebServer()){
            var session=session(server);var repo=new Gs1Repository(directory.resolve("stale.db"));var service=new Gs1ApplicationService(repo);
            var submitted=form();submitted.addProperty("isSent",true);if(malformedError)submitted.addProperty("errorText",false);server.enqueue(new MockResponse().setBody(submitted.toString()));
            assertThrows(IllegalStateException.class,()->{if(saveOnly)service.saveReviewedDraft(session,form());else service.prepareSigning(session,form());});
            assertTrue(repo.requests(session.inn()).isEmpty());assertEquals(3,server.getRequestCount());
            for(int i=0;i<3;i++){var request=server.takeRequest();assertFalse(request.getMethod().equals("POST")&&request.getPath().endsWith("gs1/form"));}
        }
    }
    @Test void rejectedRemoteSentFlagAllowsReviewedCorrectionAndExactXmlSubmission() throws Exception {
        try(var server=new MockWebServer()){
            var session=session(server);var repo=new Gs1Repository(directory.resolve("rejected.db"));var service=new Gs1ApplicationService(repo);
            var rejected=form();rejected.addProperty("isSent",true);rejected.addProperty("errorText","fixture correction required");
            server.enqueue(new MockResponse().setBody(rejected.toString()));
            server.enqueue(new MockResponse().setBody("{}"));server.enqueue(new MockResponse().setBody(form().toString()));
            var document=new JsonObject();document.addProperty("XMLForSign",XML);server.enqueue(new MockResponse().setBody(document.toString()));
            var reviewed=service.prepareSigning(session,form());
            // Official frontend resets isSent when errorText is nonempty; preserve the same precedence.
            server.enqueue(new MockResponse().setBody(rejected.toString()));server.enqueue(new MockResponse().setBody("{}"));
            var accepted=form();accepted.addProperty("isSent",true);server.enqueue(new MockResponse().setBody(accepted.toString()));
            var signs=new AtomicInteger();
            assertEquals(Gs1Repository.State.SUBMITTED,service.submit(session,reviewed,xml->{assertEquals(XML,xml);signs.incrementAndGet();return "<signed/>";}));
            assertEquals(1,signs.get());
            int signPosts=0;int count=server.getRequestCount();
            for(int i=0;i<count;i++){var request=server.takeRequest();if(request.getMethod().equals("POST")&&request.getPath().endsWith("gs1/sign-form"))signPosts++;}
            assertEquals(1,signPosts);
        }
    }
}
