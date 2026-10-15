package com.vncode.app.integration.license;

import com.google.gson.JsonObject;
import com.vncode.app.features.license.LicenseDeviceKeys;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.license.LicenseApi.*;

class LicenseConnectionRouterTest {
    static MockResponse json(String body,int status){return new MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body);}
    static final String STATUS="{\"email\":\"client@gmail.com\",\"status\":\"approved\",\"expires_at\":null}";
    static JsonObject proof(KeyPair key,String nonce,String origin,long now)throws Exception {
        var payload=new JsonObject();payload.addProperty("formatVersion",1);payload.addProperty("product","vncode");
        payload.addProperty("purpose","license-endpoint");payload.addProperty("nonce",nonce);payload.addProperty("origin",origin);
        payload.addProperty("issuedAt",now);payload.addProperty("expiresAt",now+120);
        var bytes=payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(bytes);
        var envelope=new JsonObject();envelope.addProperty("key_id","fixture");envelope.addProperty("payload",LicenseDeviceKeys.encode(bytes));
        envelope.addProperty("signature",LicenseDeviceKeys.encode(signer.sign()));return envelope;
    }
    @Test void automaticFailureSelectsCompatibleBeforeSendingCredentialsAndCachesRoute()throws Exception {
        try(var primary=new MockWebServer();var backup=new MockWebServer()) {
            primary.start();backup.start();
            primary.enqueue(json("{\"error\":\"EDGE\"}",503));
            primary.enqueue(json("{\"error\":\"AUTH_REQUIRED\"}",401));
            primary.enqueue(json(STATUS,200));primary.enqueue(json(STATUS,200));
            var api=new WebLicenseApi(new OkHttpClient(),primary.url("/"),backup.url("/"),Map.of());
            assertEquals("approved",api.status("A".repeat(43)).status());api.status("A".repeat(43));
            for(int i=0;i<2;i++){var probe=primary.takeRequest();assertNull(probe.getHeader("Authorization"));assertEquals("{}",probe.getBody().readUtf8());}
            for(int i=0;i<2;i++)assertEquals("Bearer "+"A".repeat(43),primary.takeRequest().getHeader("Authorization"));
            assertEquals(0,backup.getRequestCount());
        }
    }
    @Test void failedFastProbesStillAllowTrustedPrimaryAndNeverSendCredentialsToUnverifiedBackup()throws Exception {
        try(var primary=new MockWebServer();var backup=new MockWebServer()) {
            primary.start();backup.start();
            for(int i=0;i<2;i++){primary.enqueue(json("{}",503));backup.enqueue(json("{}",200));}
            primary.enqueue(json(STATUS,200));
            var api=new WebLicenseApi(new OkHttpClient(),primary.url("/"),backup.url("/"),Map.of());
            assertEquals("approved",api.status("A".repeat(43)).status(),"Fast probes must not veto a real trusted-primary request");
            primary.takeRequest();primary.takeRequest();
            assertEquals("Bearer "+"A".repeat(43),primary.takeRequest().getHeader("Authorization"));
            for(int i=0;i<2;i++){
                var request=backup.takeRequest(1,TimeUnit.SECONDS);assertNotNull(request);assertNull(request.getHeader("Authorization"));
                assertEquals("/api/client/license/endpoint-proof",request.getPath());
                var body=com.vncode.app.features.license.LicenseJson.object(request.getBody().readUtf8());assertEquals(Set.of("nonce"),body.keySet());
            }
            assertEquals(2,backup.getRequestCount());
        }
    }
    @Test void verifiedBackupReceivesCredentialsAndRecoveryUsesAnotherProfile()throws Exception {
        var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try(var primary=new MockWebServer();var backup=new MockWebServer()) {
            primary.start();backup.start();for(int i=0;i<2;i++)primary.enqueue(json("{}",503));
            var proofs=new AtomicInteger();
            backup.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public MockResponse dispatch(RecordedRequest request){
                try{
                    if(request.getPath().endsWith("endpoint-proof")){
                        proofs.incrementAndGet();assertNull(request.getHeader("Authorization"));
                        String nonce=com.vncode.app.features.license.LicenseJson.object(request.getBody().readUtf8()).get("nonce").getAsString();
                        return json(proof(key,nonce,backup.url("/").toString(),java.time.Instant.now().getEpochSecond()).toString(),200);
                    }
                    assertEquals("Bearer "+"A".repeat(43),request.getHeader("Authorization"));return json(STATUS,200);
                }catch(Exception failure){return json("{}",500);}
            }});
            var api=new WebLicenseApi(new OkHttpClient(),primary.url("/"),backup.url("/"),Map.of("fixture",key.getPublic()));
            assertEquals("approved",api.status("A".repeat(43)).status());assertEquals(1,proofs.get());
            // A reported transport failure invalidates the cached route, rather than replaying it.
            api.recoverConnection();for(int i=0;i<2;i++)primary.enqueue(json("{}",503));
            assertEquals("approved",api.status("A".repeat(43)).status());assertEquals(2,proofs.get());
        }
    }
    @Test void endpointProofRejectsWrongNonceOriginExpiredTamperedAndUnknownIssuer()throws Exception {
        var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var origin=HttpUrl.get("https://license.vncode.id.vn/");
        long now=java.time.Instant.now().getEpochSecond();String nonce="A".repeat(43);var envelope=proof(key,nonce,origin.toString(),now);
        var keys=Map.of("fixture",key.getPublic());LicenseEndpointProof.verify(envelope,nonce,origin,keys,now);
        assertThrows(java.io.IOException.class,()->LicenseEndpointProof.verify(envelope,"B".repeat(43),origin,keys,now));
        assertThrows(java.io.IOException.class,()->LicenseEndpointProof.verify(envelope,nonce,HttpUrl.get("https://vncode.id.vn/"),keys,now));
        assertThrows(java.io.IOException.class,()->LicenseEndpointProof.verify(envelope,nonce,origin,keys,now+500));
        assertThrows(java.io.IOException.class,()->LicenseEndpointProof.verify(envelope,nonce,origin,Map.of(),now));
        envelope.addProperty("signature","A".repeat(86));
        assertThrows(java.io.IOException.class,()->LicenseEndpointProof.verify(envelope,nonce,origin,keys,now));
    }
}
