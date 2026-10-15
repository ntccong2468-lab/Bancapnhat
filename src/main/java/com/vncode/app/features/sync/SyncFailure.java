package com.vncode.app.features.sync;

import com.vncode.app.integration.ozon.OzonApiException;
import com.vncode.app.integration.wb.WbApiException;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import java.time.Duration;
import java.util.Locale;

/** Never persist arbitrary upstream messages, URLs, tokens or payloads. */
public record SyncFailure(String code,Duration delay,boolean actionRequired) {
    public static SyncFailure of(Throwable failure,int attempt) {
        for(Throwable e=failure;e!=null;e=e.getCause()) {
            if(e instanceof com.vncode.app.shared.RateLimitDeferral.Deferred deferred)return new SyncFailure("API_COOLDOWN",deferred.delay(),false);
            if(e instanceof LicenseDeniedException)return new SyncFailure("LICENSE_REQUIRED",Duration.ofMinutes(1),true);
            if(e instanceof OzonApiException o) {
                if(o.statusCode()==401||o.statusCode()==403)return new SyncFailure("OZON_AUTH_REQUIRED",Duration.ofMinutes(5),true);
                if(o.statusCode()==429)return new SyncFailure("OZON_RATE_LIMIT",o.retryAfter()==null?Duration.ofMinutes(1):o.retryAfter(),false);
                return new SyncFailure("OZON_"+o.safeErrorCode().toUpperCase(Locale.ROOT),backoff(attempt),!o.retryable());
            }
            if(e instanceof WbApiException w) {
                if(w.getStatusCode()==401||w.getStatusCode()==403)return new SyncFailure("WB_AUTH_REQUIRED",Duration.ofMinutes(5),true);
                if(w.getStatusCode()==429)return new SyncFailure("WB_RATE_LIMIT",Duration.ofMinutes(1),false);
                return new SyncFailure("WB_HTTP_"+w.getStatusCode(),backoff(attempt),false);
            }
            if(e instanceof java.net.SocketTimeoutException||e instanceof java.net.http.HttpTimeoutException)return new SyncFailure("NETWORK_TIMEOUT",backoff(attempt),false);
            if(e instanceof java.net.UnknownHostException)return new SyncFailure("NETWORK_DNS",backoff(attempt),false);
            if(e instanceof com.vncode.app.features.gtinsync.RegisteredGtinProof.UnverifiedOwnerException u)return new SyncFailure("CATALOG_"+u.reason().name(),Duration.ofMinutes(5),true);
        }
        String text=failure.getMessage();
        String safe=com.vncode.app.features.sync.SyncRunRepository.code(text);
        String code=!safe.isBlank()&&!safe.equals("DETAIL_NOT_AVAILABLE")?safe:failure instanceof java.io.IOException?"NETWORK_OR_RESPONSE":"SYNC_INTERNAL";
        return new SyncFailure(code,backoff(attempt),failure instanceof IllegalArgumentException||failure instanceof SecurityException);
    }
    private static Duration backoff(int attempt){return Duration.ofSeconds(Math.min(300,5L<<Math.min(6,Math.max(0,attempt))));}
}
