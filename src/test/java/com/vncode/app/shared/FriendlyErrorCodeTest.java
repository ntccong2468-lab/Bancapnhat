package com.vncode.app.shared;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class FriendlyErrorCodeTest {
 @TempDir java.nio.file.Path directory;
 private String previousDirectory;
 @BeforeEach void isolateData() throws Exception {
  previousDirectory=System.getProperty("vncode.appdata.dir");
  System.setProperty("vncode.appdata.dir",directory.toString());
  com.vncode.app.config.Database.initDatabase();
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
 @Test void kizMappingErrorsKeepOrderContextAndShowTheCodeOnceWithoutEmptyRows(){
  var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
  try{for(var language:AppLanguage.values()){
   i18n.setLanguage(language);
   String code="KIZ_GTIN_MAPPING_MISSING";
   String detail=FriendlyErrorService.format(new com.vncode.app.features.kiz.KizAssignmentException(code,"ORDER-1","101-SKU-M"));
   assertTrue(detail.contains("ORDER-1"));assertTrue(detail.contains("101-SKU-M"));assertEquals(1,detail.split("\\["+code+"\\]",-1).length-1);
   String empty=FriendlyErrorService.format(new com.vncode.app.features.kiz.KizAssignmentException(code,"",""));
   assertEquals(com.vncode.app.features.kiz.KizErrorMessages.message(code),empty);
  }}finally{i18n.setLanguage(previous);}
 }
 @Test void wrappedHttpFailuresRetainSafeStatusCode() {
  var error=new RuntimeException("Cannot load supply",new com.vncode.app.integration.wb.WbApiException("upstream",503,"private raw body"));
  String text=FriendlyErrorService.format(error);assertTrue(text.contains("[WB_HTTP_503]"));assertFalse(text.contains("private raw body"));
 }
 @Test void wrappedCryptoProFailureShowsItsSpecificAdviceAndDurableActionCode() {
  var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
  try{for(var language:AppLanguage.values()){
   i18n.setLanguage(language);
   var crypto=new com.vncode.app.integration.znack.signature.CryptoProException(
       com.vncode.app.integration.znack.signature.CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE,"private diagnostic token=secret-value");
   var wrapped=new java.io.IOException("Catalog certificate signing failed",crypto);
   String text=FriendlyErrorService.format(wrapped);
   assertTrue(text.contains(i18n.tr("znack.signature.error.private_key")));
   assertTrue(text.contains("[CRYPTOPRO_PRIVATE_KEY_UNAVAILABLE]"));
   assertFalse(text.contains("secret-value"));assertFalse(text.contains("private diagnostic"));
   var failure=com.vncode.app.features.sync.SyncFailure.of(wrapped,0);
   assertEquals("CRYPTOPRO_PRIVATE_KEY_UNAVAILABLE",failure.code());assertTrue(failure.actionRequired());
  }}finally{i18n.setLanguage(previous);}
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

 @Test void missingOrAmbiguousPurchaseBuffersKeepDistinctLocalizedCheckingAdvice() {
  var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
  try{for(var language:AppLanguage.values()){
   i18n.setLanguage(language);
   var messages=new java.util.HashSet<String>();
   for(var issue:purchaseBufferIssues()){
    String expected=i18n.tr("znack.error.order_buffer."+issue.key());
    assertFalse(expected.startsWith("znack.error."));
    String actual=com.vncode.app.integration.znack.ZnackErrorMessages.displayForPipeline("POLLING_ORDER",issue.code());
    assertEquals(expected,actual);assertFalse(actual.contains(issue.code()));
    assertEquals(actual,com.vncode.app.integration.znack.ZnackErrorMessages.display(issue.legacy()));
    messages.add(actual);
   }
   assertEquals(3,messages.size(),"Missing buffer, ambiguous buffer and missing status need distinct reasons");
  }}finally{i18n.setLanguage(previous);}
 }

 @Test void wrappedPurchaseBufferWaitsRetainCodesWithoutLeakingUnrelatedWrapperDetails() {
  var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
  try{for(var language:AppLanguage.values()){
   i18n.setLanguage(language);
   for(var issue:purchaseBufferIssues())for(String raw:java.util.List.of(issue.code(),issue.legacy())){
    var error=new java.util.concurrent.CompletionException("private account token=secret-value",
        new java.io.IOException("private transport body",new java.io.IOException(raw)));
    String actual=FriendlyErrorService.format(error);
    assertEquals(i18n.tr("znack.error.order_buffer."+issue.key())+"\n["+issue.code()+"]",actual);
    assertFalse(actual.contains("secret-value"));assertFalse(actual.contains("private transport"));
    assertFalse(actual.contains("[VN_LOCAL]"));
   }
  }}finally{i18n.setLanguage(previous);}
 }

 @Test void unrelatedSuzErrorsAreNotReclassifiedAsIncompletePurchaseBuffers() {
  String rejection="SUZ rejected the purchased GTIN for a different validation reason.";
  assertEquals("",com.vncode.app.integration.znack.ZnackErrorMessages.kizOrderBufferIssueCode(rejection));
  assertEquals(rejection,com.vncode.app.integration.znack.ZnackErrorMessages.display(rejection));
  assertEquals("",com.vncode.app.integration.znack.ZnackErrorMessages.kizOrderBufferIssueCode("SUZ_KIZ_BUFFER_MISSING_EXTRA"));
  assertEquals("",com.vncode.app.integration.znack.ZnackErrorMessages.kizOrderBufferIssueCode(null));
 }

 @Test void malformedSuzOrderReconciliationHasLocalizedAdviceAndRetainsItsTechnicalCause() {
  var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
  var cause=new java.io.IOException("SUZ_ORDER_RECONCILIATION_INVALID");
  var wrapped=new java.util.concurrent.CompletionException("private account token=secret-value",cause);
  try{for(var language:AppLanguage.values()){
   i18n.setLanguage(language);String key="znack.error.suz_order_reconciliation_invalid",expected=i18n.tr(key);
   assertNotEquals(key,expected);assertFalse(expected.isBlank());
   assertEquals(expected+"\n[SUZ_ORDER_RECONCILIATION_INVALID]",FriendlyErrorService.format(wrapped));
   assertSame(cause,wrapped.getCause());assertEquals("SUZ_ORDER_RECONCILIATION_INVALID",cause.getMessage());
  }}finally{i18n.setLanguage(previous);}
 }

 @Test void catalogModelReadFailuresHaveLocalizedAdviceAndKeepTheirCodesThroughWrappers() {
  var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
  try{for(var language:AppLanguage.values()){
   i18n.setLanguage(language);
   for(String code:java.util.List.of("CATALOG_REGISTRATION_SCHEMA_UNAVAILABLE","CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE","CATALOG_COUNTRY_DICTIONARY_INVALID")){
    String key="znack.registration.reason."+code,expected=i18n.tr(key);
    assertNotEquals(key,expected);assertFalse(expected.isBlank());
    String actual=FriendlyErrorService.format(new java.io.IOException("private account token=secret-value",new IllegalStateException(code)));
    assertEquals(expected+"\n["+code+"]",actual);
    assertFalse(actual.contains("secret-value"));assertFalse(actual.contains("[VN_LOCAL]"));
   }
  }}finally{i18n.setLanguage(previous);}
 }

 private static java.util.List<PurchaseBufferIssue> purchaseBufferIssues(){
  return java.util.List.of(
      new PurchaseBufferIssue(com.vncode.app.integration.znack.ZnackKizOrderService.BUFFER_MULTIPLE,"multiple",
          "SUZ returned several buffers for the purchased GTIN; keep checking the existing order."),
      new PurchaseBufferIssue(com.vncode.app.integration.znack.ZnackKizOrderService.BUFFER_MISSING,"missing",
          "SUZ did not return the purchased GTIN buffer; keep checking the existing order."),
      new PurchaseBufferIssue(com.vncode.app.integration.znack.ZnackKizOrderService.BUFFER_STATUS_MISSING,"status_missing",
          "SUZ did not return the purchased GTIN buffer status; keep checking the existing order."));
 }
 private record PurchaseBufferIssue(String code,String key,String legacy){}
}
