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

public final class Installed166UpdateProbe {
  public static void main(String[] args) throws Exception {
    Path receipt=Path.of(args[0]);String expectedStatus=args[1];
    if(!BuildConfig.getAppVersion().equals("1.6.6"))throw new AssertionError("Wrong installed binary");
    Database.initDatabase();
    var transport=new OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).followSslRedirects(false);
    String proxyValue=System.getenv("HTTPS_PROXY");if(proxyValue==null)proxyValue=System.getenv("https_proxy");
    if(proxyValue!=null&&!proxyValue.isBlank()){
      var proxy=URI.create(proxyValue);if(proxy.getRawUserInfo()!=null)throw new IllegalStateException("credentialed proxy requires existing authenticated transport");
      transport.proxy(new Proxy(Proxy.Type.HTTP,new InetSocketAddress(proxy.getHost(),proxy.getPort()>0?proxy.getPort():80)));
    }
    transport.addNetworkInterceptor(chain->{if(chain.request().header("Authorization")!=null)throw new AssertionError("Unexpected app authorization header");return chain.proceed(chain.request());});
    var http=transport.build();String platform=System.getProperty("os.name");
    try{
      var api=new UpdateApiClient(http);
      var result=new UpdateService(api,BuildConfig::getAppVersion,ConfigService::getSkippedVersion).check(false);
      var out=new JsonObject();out.addProperty("verifiedAtUtc",Instant.now().toString());out.addProperty("installedBinaryVersion",BuildConfig.getAppVersion());
      out.addProperty("configuredDefaultSource",BuildConfig.getUpdateUrl());out.addProperty("normalizedSource",api.resolveConfiguredSource());
      out.addProperty("manifestPublicKeyPinned",!BuildConfig.getUpdateManifestPublicKey().isBlank());out.addProperty("checkStatus",result.status().name());out.addProperty("errorCode",result.errorCode());
      if(!result.status().name().equals(expectedStatus))throw new AssertionError("Unexpected updater status "+result.status()+" / "+result.errorCode());
      if(result.info()!=null){
        if(!result.info().getVersion().equals("1.6.7"))throw new AssertionError("Wrong latest version");
        System.setProperty("os.name","Windows 11");
        out.addProperty("latestVersion",result.info().getVersion());out.addProperty("selectedDownload",result.info().getBestDownloadUrl());
        out.addProperty("sha256",result.info().getBestDownloadSha256());out.addProperty("size",result.info().getBestDownloadSize());
        if(!result.info().getBestDownloadUrl().endsWith("/v1.6.7/VN-code-1.6.7-Windows-x64.msi"))throw new AssertionError("Wrong Windows installer URL");
      }
      out.addProperty("explicitAuthorizationHeaderInApp",false);out.addProperty("anonymousNetworkAccessIndependentlyProven",false);out.addProperty("sellerPopupObserved",false);
      out.addProperty("scope","Actual published 1.6.6 updater through inherited Cloud proxy/CA; blank local profile; Windows asset selection only after network; proxy may attach repository credentials; no installation, seller API write or license activation");
      Files.writeString(receipt,out.toString()+"\n");System.out.println(out);
    }finally{System.setProperty("os.name",platform);http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();}
  }
}
