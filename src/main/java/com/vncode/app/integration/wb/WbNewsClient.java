package com.vncode.app.integration.wb;
import com.vncode.app.models.Shop;
import com.vncode.app.features.news.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.google.gson.*;
import okhttp3.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
/** Official 01-general.yaml getV2News contract, verified 2026-10-11. */
public final class WbNewsClient implements OfficialNewsService.Reader {
 public record NewsBatch(List<SellerNews> items,String nextFromId){public NewsBatch{items=List.copyOf(items);}}
 public static final class RateLimitedException extends IOException {private final Duration retryAfter;public RateLimitedException(Duration delay){super("WB news rate limit");retryAfter=delay.compareTo(Duration.ofHours(24))>0?Duration.ofHours(24):delay.isNegative()?Duration.ZERO:delay;}public Duration retryAfter(){return retryAfter;}}
 private final OkHttpClient http;private final URI root;
 public WbNewsClient(){this(new OkHttpClient(),URI.create("https://common-api.wildberries.ru/"));}
 public WbNewsClient(OkHttpClient client,URI root){if(root.getUserInfo()!=null||root.getQuery()!=null||root.getFragment()!=null||!("https".equals(root.getScheme())&&"common-api.wildberries.ru".equals(root.getHost())&&(root.getPort()==-1||root.getPort()==443)||"http".equals(root.getScheme())&&Set.of("localhost","127.0.0.1").contains(root.getHost())))throw new IllegalArgumentException("Untrusted WB news origin");this.root=root;var b=client.newBuilder().connectTimeout(Duration.ofSeconds(8)).readTimeout(Duration.ofSeconds(15)).callTimeout(Duration.ofSeconds(20)).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE);b.interceptors().clear();b.networkInterceptors().clear();http=b.build();}
 /** The current official spec gives base tokens one request/hour. No token type is inferred. */
 @Override public Duration minimumInterval(){return Duration.ofHours(1);}
 @Override public NewsBatch readSince(Shop shop,Instant from,String fromId)throws IOException {
  if(shop==null||shop.getId()<=0||shop.getMarketplace()!=Marketplace.WILDBERRIES)throw new SecurityException("WB news requires WB owner");
  String token=shop.getApiKey();if(token==null||token.isBlank()||token.length()>16384||token.chars().anyMatch(c->c<33||c>126))throw new IOException("WB news credential unavailable");
  if(fromId!=null&&!fromId.isBlank()&&!fromId.matches("[0-9]{1,20}"))throw new IOException("WB news cursor invalid");
  var endpoint=Objects.requireNonNull(HttpUrl.parse(root.resolve("/api/communications/v2/news").toString())).newBuilder();
  if(fromId==null||fromId.isBlank())endpoint.addQueryParameter("from",Objects.requireNonNull(from).atZone(ZoneId.of("Europe/Moscow")).toLocalDate().toString());else endpoint.addQueryParameter("fromID",fromId);
  var request=new Request.Builder().url(endpoint.build()).header("Authorization",token).header("Accept","application/json").get().build();
  try(var response=http.newCall(request).execute()) {
   if(response.code()==429){long delay=3600;try{delay=Long.parseLong(Objects.toString(response.header("Retry-After"),"3600"));}catch(NumberFormatException ignored){}throw new RateLimitedException(Duration.ofSeconds(Math.max(60,Math.min(86400,delay))));}
   if(!response.isSuccessful()||response.body()==null||response.body().contentLength()>2*1024*1024)throw new IOException("WB news unavailable");
   byte[] data=response.body().byteStream().readNBytes(2*1024*1024+1);if(data.length>2*1024*1024)throw new IOException("WB news response exceeds limit");
   var json=JsonParser.parseString(new String(data,StandardCharsets.UTF_8)).getAsJsonObject();var entries=json.getAsJsonArray("data");if(entries==null||entries.size()>100)throw new IOException("WB news response invalid");
   var scope=NewsScope.forShop(shop);var items=new ArrayList<SellerNews>();java.math.BigInteger highest=java.math.BigInteger.ZERO;
   for(var entry:entries){var item=entry.getAsJsonObject();String id=item.get("id").getAsString();var date=OffsetDateTime.parse(item.get("date").getAsString()).toInstant();items.add(SellerNews.wb(scope,id,date,plain(item.get("header").getAsString()),plain(item.get("content").getAsString()),URI.create("https://seller.wildberries.ru/")));highest=highest.max(new java.math.BigInteger(id));}
   return new NewsBatch(items,items.isEmpty()?"":highest.toString());
  }catch(RateLimitedException rate){throw rate;}catch(IOException|RuntimeException error){throw new IOException("WB news unavailable or response invalid");}
 }
 private static String plain(String value){return value.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>","").replaceAll("(?i)<(?:br\\s*/?|/p|/div)>","\n").replaceAll("<[^>]+>","").replace("&nbsp;"," ").replace("&amp;","&").replace("&quot;","\"").replace("&lt;","<").replace("&gt;",">").strip();}
}
