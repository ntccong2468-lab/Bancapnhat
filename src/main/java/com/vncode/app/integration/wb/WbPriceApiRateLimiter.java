package com.vncode.app.integration.wb;

import java.io.IOException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import okhttp3.Headers;

/** WB Prices and Discounts shares one quota across its endpoints for a seller.
 * Personal/service tokens use 600 ms (burst 5); base tokens use 15 minutes (burst 1).
 * Unknown token types conservatively use the base-token limit. */
public final class WbPriceApiRateLimiter {
    private static final Buckets BUCKETS=new Buckets(System::nanoTime,nanos->{
        try {Thread.sleep(nanos/1_000_000L,(int)(nanos%1_000_000L));}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("WB price API wait cancelled");}
    });
    private WbPriceApiRateLimiter(){}
    public static void awaitTurn(String apiKey)throws IOException{BUCKETS.awaitTurn(apiKey);}
    public static void registerRateLimit(String apiKey,Headers headers){BUCKETS.registerRateLimit(apiKey,headers);}
    /** Only personal/service tokens allow a fresh price read and guarded write within a practical window. */
    public static boolean supportsGuardedPriceWrite(String apiKey){return identity(apiKey).fast();}
    static final class Buckets {
        private final ConcurrentHashMap<String,AccountGate> gates=new ConcurrentHashMap<>();
        private final LongSupplier clock;private final WbContentApiRateLimiter.Sleeper sleeper;
        Buckets(LongSupplier clock,WbContentApiRateLimiter.Sleeper sleeper){this.clock=clock;this.sleeper=sleeper;}
        private AccountGate gate(Identity identity){
            return gates.computeIfAbsent(identity.scope(),ignored->new AccountGate(
                    new WbContentApiRateLimiter.Gate(Duration.ofMillis(600),5,clock,sleeper),
                    new WbContentApiRateLimiter.Gate(Duration.ofMinutes(15),1,clock,sleeper)));
        }
        void awaitTurn(String apiKey)throws IOException{var identity=identity(apiKey);var account=gate(identity);account.shared().await();if(!identity.fast())account.base().await();}
        void registerRateLimit(String apiKey,Headers headers){var identity=identity(apiKey);var account=gate(identity);var delay=com.vncode.app.integration.listing.ListingRateLimitException.delay(headers);account.shared().cooldown(delay);if(!identity.fast())account.base().cooldown(delay);}
    }
    private record AccountGate(WbContentApiRateLimiter.Gate shared,WbContentApiRateLimiter.Gate base){}
    private record Identity(String scope,boolean fast){}
    private static Identity identity(String token){
        String scope=com.vncode.app.integration.listing.ListingIdentity.hash(token==null?"":token);
        if(token==null||token.length()>16_384)return new Identity(scope,false);
        try {
            var parts=token.strip().split("\\.");if(parts.length!=3)return new Identity(scope,false);
            var payload=com.google.gson.JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8)).getAsJsonObject();
            var sid=payload.get("sid");if(sid!=null&&sid.isJsonPrimitive()&&sid.getAsJsonPrimitive().isString()){
                String value=sid.getAsString();if(value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))scope=com.vncode.app.integration.listing.ListingIdentity.hash("wb-seller:"+value.toLowerCase(java.util.Locale.ROOT));
            }
            var acc=payload.get("acc");if(acc==null||!acc.isJsonPrimitive()||!acc.getAsJsonPrimitive().isNumber())return new Identity(scope,false);
            int type=acc.getAsBigDecimal().intValueExact();
            return new Identity(scope,type==3||type==4);
        } catch(RuntimeException invalid) {return new Identity(scope,false);}
    }
}
