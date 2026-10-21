package com.vncode.app.integration.znack;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class CatalogDeviceConnectionTest {
 @TempDir Path root;Shop shop;
 @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
 @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
 CatalogCredentialRepository store(String device){return new CatalogCredentialRepository(new CatalogCredentialRepository.Protection(){
  public String protect(String key){return device+":"+key;}
  public String reveal(String cipher)throws IOException{if(!cipher.startsWith(device+":"))throw new IOException("Foreign Windows identity");return cipher.substring(device.length()+1);}
 });}
 ZnackModels.Settings settings(){return new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();}
 @Test void freshInstallationDiscoversTheKeyAndSubsequentReadsReuseItsOwnProtectedCopy()throws Exception{
  var store=store("one");var discoveries=new AtomicInteger();
  var connection=new CatalogConnectionService(store,(a,b,c,d,e)->{},(a,b,c,guard)->{guard.run();discoveries.incrementAndGet();return "fixture-owner-key";});
  assertTrue(connection.connectForRead(shop,settings()).hasCatalogKey());assertTrue(connection.connectForRead(shop,settings()).hasCatalogKey());assertEquals(1,discoveries.get());
  assertEquals("fixture-owner-key",store.read(shop,RegisteredGtinProof.current(shop)).orElseThrow());
 }
 @Test void foreignDeviceCiphertextIsReplacedOnlyAfterLocalAuthenticatedDiscovery()throws Exception{
  store("one").saveVerified(shop,RegisteredGtinProof.current(shop),"fixture-device-one-key");
  var second=store("two");assertThrows(IOException.class,()->second.read(shop,RegisteredGtinProof.current(shop)));
  var discoveries=new AtomicInteger();var connection=new CatalogConnectionService(second,(a,b,c,d,e)->{},(a,b,c,guard)->{guard.run();discoveries.incrementAndGet();return "fixture-device-two-key";});
  assertTrue(connection.connectForRead(shop,settings()).hasCatalogKey());assertEquals(1,discoveries.get());assertEquals("fixture-device-two-key",second.read(shop,RegisteredGtinProof.current(shop)).orElseThrow());
 }
 @Test void changingEnterpriseCannotReuseThePreviousKeyOrSilentlyRebindIt()throws Exception{
  var store=store("one");store.saveVerified(shop,RegisteredGtinProof.current(shop),"fixture-key");GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
  var calls=new AtomicInteger();var connection=new CatalogConnectionService(store,(a,b,c,d,e)->{},(a,b,c,g)->{calls.incrementAndGet();return "must-not-save";});
  assertEquals("CATALOG_KEY_SCOPE_CHANGED",assertThrows(IOException.class,()->connection.connectForRead(shop,settings())).getMessage());assertEquals(0,calls.get());
 }
 @Test void failedLocalDiscoveryPreservesTheForeignCiphertextForExplicitRecovery()throws Exception{
  var original=store("one");original.saveVerified(shop,RegisteredGtinProof.current(shop),"fixture-original-key");
  var connection=new CatalogConnectionService(store("two"),(a,b,c,d,e)->{},(a,b,c,g)->{throw new java.net.SocketTimeoutException("Catalog unavailable");});
  assertThrows(java.net.SocketTimeoutException.class,()->connection.connectForRead(shop,settings()));assertEquals("fixture-original-key",original.read(shop,RegisteredGtinProof.current(shop)).orElseThrow());
 }
}
