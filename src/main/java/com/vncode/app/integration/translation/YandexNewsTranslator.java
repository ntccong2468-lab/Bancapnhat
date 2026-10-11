package com.vncode.app.integration.translation;
import com.google.gson.*;
import okhttp3.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
public final class YandexNewsTranslator implements NewsTranslator {
 public static final URI ENDPOINT=URI.create("https://translate.api.cloud.yandex.net/translate/v2/translate");
 private final OkHttpClient http;private final URI endpoint;private final String folder,key;private volatile boolean closed;private volatile Call activeCall;private String configuration="";
 public YandexNewsTranslator(TranslationSettingsRepository.Settings settings){this(new OkHttpClient(),settings.endpoint(),settings.folderId(),settings.apiKey(),ENDPOINT::equals);configuration=settings.revision();}
 public YandexNewsTranslator(OkHttpClient client,URI endpoint,String folder,String key,Predicate<URI> trusted){if(!trusted.test(endpoint)||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null)throw new IllegalArgumentException("Untrusted translation endpoint");if(key==null||key.isBlank()||key.length()>8192||key.chars().anyMatch(c->c<33||c>126)||folder==null||!folder.matches("[A-Za-z0-9_-]{0,50}"))throw new IllegalArgumentException("Translation credentials unavailable");this.endpoint=endpoint;this.folder=folder;this.key=key;var b=client.newBuilder().connectTimeout(Duration.ofSeconds(8)).readTimeout(Duration.ofSeconds(15)).callTimeout(Duration.ofSeconds(20)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE);b.interceptors().clear();b.networkInterceptors().clear();http=b.build();}
 @Override public String version(){return "yandex-translate-v2/plain/1"+(configuration.isBlank()?"":"/"+configuration);}
 @Override public List<String> translate(List<String> texts,String source,String target)throws IOException {
  if(texts==null||texts.isEmpty()||texts.size()>100||source==null||target==null||!source.matches("[a-z]{2,3}")||!target.matches("[a-z]{2,3}")||texts.stream().anyMatch(s->s==null||s.length()>200000))throw new IOException("Invalid translation text");
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);var result=new ArrayList<String>();
  for(String text:texts){var translated=new StringBuilder();for(int start=0;start<text.length();){if(closed||System.nanoTime()>deadline)throw unavailable();int end=Math.min(text.length(),start+10000);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;String chunk=text.substring(start,end);translated.append(request(chunk,source,target));start=end;}result.add(translated.toString());}return List.copyOf(result);
 }
 private String request(String text,String source,String target)throws IOException {
  var body=new JsonObject();body.addProperty("sourceLanguageCode",source);body.addProperty("targetLanguageCode",target);body.addProperty("format","PLAIN_TEXT");if(!folder.isBlank())body.addProperty("folderId",folder);var texts=new JsonArray();texts.add(text);body.add("texts",texts);
  var request=new Request.Builder().url(endpoint.toString()).header("Authorization","Api-Key "+key).post(RequestBody.create(body.toString(),MediaType.get("application/json; charset=utf-8"))).build();
  var call=http.newCall(request);activeCall=call;if(closed)call.cancel();
  try(var response=call.execute()){if(!response.isSuccessful()||response.body()==null||response.body().contentLength()>1024*1024)throw unavailable();byte[] bytes=response.body().byteStream().readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw unavailable();var json=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();var translations=json.getAsJsonArray("translations");if(translations==null||translations.size()!=1)throw unavailable();String value=translations.get(0).getAsJsonObject().get("text").getAsString();if(value.isBlank()||value.length()>100000)throw unavailable();return value;}catch(IOException|RuntimeException failure){throw unavailable();}finally{if(activeCall==call)activeCall=null;}
 }
 @Override public void close(){closed=true;var call=activeCall;if(call!=null)call.cancel();}
 private static IOException unavailable(){return new IOException("Translation unavailable, quota exceeded or response invalid");}
 @Override public String toString(){return "YandexNewsTranslator[credentials redacted]";}
}
