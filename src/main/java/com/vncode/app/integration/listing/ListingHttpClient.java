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
        b.interceptors().clear();b.networkInterceptors().clear();http=b.build();
    }
    public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException{
        ListingIdentity.require(shop);if(path==null||!path.matches("[a-zA-Z0-9/_?=&%.,:-]{1,2048}"))throw new IllegalArgumentException("Invalid listing endpoint");
        HttpUrl url=origin.resolve(path);if(url==null||!url.host().equals(origin.host()))throw new IOException("Invalid listing origin");
        var b=new Request.Builder().url(url).header("Accept","application/json");
        if(shop.getMarketplace()==Marketplace.OZON)b.header("Client-Id",shop.getClientId()).header("Api-Key",shop.getApiKey());else b.header("Authorization",shop.getApiKey());
        if(body==null)b.get();else b.post(RequestBody.create(MediaType.get("application/json"),body.toString()));
        if(origin.host().equals("content-api.wildberries.ru"))com.vncode.app.integration.wb.WbContentApiRateLimiter.awaitTurn(shop.getApiKey());
        if(origin.host().equals("api-seller.ozon.ru"))try{OZON_LIMITER.awaitTurn(shop.getId(),"catalog");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Listing request cancelled");}
        try(var r=http.newCall(b.build()).execute()){
            if(r.code()==429&&origin.host().equals("content-api.wildberries.ru"))com.vncode.app.integration.wb.WbContentApiRateLimiter.registerRateLimit(shop.getApiKey(),r.headers());
            if(!r.isSuccessful())throw new IOException("Listing API HTTP "+r.code());
            if(r.body()==null||r.body().contentLength()>LIMIT)throw new IOException("Listing response unavailable or too large");
            try(var in=r.body().byteStream()){var bytes=in.readNBytes(LIMIT+1);if(bytes.length>LIMIT)throw new IOException("Listing response too large");return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8));}
        }catch(JsonParseException e){throw new IOException("Invalid listing response");}
    }
}
