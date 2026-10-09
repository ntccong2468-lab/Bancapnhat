package com.vncode.app.shared;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class FriendlyErrorCodeTest {
 @TempDir java.nio.file.Path directory;
 private String previousDirectory;
 @BeforeEach void isolateData() {
  previousDirectory=System.getProperty("vncode.appdata.dir");
  System.setProperty("vncode.appdata.dir",directory.toString());
 }
 @AfterEach void restoreData() {
  if(previousDirectory==null)System.clearProperty("vncode.appdata.dir");
  else System.setProperty("vncode.appdata.dir",previousDirectory);
 }
 @Test void networkFailuresHaveLocalizedCodeWithoutLeakingRawHostDetails() {
  var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
  try{for(var language:AppLanguage.values()){i18n.setLanguage(language);String text=FriendlyErrorService.format(new java.net.UnknownHostException("private-host.example token=secret-value"));
   assertTrue(text.contains("[VN_NETWORK]"));assertFalse(text.contains("private-host"));assertFalse(text.contains("secret-value"));assertFalse(text.contains("error.network"));}}
  finally{i18n.setLanguage(previous);}
 }
 @Test void wrappedHttpFailuresRetainSafeStatusCode() {
  var error=new RuntimeException("Cannot load supply",new com.vncode.app.integration.wb.WbApiException("upstream",503,"private raw body"));
  String text=FriendlyErrorService.format(error);assertTrue(text.contains("[WB_HTTP_503]"));assertFalse(text.contains("private raw body"));
 }
}
