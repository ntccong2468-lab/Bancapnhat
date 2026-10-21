package com.vncode.app.features.kiz;

import okhttp3.Headers;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Separate official families: read/delete 300/min; add 1000/min. No HTTP slot or
 * monitor is retained while waiting. FBS polling can yield on an existing cooldown. */
class WbLabelIdentifierRateLimiter {
    private static final ConcurrentHashMap<String,Gate> READS=new ConcurrentHashMap<>(),WRITES=new ConcurrentHashMap<>();
    static void awaitTurn(String key)throws IOException{awaitTurn(key,false);}
    static void awaitTurn(String key,boolean write)throws IOException{gate(key,write).await();}
    static void registerResponse(String key,int status,Headers headers){registerResponse(key,status,headers,false);}
    static void registerResponse(String key,int status,Headers headers,boolean write){
        if(status<400)return;
        gate(key,write).cooldown(status==429?com.vncode.app.integration.listing.ListingRateLimitException.delay(headers):Duration.ofMillis(write?600:2000));
    }
    private static Gate gate(String key,boolean write){return (write?WRITES:READS).computeIfAbsent(com.vncode.app.integration.listing.ListingIdentity.hash(key==null?"":key),k->new Gate(Duration.ofMillis(write?60:200),System::nanoTime,n->{try{Thread.sleep(n/1_000_000L,(int)(n%1_000_000L));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("WB label identifier wait cancelled");}}));}
    @FunctionalInterface interface Sleeper{void sleep(long nanos)throws IOException;}
    static final class Gate {
        private final long interval;private final LongSupplier clock;private final Sleeper sleeper;
        private long next=Long.MIN_VALUE,cooldown=Long.MIN_VALUE;
        Gate(Duration interval,LongSupplier clock,Sleeper sleeper){this.interval=interval.toNanos();this.clock=clock;this.sleeper=sleeper;}
        void await()throws IOException{
            long slot; synchronized(this){slot=Math.max(clock.getAsLong(),Math.max(next,cooldown));next=slot+interval;}
            while(true){long wait;
                synchronized(this){long now=clock.getAsLong();if(cooldown>slot&&cooldown>now){slot=Math.max(cooldown,next);next=slot+interval;}wait=slot-now;}
                if(wait<=0)return;
                com.vncode.app.shared.RateLimitDeferral.waiting(wait);sleeper.sleep(wait);
            }
        }
        synchronized void cooldown(Duration delay){long until=clock.getAsLong()+delay.toNanos();cooldown=Math.max(cooldown,until);next=Math.max(next,cooldown);}
    }
}
