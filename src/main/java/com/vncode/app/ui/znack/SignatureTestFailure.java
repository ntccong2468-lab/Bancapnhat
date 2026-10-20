package com.vncode.app.ui.znack;

import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException;
import com.vncode.app.integration.znack.signature.CryptoProException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Describes the failed verification step without exposing upstream bodies, URLs or credentials. */
record SignatureTestFailure(SignatureTestFailure.Phase phase,String code,String messageKey,
                            Duration retryAfter,Integer httpStatus,CryptoProException cryptoError) {
    enum Phase { LOCAL_SIGNATURE, CRPT_AUTHENTICATION, SAVE_VERIFICATION }

    static SignatureTestFailure classify(Throwable failure,Phase phase) {
        var causes=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable current=failure;current!=null&&causes.add(current);current=current.getCause()) {
            // CryptoPro can fail while signing CRPT's challenge as well as the initial local proof.
            if(current instanceof CryptoProException crypto)
                return new SignatureTestFailure(Phase.LOCAL_SIGNATURE,"CRYPTOPRO_"+crypto.code().name(),"",null,null,crypto);
        }
        causes.clear();
        for(Throwable current=failure;current!=null&&causes.add(current);current=current.getCause()) {
            if(current instanceof LicenseDeniedException)
                return result(phase,"LICENSE_REQUIRED","verification_license",null,null);
            if(current instanceof java.util.concurrent.CancellationException||current instanceof InterruptedException
                    ||current instanceof java.io.InterruptedIOException&&!(current instanceof java.net.SocketTimeoutException))
                return result(phase,"SIGNATURE_VERIFICATION_CANCELLED","verification_cancelled",null,null);
            if(phase==Phase.CRPT_AUTHENTICATION) {
                if(current instanceof ZnackApiException api) {
                    int status=api.statusCode();
                    if(status==429)return result(phase,"ZNACK_RATE_LIMIT","remote_rate_limit",positiveDelay(api.retryAfter(),60),status);
                    if(status==401||status==403)return result(phase,"ZNACK_AUTH_REQUIRED","remote_rejected",null,status);
                    return result(phase,"ZNACK_HTTP_"+status,status>=500?"remote_unavailable":"remote_rejected",
                            status>=500?positiveDelay(api.retryAfter(),5):null,status);
                }
                if(current instanceof com.vncode.app.shared.RateLimitDeferral.Deferred deferred)
                    return result(phase,"API_COOLDOWN","remote_rate_limit",positiveDelay(deferred.delay(),60),null);
                if(current instanceof java.net.SocketTimeoutException||current instanceof java.net.http.HttpTimeoutException)
                    return result(phase,"NETWORK_TIMEOUT","remote_unavailable",Duration.ofSeconds(5),null);
                if(current instanceof java.net.UnknownHostException)
                    return result(phase,"NETWORK_DNS","remote_unavailable",Duration.ofSeconds(5),null);
                if(current instanceof java.io.IOException)
                    return result(phase,"NETWORK_OR_RESPONSE","remote_unavailable",Duration.ofSeconds(5),null);
            }
        }
        if(phase==Phase.SAVE_VERIFICATION)
            return result(phase,"SIGNATURE_VERIFICATION_SAVE_FAILED","verification_save",null,null);
        return result(phase,phase==Phase.CRPT_AUTHENTICATION?"ZNACK_AUTHENTICATION_FAILED":"SIGNATURE_VERIFICATION_FAILED",
                phase==Phase.CRPT_AUTHENTICATION?"remote_rejected":"verification_failed",null,null);
    }

    String diagnostic() {
        return "phase="+phase.name()+"; code="+code
                +(httpStatus==null?"":"; http_status="+httpStatus)
                +(retryAfter==null?"":"; retry_after_seconds="+retryAfter.toSeconds());
    }
    private static SignatureTestFailure result(Phase phase,String code,String key,Duration retry,Integer status) {
        return new SignatureTestFailure(phase,code,"znack.signature.error."+key,retry,status,null);
    }
    private static Duration positiveDelay(Duration value,long fallback) {
        return value==null||value.isZero()||value.isNegative()?Duration.ofSeconds(fallback):value;
    }
}
