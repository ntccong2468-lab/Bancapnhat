package com.vncode.app.features.license;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LicenseRefreshCadenceTest {
    @Test void aThousandClientsStayBelowAccountLimitsAndDoNotShareOneSchedule(){
        var buckets=new java.util.HashSet<Long>();int checks=0;
        for(int client=0;client<1000;client++){
            final int seed=client;var cadence=new LicenseRefreshCadence(()->seed);
            long start=TimeUnit.SECONDS.toNanos(client%51);cadence.completed(start,true);int accountChecks=0;
            for(int second=1;second<=3600;second++){
                long now=TimeUnit.SECONDS.toNanos(second);
                if(second*1_000_000_000L<start||!cadence.due(now))continue;
                if(accountChecks==0)buckets.add((long)second);
                cadence.completed(now,true);accountChecks++;
            }
            assertTrue(accountChecks<=24,"Automatic renewal must leave headroom below 30/hour");checks+=accountChecks;
        }
        assertTrue(buckets.size()>30);assertTrue(checks<24000);
    }
    @Test void failureBackoffIsBoundedAndSuccessfulRenewalRestoresNormalCadence(){
        var cadence=new LicenseRefreshCadence(()->0);assertTrue(cadence.due(0));cadence.completed(0,false);
        assertFalse(cadence.due(TimeUnit.SECONDS.toNanos(179)));assertTrue(cadence.due(TimeUnit.SECONDS.toNanos(180)));
        cadence.completed(TimeUnit.SECONDS.toNanos(180),false);assertFalse(cadence.due(TimeUnit.SECONDS.toNanos(539)));
        cadence.completed(TimeUnit.SECONDS.toNanos(540),true);assertTrue(cadence.due(TimeUnit.SECONDS.toNanos(690)));
        cadence.reset();assertTrue(cadence.due(TimeUnit.SECONDS.toNanos(541)));
    }
    @Test void monotonicWraparoundAndNegativeJitterDoNotCreateImmediateRetryLoops(){
        var cadence=new LicenseRefreshCadence(()->Integer.MIN_VALUE);long start=Long.MAX_VALUE-TimeUnit.SECONDS.toNanos(10);
        cadence.completed(start,true);assertFalse(cadence.due(start+TimeUnit.SECONDS.toNanos(149)));
        assertTrue(cadence.due(start+TimeUnit.SECONDS.toNanos(181)));
    }
}
