package com.vncode.app.integration.wb;

import okhttp3.Headers;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Official per-account buckets: shared Content 100/min, cards/update 10/min, burst 5.
 * An update is an exception to the shared Content bucket. Cooldown waits never hold HTTP slots. */
public final class WbContentApiRateLimiter {
    private static final ConcurrentHashMap<String,Gate> READS=new ConcurrentHashMap<>(),UPDATES=new ConcurrentHashMap<>();
    private WbContentApiRateLimiter(){}
    public static void awaitTurn(String apiKey)throws IOException{awaitTurn(apiKey,"");}
    public static void awaitTurn(String apiKey,String path)throws IOException{bucket(apiKey,path).await();}
    public static void registerRateLimit(String apiKey,Headers headers){registerRateLimit(apiKey,"",headers);}
    public static void registerRateLimit(String apiKey,String path,Headers headers){bucket(apiKey,path).cooldown(com.vncode.app.integration.listing.ListingRateLimitException.delay(headers));}
    private static Gate bucket(String apiKey,String path){
        boolean update="content/v2/cards/update".equals(path)||"/content/v2/cards/update".equals(path);
        return (update?UPDATES:READS).computeIfAbsent(com.vncode.app.integration.listing.ListingIdentity.hash(apiKey==null?"":apiKey),ignored->new Gate(Duration.ofMillis(update?6000:600),5,System::nanoTime,nanos->{try{Thread.sleep(nanos/1_000_000L,(int)(nanos%1_000_000L));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("WB API wait cancelled");}}));
    }
    @FunctionalInterface interface Sleeper{void sleep(long nanos)throws IOException;}
    static final class Gate {
        private final long interval;private final int burst;private final LongSupplier clock;private final Sleeper sleep;
        private double tokens;private long updated,cooldown=Long.MIN_VALUE;
        Gate(Duration spacing,int burst,LongSupplier clock,Sleeper sleep){if(spacing.isNegative()||spacing.isZero()||burst<1)throw new IllegalArgumentException("invalid_rate_policy");interval=spacing.toNanos();this.burst=burst;this.clock=clock;this.sleep=sleep;tokens=burst;updated=clock.getAsLong();}
        void await()throws IOException{
            while(true){long wait;
                synchronized(this){long now=clock.getAsLong();if(now<cooldown)wait=cooldown-now;
                    else {if(now>updated){tokens=Math.min(burst,tokens+(double)(now-updated)/interval);updated=now;}if(tokens>=1){tokens-=1;return;}wait=Math.max(1,(long)Math.ceil((1-tokens)*interval));}
                }
                com.vncode.app.shared.RateLimitDeferral.waiting(wait);sleep.sleep(wait);
            }
        }
        synchronized void cooldown(Duration duration){long now=clock.getAsLong(),delay=duration.toNanos();long until=now+delay;if(until>cooldown){cooldown=until;updated=until;tokens=1;}}
    }
}
