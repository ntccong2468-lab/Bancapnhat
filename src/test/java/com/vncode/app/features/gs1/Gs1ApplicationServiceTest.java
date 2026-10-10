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
import static org.junit.jupiter.api.Assertions.*;

class Gs1ApplicationServiceTest {
    @TempDir Path directory;
    private JsonObject form(){return JsonParser.parseString("{\"applicant\":{\"inn\":\"7707083893\",\"companyName\":\"Fixture\"}}").getAsJsonObject();}
    private NationalCatalogGs1Client.Session session(MockWebServer server)throws Exception{
        server.enqueue(new MockResponse().setBody("{\"data\":\"YWJj\"}"));
        server.enqueue(new MockResponse().setBody("{\"csrfToken\":\"fixture\",\"gs1Member\":false}"));
        return new NationalCatalogGs1Client(new OkHttpClient.Builder().callTimeout(Duration.ofMillis(500)).build(),server.url("/rest/")).login("7707083893","A".repeat(40),(p,c)->new CryptoProSigningResult(new byte[]{1},""));
    }
    @Test void changedFormCannotSignPreviousPreview()throws Exception{
        try(var server=new MockWebServer()){
            var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1ApplicationService(repo);
            var preview=service.prepare("7707083893",form());var changed=form();changed.getAsJsonObject("applicant").addProperty("companyName","Changed");
            var session=session(server);
            assertThrows(IllegalStateException.class,()->service.submit(session,preview.id(),changed,xml->"signed"));
            assertEquals(2,server.getRequestCount());
            assertEquals(Gs1Repository.State.DRAFT,repo.request(preview.inn(),preview.id()).state());
        }
    }
    @Test void submittedRequiresReadbackAndIsNotMembershipAcceptance()throws Exception{
        try(var server=new MockWebServer()){
            var session=session(server);
            server.enqueue(new MockResponse().setBody("{}"));server.enqueue(new MockResponse().setBody(form().toString()));
            server.enqueue(new MockResponse().setBody("{\"XMLForSign\":\"<application/>\"}"));server.enqueue(new MockResponse().setBody("{}"));
            var after=form();after.addProperty("isSent",true);server.enqueue(new MockResponse().setBody(after.toString()));
            var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1ApplicationService(repo);var preview=service.prepare("7707083893",form());
            assertEquals(Gs1Repository.State.SUBMITTED,service.submit(session,preview.id(),form(),xml->{assertEquals("<application/>",xml);return "<application><Signature/></application>";}));
            assertTrue(repo.membership(preview.inn()).isEmpty());
            assertFalse(repo.claimSend(preview.inn(),preview.id()));
        }
    }
    @Test void ambiguousSubmissionRequiresReconciliationBeforeAnyNewSubmission()throws Exception{
        try(var server=new MockWebServer()){
            var session=session(server);
            server.enqueue(new MockResponse().setBody("{}"));server.enqueue(new MockResponse().setBody(form().toString()));
            server.enqueue(new MockResponse().setBody("{\"XMLForSign\":\"<application/>\"}"));server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1ApplicationService(repo);var preview=service.prepare("7707083893",form());
            assertThrows(java.io.IOException.class,()->service.submit(session,preview.id(),form(),xml->"signed"));
            assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,repo.request(preview.inn(),preview.id()).state());
            var second=service.prepare(preview.inn(),form());
            assertThrows(IllegalStateException.class,()->service.submit(session,second.id(),form(),xml->"signed"));
            assertEquals(6,server.getRequestCount());
        }
    }
}
