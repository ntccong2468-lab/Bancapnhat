package com.vncode.app.shared;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FriendlyErrorCodeTest {
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
