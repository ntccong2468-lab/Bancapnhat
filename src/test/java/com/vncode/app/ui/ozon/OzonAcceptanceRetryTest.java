package com.vncode.app.ui.ozon;

import com.vncode.app.integration.ozon.OzonApiException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OzonAcceptanceRetryTest {
    @Test void definitiveRateLimitWaitsAndRetriesSameSelection() throws Exception {
        var attempts = new AtomicInteger(); var ownerChecks = new AtomicInteger(); var waits = new AtomicInteger();
        String result = OzonAcceptanceRetry.run(() -> {
            if (attempts.getAndIncrement() == 0) throw new OzonApiException("rate_limited",429,true,false,null);
            return "accepted";
        }, ownerChecks::incrementAndGet, ()->false, delay->{assertEquals(Duration.ofSeconds(60),delay);waits.incrementAndGet();},
                (delay,stopping)->{});
        assertEquals("accepted",result); assertEquals(2,attempts.get()); assertEquals(2,ownerChecks.get()); assertEquals(1,waits.get());
    }
    @Test void ambiguousTimeoutAndAmbiguousRateLimitAreNeverSentAgain() {
        for (var failure : new OzonApiException[]{new OzonApiException("timeout",0,true,true,null),new OzonApiException("rate_limited",429,true,true,null)}) {
            var attempts = new AtomicInteger();
            assertSame(failure,assertThrows(OzonApiException.class,()->OzonAcceptanceRetry.run(()->{attempts.incrementAndGet();throw failure;},()->{},()->false,d->{},(d,s)->{})));
            assertEquals(1,attempts.get());
        }
    }
    @Test void cancellationOrChangedOwnerStopsBeforeAnotherRequest() {
        var attempts = new AtomicInteger(); var checks=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->OzonAcceptanceRetry.run(()->{attempts.incrementAndGet();throw new OzonApiException("rate_limited",429,true,false,null);},
                ()->{if(checks.incrementAndGet()>1)throw new IllegalStateException("shop_changed");},()->false,d->{},(d,s)->{}));
        assertEquals(1,attempts.get());
        assertThrows(CancellationException.class,()->OzonAcceptanceRetry.run(()->"bad",()->{},()->true,d->{},(d,s)->{}));
    }
}
