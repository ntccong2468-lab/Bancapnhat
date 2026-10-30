package com.vncode.app.integration.listing;

import com.google.gson.*;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.marketplace.Marketplace;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Fixed seller API origin; bounded bodies and generic errors contain no private upstream data. */
public final class ListingHttpClient implements ListingTransport {
    private static final int LIMIT=20*1024*1024;
    private static final OkHttpClient SHARED_HTTP=new OkHttpClient();
    private static final com.vncode.app.integration.ozon.OzonApiRateLimiter OZON_LIMITER=new com.vncode.app.integration.ozon.OzonApiRateLimiter();
    private final Shop shop;private final HttpUrl origin;private final OkHttpClient http;
    public ListingHttpClient(Shop s){this(s,SHARED_HTTP,Objects.requireNonNull(HttpUrl.parse(s.getMarketplace()==Marketplace.OZON?"https://api-seller.ozon.ru/":"https://content-api.wildberries.ru/")));}
    public ListingHttpClient(Shop s,OkHttpClient client,HttpUrl origin){
        shop=new Shop(s.getId(),s.getName(),s.getMarketplace(),s.getClientId(),s.getApiKey());this.origin=origin;
        var b=client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS);
        b.interceptors().clear();b.networkInterceptors().clear();
        b.addNetworkInterceptor(chain->{
            NetworkAttempt attempt=chain.call().request().tag(NetworkAttempt.class);
            if(attempt!=null&&!attempt.sent.compareAndSet(false,true))throw new IOException("Automatic mutation replay is blocked; read back the result");
            return chain.proceed(chain.request());
        });http=b.build();
    }
    public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException{return call(path,body,mutation,()->{});}
    public JsonElement call(String path,JsonElement body,boolean mutation,Runnable beforeSend)throws IOException{
        return call(path,body,mutation,()->{},beforeSend);
    }
    @Override public JsonElement call(String path,JsonElement body,boolean mutation,ListingTransport.CheckedGuard freshRead,Runnable beforeSend)throws IOException{
        return call(path,body,mutation,freshRead,beforeSend,0);
    }
    private JsonElement call(String path,JsonElement body,boolean mutation,ListingTransport.CheckedGuard freshRead,Runnable beforeSend,int retries)throws IOException{
        ListingIdentity.require(shop);if(path==null||!path.matches("[a-zA-Z0-9/_?=&%.,:-]{1,2048}"))throw new IllegalArgumentException("Invalid listing endpoint");
        HttpUrl url=origin.resolve(path);if(url==null||!url.host().equals(origin.host()))throw new IOException("Invalid listing origin");
        var b=new Request.Builder().url(url).header("Accept","application/json");
        if(mutation)b.tag(NetworkAttempt.class,new NetworkAttempt());
        if(shop.getMarketplace()==Marketplace.OZON)b.header("Client-Id",shop.getClientId()).header("Api-Key",shop.getApiKey());else b.header("Authorization",shop.getApiKey());
        if(body==null)b.get();else b.post(RequestBody.create(MediaType.get("application/json"),body.toString()));
        if(origin.host().equals("content-api.wildberries.ru"))com.vncode.app.integration.wb.WbContentApiRateLimiter.awaitTurn(shop.getApiKey(),path);
        if(origin.host().equals("api-seller.ozon.ru"))try{OZON_LIMITER.awaitTurn(ListingIdentity.hash(shop.getClientId()),"catalog");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Listing request cancelled");}
        // A guard may re-read a marketplace card. Never hold the background/FBS HTTP permit
        // while it does so; a nested background acquire would otherwise deadlock.
        ListingIdentity.require(shop);freshRead.check();
        // The guard may itself read the prices API. Reserve this request only
        // after that read, so nested requests cannot violate the shared quota.
        if(origin.host().equals("discounts-prices-api.wildberries.ru"))com.vncode.app.integration.wb.WbPriceApiRateLimiter.awaitTurn(shop.getApiKey());
        try(var slot=com.vncode.app.shared.MarketplaceRequestBudget.acquire()){
            ListingIdentity.require(shop);beforeSend.run();
            try(var r=http.newCall(b.build()).execute()){
            if(r.code()==429){
                var rejected=new ListingRateLimitException(r.headers());
                if(origin.host().equals("content-api.wildberries.ru"))com.vncode.app.integration.wb.WbContentApiRateLimiter.registerRateLimit(shop.getApiKey(),path,r.headers());
                if(origin.host().equals("discounts-prices-api.wildberries.ru"))com.vncode.app.integration.wb.WbPriceApiRateLimiter.registerRateLimit(shop.getApiKey(),r.headers());
                if(origin.host().equals("api-seller.ozon.ru"))OZON_LIMITER.registerRateLimit(ListingIdentity.hash(shop.getClientId()),"catalog",rejected.retryAfter());
                throw rejected;
            }
            if(!r.isSuccessful()){
                if(r.code()>=400&&r.code()<500&&r.code()!=408&&r.code()!=425){
                    String reason="";
                    try{
                        var detail=JsonParser.parseString(r.peekBody(8192).string());
                        if(detail.isJsonObject())for(String field:new String[]{"errorText","message","detail"}){
                            var value=detail.getAsJsonObject().get(field);
                            if(value!=null&&value.isJsonPrimitive()){reason=ListingRejectedException.safe(shop,value.getAsString());if(!reason.isBlank())break;}
                        }
                    }catch(IOException|RuntimeException unavailable){/* Status is still an explicit rejection. */}
                    throw new ListingRejectedException(shop.getMarketplace(),r.code(),"Listing API HTTP "+r.code()+(reason.isBlank()?"":": "+reason));
                }
                throw new IOException("Listing API HTTP "+r.code());
            }
            if(r.body()==null||r.body().contentLength()>LIMIT)throw new IOException("Listing response unavailable or too large");
            try(var in=r.body().byteStream()){var bytes=in.readNBytes(LIMIT+1);if(bytes.length>LIMIT)throw new IOException("Listing response too large");return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8));}
            }
        }catch(ListingRateLimitException limited){
            // Read-only WB calls can safely wait for the shared endpoint cooldown
            // and retry. A mutation is handled by its durable caller, which must
            // distinguish an explicit 429 rejection from an uncertain delivery.
            boolean wb=origin.host().equals("content-api.wildberries.ru")||origin.host().equals("discounts-prices-api.wildberries.ru");
            if(!mutation&&wb&&retries<2)return call(path,body,false,freshRead,beforeSend,retries+1);
            throw limited;
        }catch(JsonParseException e){throw new IOException("Invalid listing response");}
    }
    private static final class NetworkAttempt{private final java.util.concurrent.atomic.AtomicBoolean sent=new java.util.concurrent.atomic.AtomicBoolean();}
}
