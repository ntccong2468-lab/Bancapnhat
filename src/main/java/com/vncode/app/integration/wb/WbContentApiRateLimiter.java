package com.vncode.app.integration.wb;

import okhttp3.Headers;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Content reads use 100/min; updates and the error-list/limits group each use 10/min, burst 5.
 * Cooldown waits never hold HTTP slots. */
public final class WbContentApiRateLimiter {
    private static final Buckets BUCKETS=new Buckets(System::nanoTime,nanos->{try{Thread.sleep(nanos/1_000_000L,(int)(nanos%1_000_000L));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("WB API wait cancelled");}});
    private WbContentApiRateLimiter(){}
    public static void awaitTurn(String apiKey)throws IOException{awaitTurn(apiKey,"");}
    public static void awaitTurn(String apiKey,String path)throws IOException{BUCKETS.awaitTurn(apiKey,path);}
    public static void registerRateLimit(String apiKey,Headers headers){registerRateLimit(apiKey,"",headers);}
    public static void registerRateLimit(String apiKey,String path,Headers headers){BUCKETS.registerRateLimit(apiKey,path,headers);}
    @FunctionalInterface interface Sleeper{void sleep(long nanos)throws IOException;}
    /** Injectable clocks keep path routing and shared cooldown tests independent of wall time. */
    static final class Buckets {
        private final ConcurrentHashMap<String,Gate> reads=new ConcurrentHashMap<>(),updates=new ConcurrentHashMap<>(),errors=new ConcurrentHashMap<>();
        private final LongSupplier clock;private final Sleeper sleep;
        Buckets(LongSupplier clock,Sleeper sleep){this.clock=clock;this.sleep=sleep;}
        void awaitTurn(String apiKey,String path)throws IOException{bucket(apiKey,path).await();}
        void registerRateLimit(String apiKey,String path,Headers headers){bucket(apiKey,path).cooldown(com.vncode.app.integration.listing.ListingRateLimitException.delay(headers));}
        private Gate bucket(String apiKey,String path){
            String normalized=path!=null&&path.startsWith("/")?path.substring(1):path;
            boolean update="content/v2/cards/update".equals(normalized);
            // WB documents the error list and listing limits as one shared quota group.
            boolean errorList="content/v2/cards/error/list".equals(normalized)||"content/v2/cards/limits".equals(normalized);
            return (update?updates:errorList?errors:reads).computeIfAbsent(com.vncode.app.integration.listing.ListingIdentity.hash(apiKey==null?"":apiKey),ignored->new Gate(Duration.ofMillis(update||errorList?6000:600),5,clock,sleep));
        }
    }
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
