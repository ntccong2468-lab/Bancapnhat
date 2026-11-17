package com.vncode.app.integration.znack;

import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.vncode.app.integration.znack.Wb1326AuthIsolationRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact account/token invalidation and read-only session recovery; all service responses are synthetic. */
class Wb1326AuthSessionTest {
    @Test void rejectedSuzSessionDoesNotInvalidateNewerOtherAccountOrTrueApiSessions() throws Exception {
        var calls=new AtomicInteger();
        var auth=new ZnackAuthService(api(calls),(p,c)->new CryptoProSigningResult(new byte[]{1},"fixture"));
        var first=settings("connection-A","A".repeat(40),"7700000001");
        var other=settings("connection-B","B".repeat(40),"7700000002");
        String old=auth.suzToken(first),otherToken=auth.suzToken(other),trueToken=auth.trueApiToken(first);
        assertFalse(auth.invalidateSuzToken(other,old));
        assertTrue(auth.invalidateSuzToken(first,old));
        String renewed=auth.suzToken(first);
        assertNotEquals(old,renewed);
        assertFalse(auth.invalidateSuzToken(first,old));
        assertEquals(renewed,auth.suzToken(first));
        assertEquals(otherToken,auth.suzToken(other));
        assertEquals(trueToken,auth.trueApiToken(first));
        assertEquals(4,calls.get());
    }

    @Test void suzReadRetriesExactlyOnceAfter401AndPreservesOtherRole() throws Exception {
        var calls=new AtomicInteger();var reads=new AtomicInteger();
        var auth=new ZnackAuthService(api(calls),(p,c)->new CryptoProSigningResult(new byte[]{1},"fixture"));
        var settings=settings("connection-A","A".repeat(40),"7700000001");
        String trueToken=auth.trueApiToken(settings);
        String result=auth.readWithSuzRecovery(settings,token->{
            if(reads.incrementAndGet()==1)throw rejected(401);return token;
        });
        assertEquals(result,auth.suzToken(settings));assertEquals(2,reads.get());assertEquals(3,calls.get());
        assertEquals(trueToken,auth.trueApiToken(settings));
    }

    @Test void second401AndNonAuthenticationFailureAreNotRepeated() throws Exception {
        var calls=new AtomicInteger();var reads=new AtomicInteger();
        var auth=new ZnackAuthService(api(calls),(p,c)->new CryptoProSigningResult(new byte[]{1},"fixture"));
        var settings=settings("connection-A","A".repeat(40),"7700000001");
        assertThrows(ZnackApiClient.ZnackApiException.class,()->auth.readWithSuzRecovery(settings,token->{reads.incrementAndGet();throw rejected(401);}));
        assertEquals(2,reads.get());assertEquals(2,calls.get());
        reads.set(0);
        var failure=assertThrows(ZnackApiClient.ZnackApiException.class,()->auth.readWithSuzRecovery(settings,token->{reads.incrementAndGet();throw rejected(503);}));
        assertEquals(503,failure.statusCode());assertEquals(1,reads.get());assertEquals(2,calls.get());
    }

    @Test void trueApiReadRecoveryIsIsolatedFromSuzRole() throws Exception {
        var calls=new AtomicInteger();var reads=new AtomicInteger();
        var auth=new ZnackAuthService(api(calls),(p,c)->new CryptoProSigningResult(new byte[]{1},"fixture"));
        var settings=settings("connection-A","A".repeat(40),"7700000001");
        String suz=auth.suzToken(settings);
        String result=auth.readWithTrueApiRecovery(settings,token->{if(reads.incrementAndGet()==1)throw rejected(401);return token;});
        assertEquals(result,auth.trueApiToken(settings));assertEquals(suz,auth.suzToken(settings));
        assertEquals(2,reads.get());assertEquals(3,calls.get());
    }

    private static ZnackApiClient.ZnackApiException rejected(int status) {
        return new ZnackApiClient.ZnackApiException("Synthetic session response",status,"{}","GET","http://127.0.0.1:9/status");
    }
}
