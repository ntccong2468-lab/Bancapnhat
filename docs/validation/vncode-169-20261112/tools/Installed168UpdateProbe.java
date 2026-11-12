package com.vncode.app.integration.update;

import com.google.gson.JsonObject;
import com.vncode.app.BuildConfig;
import com.vncode.app.config.Database;
import com.vncode.app.shared.ConfigService;
import okhttp3.OkHttpClient;
import java.nio.file.*;
import java.net.*;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.gson.JsonArray;
import com.vncode.app.shared.AppPaths;

public final class Installed168UpdateProbe {
  public static void main(String[] args) throws Exception {
    Path receipt=Path.of(args[0]);String expectedStatus=args[1];String expectedVersion=args[2], expectedSha=args[3]; long expectedSize=Long.parseLong(args[4]);
    if(!BuildConfig.getAppVersion().equals("1.6.8"))throw new AssertionError("Wrong installed binary");
    Path originalJar=Path.of(args[5]).toRealPath();
    for(Class<?> type:new Class<?>[]{BuildConfig.class,Database.class,ConfigService.class,UpdateApiClient.class,UpdateService.class}){
      if(!Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(originalJar))throw new AssertionError("Application class did not load from original published JAR");
    }
    if(AppPaths.isZnackRegistrationTestProfile())throw new AssertionError("Unexpected test update channel");
    Path profile=AppPaths.appDataDir();
    if(Files.exists(profile.resolve("database.db")))throw new AssertionError("Probe requires a fresh blank profile");
    Database.initDatabase();
    if(ConfigService.getSkippedVersion()!=null)throw new AssertionError("Unexpected skipped version");
    var networkReads=new JsonArray();var requests=new AtomicInteger();
    var transport=new OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).followSslRedirects(false);
    String proxyValue=System.getenv("HTTPS_PROXY");if(proxyValue==null)proxyValue=System.getenv("https_proxy");
    if(proxyValue!=null&&!proxyValue.isBlank()){
      var proxy=URI.create(proxyValue);if(proxy.getRawUserInfo()!=null)throw new IllegalStateException("credentialed proxy requires existing authenticated transport");
      transport.proxy(new Proxy(Proxy.Type.HTTP,new InetSocketAddress(proxy.getHost(),proxy.getPort()>0?proxy.getPort():80)));
    }
    transport.addNetworkInterceptor(chain->{
      var request=chain.request();
      if(request.header("Authorization")!=null)throw new AssertionError("Unexpected app authorization header");
      if(!request.method().equals("GET")||!request.url().isHttps()||!request.url().host().equals("api.github.com")||!request.url().encodedPath().equals("/repos/ntccong2468-lab/Bancapnhat/releases/latest"))throw new AssertionError("Unexpected network destination or method");
      var response=chain.proceed(request);
      if(response.handshake()==null)throw new AssertionError("Missing verified TLS handshake");
      var read=new JsonObject();read.addProperty("method",request.method());read.addProperty("url",request.url().toString());read.addProperty("status",response.code());read.addProperty("tlsVersion",response.handshake().tlsVersion().javaName());networkReads.add(read);requests.incrementAndGet();return response;
    });
    var http=transport.build();String platform=System.getProperty("os.name");
    try{
      var api=new UpdateApiClient(http);
      var result=new UpdateService(api,BuildConfig::getAppVersion,ConfigService::getSkippedVersion).check(false);
      var out=new JsonObject();out.addProperty("verifiedAtUtc",Instant.now().toString());out.addProperty("installedBinaryVersion",BuildConfig.getAppVersion());
      out.addProperty("configuredDefaultSource",BuildConfig.getUpdateUrl());out.addProperty("normalizedSource",api.resolveConfiguredSource());
      out.addProperty("manifestPublicKeyPinned",!BuildConfig.getUpdateManifestPublicKey().isBlank());out.addProperty("checkStatus",result.status().name());out.addProperty("errorCode",result.errorCode());
      if(!result.status().name().equals(expectedStatus))throw new AssertionError("Unexpected updater status "+result.status()+" / "+result.errorCode());
      var latest=result.info();
      if(result.status().name().equals("CURRENT"))latest=api.fetchLatestVersion();
      if(latest==null||!latest.getVersion().equals(expectedVersion))throw new AssertionError("Wrong latest version");
      if(requests.get()<1)throw new AssertionError("No actual network read");
      if(latest!=null){
        System.setProperty("os.name","Windows 11");
        out.addProperty("latestVersion",latest.getVersion());out.addProperty("selectedDownload",latest.getBestDownloadUrl());
        out.addProperty("sha256",latest.getBestDownloadSha256());out.addProperty("size",latest.getBestDownloadSize());
        if(!latest.getBestDownloadUrl().endsWith("/v"+expectedVersion+"/VN-code-"+expectedVersion+"-Windows-x64.msi"))throw new AssertionError("Wrong Windows installer URL");
        if(!latest.getBestDownloadSha256().equalsIgnoreCase(expectedSha) || latest.getBestDownloadSize()!=expectedSize)throw new AssertionError("Wrong Windows installer SHA/size");
      }
      System.setProperty("os.name",platform);
      out.addProperty("networkPlatform",platform);out.addProperty("windowsSelectionOnlyAfterNetwork",true);out.addProperty("networkReadCount",requests.get());out.add("networkReads",networkReads);out.addProperty("blankLocalProfile",true);out.addProperty("loadedOriginalAppJar",originalJar.getFileName().toString());out.addProperty("originalAppClassesLoadedFromPublishedJar",true);
      out.addProperty("explicitAuthorizationHeaderInApp",false);out.addProperty("anonymousNetworkAccessIndependentlyProven",false);out.addProperty("sellerPopupObserved",false);
      out.addProperty("scope","Actual published unmodified 1.6.8 updater through inherited Cloud proxy and verified CA; blank local profile; Windows asset selection only after network; proxy may attach repository credentials; no installation, seller API write or license activation");
      Files.writeString(receipt,out.toString()+"\n");System.out.println(out);
    }finally{System.setProperty("os.name",platform);http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();}
  }
}
