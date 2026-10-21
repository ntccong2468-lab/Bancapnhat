package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.registration.ZnackNationalCatalogService.SigningDocument;
import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The real HTTP transport talks only to the local Catalog fixture; CMS bytes are synthetic. */
class ZnackCatalogSigningBatchTest {
    private HttpServer server;
    private final AtomicInteger posts=new AtomicInteger();
    private final AtomicReference<JsonArray> sent=new AtomicReference<>();
    private final AtomicReference<Throwable> handlerFailure=new AtomicReference<>();
    private final List<byte[]> signedPayloads=new ArrayList<>();
    private Function<List<Long>,JsonObject> acknowledgement=this::completeAcknowledgement;
    private int httpStatus=200;

    @BeforeEach void startFixture() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",this::handle);
        server.start();
    }
    @AfterEach void stopFixture() {if(server!=null)server.stop(0);}

    @Test void tenExactImmutableDocumentsHaveSeparateCmsAndOneActualPost() throws Exception {
        var documents=new ArrayList<SigningDocument>();
        for(int i=1;i<=10;i++) {
            byte[] original=xml(i);var document=new SigningDocument(i,original);original[0]='!';document.xml()[0]='?';documents.add(document);
        }
        var checkpoint=new AtomicBoolean();
        var result=service(signer()).sendReviewedSigningBatch("synthetic-token",documents,()->{
            assertEquals(10,signedPayloads.size(),"Freeze and sign every reviewed document before declaring dispatch");
            assertEquals(0,posts.get());assertTrue(checkpoint.compareAndSet(false,true));
        });
        assertEquals(documents.stream().map(SigningDocument::goodId).toList(),result);
        assertEquals(1,posts.get());assertTrue(checkpoint.get());assertNull(handlerFailure.get());
        assertEquals(10,sent.get().size());
        for(int i=1;i<=10;i++) {
            var item=sent.get().get(i-1).getAsJsonObject();
            assertEquals(i,item.get("goodId").getAsLong());
            assertArrayEquals(xml(i),signedPayloads.get(i-1));
            assertArrayEquals(xml(i),Base64.getDecoder().decode(item.get("base64Xml").getAsString()));
            assertArrayEquals(cms(xml(i)),Base64.getDecoder().decode(item.get("signature").getAsString()));
        }
    }

    @Test void emptyBatchIsRejectedBeforeAnyCmsOrRequest() {
        assertRejectedBeforeWire(List.of());
    }

    @Test void malformedSignatureAcknowledgementsRemainUncertainAfterOnePost()throws Exception {
        for(String raw:List.of("[\"1\"]","[1.5]","[1,1]","[true]","[{}]","[-1]")){
            acknowledgement=ignored->{var response=new JsonObject();response.add("signed",JsonParser.parseString(raw));return response;};
            int before=posts.get();var dispatched=new AtomicBoolean();
            assertThrows(IllegalStateException.class,()->service(signer()).sendReviewedSigningBatch("synthetic-token",List.of(document(1)),()->dispatched.set(true)));
            assertTrue(dispatched.get());assertEquals(before+1,posts.get(),"Uncertain acknowledgement cannot replay the signing POST");
        }
    }

    @Test void elevenCardsAreRejectedInsteadOfSilentlyTruncatingOrSplitting() {
        var documents=new ArrayList<SigningDocument>();for(int i=1;i<=11;i++)documents.add(document(i));
        assertRejectedBeforeWire(documents);
    }

    @Test void duplicateCardIdentityIsRejectedBeforeSigningEvenWhenXmlDiffers() {
        assertRejectedBeforeWire(List.of(document(1),new SigningDocument(1,xml(2))));
    }

    @Test void accountOrConsentGuardCanStillBlockAllWireWritesAfterCms() {
        var calls=new AtomicInteger();
        assertThrows(SecurityException.class,()->service(signer()).sendReviewedSigningBatch("synthetic-token",List.of(document(1),document(2)),()->{
            calls.incrementAndGet();assertEquals(2,signedPayloads.size());throw new SecurityException("review_changed");
        }));
        assertEquals(1,calls.get());assertEquals(0,posts.get());
    }

    @Test void localSignerFailureNeverRunsDispatchCheckpointOrSendsAPartialBatch() {
        var callback=new AtomicInteger();var calls=new AtomicInteger();
        var expected=new CryptoProException(CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE,"synthetic private key unavailable");
        ZnackSignatureProvider failing=(payload,context)->{
            if(calls.incrementAndGet()==2)throw expected;
            return new CryptoProSigningResult(cms(payload),"");
        };
        assertSame(expected,assertThrows(CryptoProException.class,()->service(failing).sendReviewedSigningBatch("synthetic-token",List.of(document(1),document(2)),callback::incrementAndGet)));
        assertEquals(2,calls.get());assertEquals(0,callback.get());assertEquals(0,posts.get());
    }

    @Test void partialAcknowledgementRemainsUncertainAfterExactlyOneDispatchedPost() {
        acknowledgement=ids->acknowledged(List.of(ids.getFirst()));
        assertUncertainAfterOnePost(IllegalStateException.class);
    }

    @Test void missingAcknowledgementCannotClaimTheWholeBatchSucceeded() {
        acknowledgement=ids->new JsonObject();
        assertUncertainAfterOnePost(IllegalStateException.class);
    }

    @Test void foreignAcknowledgementCannotStandInForRequestedCard() {
        acknowledgement=ids->acknowledged(List.of(ids.getFirst(),999L));
        assertUncertainAfterOnePost(IllegalStateException.class);
    }

    @Test void failedHttpResponseDoesNotReplayTheNonIdempotentSigningPost() {
        httpStatus=503;
        var failure=assertUncertainAfterOnePost(IOException.class);
        assertEquals("Automatic replay of a mutation request is blocked; reconcile its result.",failure.getMessage(),
                "OkHttp's 503 follow-up must be blocked before any second network transmission");
    }

    @Test void legacySingleCardEntryPointUsesTheSameReviewedWireBoundary() throws Exception {
        var callback=new AtomicInteger();
        assertEquals(1L,service(signer()).sendReviewedSigning("synthetic-token",document(1),callback::incrementAndGet));
        assertEquals(1,callback.get());assertEquals(1,posts.get());assertEquals(1,sent.get().size());
    }

    private void assertRejectedBeforeWire(List<SigningDocument> documents) {
        var callback=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->service(signer()).sendReviewedSigningBatch("synthetic-token",documents,callback::incrementAndGet));
        assertTrue(signedPayloads.isEmpty());assertEquals(0,callback.get());assertEquals(0,posts.get());
    }
    private <T extends Throwable> T assertUncertainAfterOnePost(Class<T> expected) {
        var dispatched=new AtomicBoolean();var calls=new AtomicInteger();
        T failure=assertThrows(expected,()->service(signer()).sendReviewedSigningBatch("synthetic-token",List.of(document(1),document(2)),()->{
            assertEquals(0,posts.get());assertEquals(2,signedPayloads.size());calls.incrementAndGet();dispatched.set(true);
        }));
        assertTrue(dispatched.get(),"The durable caller must reconcile this potentially applied request rather than replay it");
        assertEquals(1,calls.get());assertEquals(1,posts.get());assertNull(handlerFailure.get());
        return failure;
    }
    private ZnackNationalCatalogService service(ZnackSignatureProvider signer) {
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        var settings=new ZnackModels.Settings(base,"","","","","","","","","[]","","","",false);
        return new ZnackNationalCatalogService(new ZnackApiClient(),null,signer,settings);
    }
    private ZnackSignatureProvider signer() {
        return (payload,context)->{
            assertEquals(ZnackSignatureContext.TRUE_API_DOCUMENT,context);signedPayloads.add(payload.clone());
            return new CryptoProSigningResult(cms(payload),"");
        };
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            assertEquals("POST",exchange.getRequestMethod());
            assertEquals("/v3/feed-product-sign-pkcs",exchange.getRequestURI().getPath());
            var body=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonArray();
            posts.incrementAndGet();sent.set(body);
            var ids=body.asList().stream().map(item->item.getAsJsonObject().get("goodId").getAsLong()).toList();
            var response=new JsonObject();response.add("result",acknowledgement.apply(ids));
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            if(httpStatus==503)exchange.getResponseHeaders().add("Retry-After","0");
            exchange.sendResponseHeaders(httpStatus,bytes.length);exchange.getResponseBody().write(bytes);
        } catch(Throwable failure) {
            handlerFailure.compareAndSet(null,failure);exchange.sendResponseHeaders(500,-1);
        } finally {exchange.close();}
    }
    private JsonObject completeAcknowledgement(List<Long> ids) {
        var reversed=new ArrayList<>(ids);Collections.reverse(reversed);return acknowledged(reversed);
    }
    private static JsonObject acknowledged(List<Long> ids) {
        var result=new JsonObject();var signed=new JsonArray();ids.forEach(signed::add);result.add("signed",signed);return result;
    }
    private static SigningDocument document(long id) {return new SigningDocument(id,xml(id));}
    private static byte[] xml(long id) {return ("<card id=\""+id+"\">synthetic</card>").getBytes(StandardCharsets.UTF_8);}
    private static byte[] cms(byte[] xml) {return ("synthetic-cms:"+new String(xml,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);}
}
