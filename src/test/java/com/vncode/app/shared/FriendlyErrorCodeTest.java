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
 @Test void sqliteBusyExplainsLocalContentionWithoutExposingDatabasePaths() {
  var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
  try{for(var language:AppLanguage.values()){
   i18n.setLanguage(language);
   var error=new IllegalStateException("SYNC_STORAGE",new java.sql.SQLException("private database path and token", "", 517));
   String text=FriendlyErrorService.format(error);
   assertTrue(text.contains("[SYNC_STORAGE_BUSY]"));assertFalse(text.contains("[VN_LOCAL]"));
   assertFalse(text.contains("private database"));assertFalse(text.contains("error.database_busy"));
   assertEquals("SYNC_STORAGE_BUSY",com.vncode.app.features.sync.SyncFailure.of(error,0).code());
   assertFalse(com.vncode.app.features.sync.SyncFailure.of(error,0).actionRequired());
  }}finally{i18n.setLanguage(previous);}
 }
 @Test void otherStorageErrorsKeepTheirOwnLocalizedCode() {
  String text=FriendlyErrorService.format(new IllegalStateException("SYNC_STORAGE",new java.sql.SQLException("secret schema details","",19)));
  assertTrue(text.contains("[SYNC_STORAGE]"));assertFalse(text.contains("SYNC_STORAGE_BUSY"));
  assertFalse(text.contains("secret schema"));assertFalse(text.contains("error.database_storage"));
 }
 @Test void catalogOwnerFailuresShowSpecificLocalizedAdviceForWrappedErrors() {
  var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
  try{for(var language:AppLanguage.values()){
   i18n.setLanguage(language);
   for(var reason:com.vncode.app.features.gtinsync.RegisteredGtinProof.OwnerFailure.values()) {
    var owner=new com.vncode.app.features.gtinsync.RegisteredGtinProof.UnverifiedOwnerException(reason);
    String text=FriendlyErrorService.format(new RuntimeException("private account token",owner));
    assertTrue(text.contains("[CATALOG_OWNER_"+reason.name()+"]"));assertFalse(text.contains("private account token"));assertFalse(text.contains("error.catalog_owner"));
   }
   assertFalse(FriendlyErrorService.format(new IllegalStateException("unverified_catalog_owner")).contains("unverified_catalog_owner"));
  }}finally{i18n.setLanguage(previous);}
 }
}
