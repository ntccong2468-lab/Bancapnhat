package com.vncode.app.integration.znack;

import com.google.gson.JsonObject;
import com.vncode.app.integration.znack.ZnackModels.Settings;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Uses existing auth entry points to reproduce cross-profile cache reuse, without any HTTP or private key. */
class Wb1326AuthIsolationRegressionTest {
    @Test void suzTokensAreNotReusedAcrossOmsConnectionsOrCertificates() throws Exception {
        var calls=new AtomicInteger();
        var auth=new ZnackAuthService(api(calls),(p,c)->new CryptoProSigningResult(new byte[]{1},"fixture"));
        String first=auth.suzToken(settings("connection-A","A".repeat(40),"7700000001"));
        assertNotEquals(first,auth.suzToken(settings("connection-B","A".repeat(40),"7700000001")));
        assertNotEquals(first,auth.suzToken(settings("connection-A","B".repeat(40),"7700000001")));
        assertEquals(first,auth.suzToken(settings("connection-A","A".repeat(40),"7700000001")));
        assertEquals(3,calls.get());
    }

    @Test void resolvedInnDoesNotBorrowAnotherProfilesAuthenticatedOwner() throws Exception {
        var calls=new AtomicInteger();
        var auth=new ZnackAuthService(api(calls),(p,c)->new CryptoProSigningResult(new byte[]{1},"fixture"));
        auth.suzToken(settings("connection-A","A".repeat(40),"7700000001"));
        Settings other=settings("connection-B","B".repeat(40),"");
        assertEquals("7700000002",auth.resolvedParticipantInn(other));
    }

    static ZnackApiClient api(AtomicInteger calls) {
        return new ZnackApiClient() {
            @Override public JsonObject authKey(String base) {
                var challenge=new JsonObject();challenge.addProperty("uuid","synthetic");challenge.addProperty("data","challenge");return challenge;
            }
            @Override public JsonObject signIn(String base,String connection,JsonObject body) {
                var response=new JsonObject();
                String claims="{\"inn\":\"7700000001\",\"fixture\":"+calls.incrementAndGet()+"}";
                response.addProperty("token","fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8))+".fixture");
                response.addProperty("expiresIn",3600);return response;
            }
        };
    }

    static Settings settings(String connection,String certificate,String inn) {
        return new Settings("http://127.0.0.1:9","http://127.0.0.1:9","synthetic-oms",connection,
                inn,"","","",certificate,"[]","","","",true,"","[]",
                "{\"inn\":\"7700000002\"}",Instant.parse("2026-01-01T00:00:00Z"));
    }
}
