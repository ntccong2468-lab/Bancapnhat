package com.vncode.app.integration.znack;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class Wb1326SuzPacingTest {
    @Test void repeatedRequestsForOneOmsWaitButDifferentAccountsDoNot(){
        var clock=new AtomicLong(1_000);var pacing=new SuzRequestPacing(clock::get);
        assertEquals(0,pacing.reserve("origin-a|oms-a"));
        assertEquals(500,pacing.reserve("origin-a|oms-a"));
        assertEquals(0,pacing.reserve("origin-a|oms-b"));
        assertEquals(0,pacing.reserve("origin-b|oms-a"));
        clock.addAndGet(499);assertEquals(1,pacing.reserve("origin-a|oms-a"));
        clock.incrementAndGet();assertEquals(0,pacing.reserve("origin-a|oms-a"));
    }

    @Test void retryAfterExtendsOnlyTheAffectedAccountDeadline(){
        var clock=new AtomicLong(1_000);var pacing=new SuzRequestPacing(clock::get);
        assertEquals(0,pacing.reserve("oms-a"));pacing.defer("oms-a",2_000);
        assertEquals(2_000,pacing.reserve("oms-a"));assertEquals(0,pacing.reserve("oms-b"));
        clock.addAndGet(1_000);pacing.defer("oms-a",500);
        assertEquals(1_000,pacing.reserve("oms-a"),"A shorter subsequent hint must not reduce the existing hold");
        clock.addAndGet(1_000);assertEquals(0,pacing.reserve("oms-a"));
    }

    @Test void tooSmallServerDelayRetainsMinimumSpacingAndOverflowNeverAllowsImmediateReplay(){
        var clock=new AtomicLong(0);var pacing=new SuzRequestPacing(clock::get);
        pacing.defer("oms-a",-1);assertEquals(500,pacing.reserve("oms-a"));
        clock.set(Long.MAX_VALUE-100);pacing.defer("oms-b",1_000);
        assertEquals(100,pacing.reserve("oms-b"));
    }
}
