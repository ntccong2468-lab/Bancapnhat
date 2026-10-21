package com.vncode.app.integration.listing;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import okhttp3.Headers;

/** HTTP 429 explicitly rejected this request; a timeout or HTTP 5xx is not equivalent. */
public final class ListingRateLimitException extends IOException {
    private final Duration retryAfter;
    public ListingRateLimitException(Headers headers){super("MARKETPLACE_RATE_LIMIT");retryAfter=delay(headers);}
    public Duration retryAfter(){return retryAfter;}
    public static Duration delay(Headers headers){
        Duration result=Duration.ofSeconds(60);boolean explicit=false;
        if(headers!=null)for(String name:new String[]{"Retry-After","X-Ratelimit-Reset"}){
            String value=headers.get(name);if(value==null||value.isBlank())continue;Duration parsed=null;
            try{var seconds=new BigDecimal(value.strip());if(seconds.signum()>=0)parsed=Duration.ofMillis(seconds.multiply(BigDecimal.valueOf(1000)).longValueExact());}catch(RuntimeException invalid){if(name.equals("Retry-After"))try{parsed=Duration.between(Instant.now(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());}catch(RuntimeException ignored){}}
            if(parsed!=null&&!parsed.isNegative()){if(!explicit||parsed.compareTo(result)>0)result=parsed;explicit=true;}
        }
        if(result.compareTo(Duration.ofDays(1))>0)throw new IllegalStateException("MARKETPLACE_COOLDOWN_TOO_LONG");
        return result.isZero()?Duration.ofMillis(1):result;
    }
}
