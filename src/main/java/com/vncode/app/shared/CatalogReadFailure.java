package com.vncode.app.shared;

import com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;

/** Local diagnostic categories, not upstream business codes or evidence of missing GTIN cards. */
public record CatalogReadFailure(String code,Duration retryAfter) {
    public static Optional<CatalogReadFailure> country(Throwable failure){
        var causes=new ArrayList<Throwable>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(var current=failure;current!=null&&seen.add(current)&&causes.size()<16;current=current.getCause())causes.add(current);
        if(causes.stream().noneMatch(CatalogReadFailure::countryContext))return Optional.empty();
        for(var error:causes)if(error instanceof ZnackApiException api){
            if("EMPTY".equals(api.responseKind()))return found("CATALOG_COUNTRY_RESPONSE_EMPTY",null);
            if("HTML".equals(api.responseKind()))return found("CATALOG_COUNTRY_RESPONSE_HTML",null);
            if("INVALID_JSON".equals(api.responseKind()))return found("CATALOG_COUNTRY_RESPONSE_INVALID_JSON",null);
            return Optional.of(http(api.statusCode(),api.retryAfter()));
        }
        for(var error:causes){
            if(error instanceof java.net.SocketTimeoutException||error instanceof java.net.http.HttpTimeoutException)return found("CATALOG_COUNTRY_TIMEOUT",null);
            if(error instanceof java.net.UnknownHostException)return found("CATALOG_COUNTRY_DNS",null);
            if(error instanceof javax.net.ssl.SSLException)return found("CATALOG_COUNTRY_TLS",null);
            if(error instanceof java.net.ConnectException||error instanceof java.net.SocketException)return found("CATALOG_COUNTRY_CONNECTION_FAILED",null);
            if(error instanceof RateLimitDeferral.Deferred deferred)return found("CATALOG_COUNTRY_RATE_LIMITED",deferred.delay());
        }
        if(causes.stream().anyMatch(error->error.getMessage()!=null&&error.getMessage().contains("operation=COUNTRY_DICTIONARY"))
                &&causes.stream().anyMatch(error->error instanceof java.io.IOException
                &&!countryContext(error)))return found("CATALOG_COUNTRY_CONNECTION_FAILED",null);
        return Optional.empty();
    }
    /** Persisted errors retain controlled operation/status markers even when no exception object remains. */
    public static Optional<CatalogReadFailure> country(String stored){
        if(stored==null||!stored.contains("CATALOG_COUNTRY_"))return Optional.empty();
        for(String code:List.of("CATALOG_COUNTRY_RESPONSE_EMPTY","CATALOG_COUNTRY_RESPONSE_INVALID_JSON","CATALOG_COUNTRY_RESPONSE_HTML","CATALOG_COUNTRY_AUTH_REQUIRED",
                "CATALOG_COUNTRY_RATE_LIMITED","CATALOG_COUNTRY_ENDPOINT_UNAVAILABLE","CATALOG_COUNTRY_SERVICE_UNAVAILABLE",
                "CATALOG_COUNTRY_TIMEOUT","CATALOG_COUNTRY_DNS","CATALOG_COUNTRY_TLS","CATALOG_COUNTRY_CONNECTION_FAILED","CATALOG_COUNTRY_HTTP_ERROR"))
            if(stored.matches("(?s).*\\b"+code+"\\b.*"))return found(code,null);
        var status=java.util.regex.Pattern.compile("\\bCATALOG_COUNTRY_HTTP_(\\d{3})\\b").matcher(stored);
        if(status.find())return Optional.of(http(Integer.parseInt(status.group(1)),null));
        if(stored.contains("SocketTimeoutException")||stored.contains("HttpTimeoutException"))return found("CATALOG_COUNTRY_TIMEOUT",null);
        if(stored.contains("UnknownHostException"))return found("CATALOG_COUNTRY_DNS",null);
        if(stored.contains("SSLException")||stored.contains("SSLHandshakeException"))return found("CATALOG_COUNTRY_TLS",null);
        if(stored.contains("ConnectException")||stored.contains("SocketException"))return found("CATALOG_COUNTRY_CONNECTION_FAILED",null);
        return Optional.empty();
    }
    public String message(){
        String text=I18nService.getInstance().tr("znack.registration.reason."+code);
        if(retryAfter!=null&&!retryAfter.isNegative()&&!retryAfter.isZero())text+="\n"+java.text.MessageFormat.format(
                I18nService.getInstance().tr("znack.registration.country.retry_after"),Math.max(1,retryAfter.toSeconds()));
        return text;
    }
    private static boolean countryContext(Throwable error){
        return error instanceof ZnackApiException api&&"COUNTRY_DICTIONARY".equals(api.operation())
                ||error.getMessage()!=null&&error.getMessage().contains("CATALOG_COUNTRY_");
    }
    private static CatalogReadFailure http(int status,Duration retry){
        return new CatalogReadFailure(switch(status){case 401,403->"CATALOG_COUNTRY_AUTH_REQUIRED";case 404->"CATALOG_COUNTRY_ENDPOINT_UNAVAILABLE";
            case 408->"CATALOG_COUNTRY_TIMEOUT";case 429->"CATALOG_COUNTRY_RATE_LIMITED";default->status>=500?"CATALOG_COUNTRY_SERVICE_UNAVAILABLE":"CATALOG_COUNTRY_HTTP_ERROR";},retry);
    }
    private static Optional<CatalogReadFailure> found(String code,Duration retry){return Optional.of(new CatalogReadFailure(code,retry));}
}
