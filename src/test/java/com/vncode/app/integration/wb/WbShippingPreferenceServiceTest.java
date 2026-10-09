package com.vncode.app.integration.wb;
import com.vncode.app.config.Database;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class WbShippingPreferenceServiceTest {
 @TempDir Path data;String previous;
 @BeforeEach void setup(){previous=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",data.toString());Database.initDatabase();}
 @AfterEach void cleanup(){if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);}
 @Test void remembersOnlyShippingPreferencesWithinTheSameShop() {
  var service=new WbShippingPreferenceService();var prefs=new WbShippingPreferenceService.Preferences("RU","selfShipping","Москва",100L);
  service.save(1,prefs);assertEquals(prefs,service.load(1));assertNull(service.load(2).country());
  assertFalse(com.vncode.app.shared.ConfigService.getConfigValue("wb.shipping.preferences.1").contains("shippingDt"));
 }
}
