package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogReadCursorRevisionTest {
 @TempDir Path directory;Shop shop;String source;
 @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);source=RegisteredGtinProof.source(shop);CatalogRotationKeys.save(shop,"synthetic-A");}
 @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
 @Test void changedKeyAndAbaCannotResumeOrClearTheOtherEpochsPartialScan()throws Exception{
  var old=new CatalogScanCheckpoint(shop.getId(),source);assertNull(old.load());var state=new CatalogScanCheckpoint.State(1,10,false,List.of(GtinWriteTestFixture.GTIN),0,List.of(),List.of(),1000);old.save(state);
  CatalogRotationKeys.save(shop,"synthetic-A");assertEquals(state,new CatalogScanCheckpoint(shop.getId(),source).load());
  CatalogRotationKeys.save(shop,"synthetic-B");var fresh=new CatalogScanCheckpoint(shop.getId(),source);assertNull(fresh.load());fresh.save(state);old.clear();assertEquals(state,fresh.load());
  CatalogRotationKeys.save(shop,"synthetic-A");assertNull(new CatalogScanCheckpoint(shop.getId(),source).load());
 }
 @Test void changedKeyAndAbaCannotResumeTheOtherEpochsVersionIndex()throws Exception{
  var old=new CatalogVersionCheckpoint(shop.getId(),source);var initial=old.load(true);old.append(initial,2,List.of(new CatalogVersionCheckpoint.Entry("1","etag-1",null,null)));
  CatalogRotationKeys.save(shop,"synthetic-A");assertEquals(1,new CatalogVersionCheckpoint(shop.getId(),source).load(true).offset());
  CatalogRotationKeys.save(shop,"synthetic-B");var fresh=new CatalogVersionCheckpoint(shop.getId(),source);assertEquals(0,fresh.load(true).offset());fresh.append(new CatalogVersionCheckpoint.State(0,null,false),2,List.of(new CatalogVersionCheckpoint.Entry("2","etag-2",null,null)));old.clear();assertEquals(1,fresh.load(true).offset());
  CatalogRotationKeys.save(shop,"synthetic-A");assertEquals(0,new CatalogVersionCheckpoint(shop.getId(),source).load(true).offset());
 }
 @Test void legacyRegisteredGtinJsonDefaultsToTheEmptyReadEpochWithoutChangingPhysicalDetails(){
  var row=new GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()).getFirst();var json=com.vncode.app.features.products.ProductJson.GSON.toJsonTree(row).getAsJsonObject();json.remove("readRevision");var legacy=com.vncode.app.features.products.ProductJson.GSON.fromJson(json,GtinSyncModels.RegisteredGtin.class);
  assertEquals("",legacy.readRevision());assertEquals(row.details(),legacy.details());assertEquals(row.source(),legacy.source());
 }
}
