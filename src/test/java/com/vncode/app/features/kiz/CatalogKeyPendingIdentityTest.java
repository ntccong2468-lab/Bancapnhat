package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.CatalogRotationKeys;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class CatalogKeyPendingIdentityTest {
 @TempDir Path directory;
 @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();}
 @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
 @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
 void acceptedPendingIdentityAndFeedOwnershipSurviveKeyRotationWhileNewPaidProofWaits(Marketplace market)throws Exception{
  var shop=PendingKizFixture.create(market);String id=market==Marketplace.WILDBERRIES?"101":"9001",source=RegisteredGtinProof.source(shop);
  var product=new KizPurchaseMappingProof.Product(id,"S-M",PendingKizFixture.GTIN);
  var before=KizPurchaseMappingProof.resolve(shop,List.of(product));assertEquals(PendingKizFixture.GTIN,before.get(product));
  var identity=new KizRegistrationIdentityRepository().readForPurchase(shop).select(id,"S-M");assertNotNull(identity);
  CatalogRotationKeys.save(shop,"synthetic-A");CatalogRotationKeys.save(shop,"synthetic-B");CatalogRotationKeys.save(shop,"synthetic-A");
  assertEquals(source,RegisteredGtinProof.source(shop));assertEquals(before,KizPurchaseMappingProof.resolve(shop,List.of(product)));
  assertEquals(identity,new KizRegistrationIdentityRepository().readForPurchase(shop).select(id,"S-M"));
  assertEquals("SOURCE_SYNC_REQUIRED",assertThrows(IllegalStateException.class,()->KizPurchaseMappingProof.require(shop,List.of(product),Set.of(PendingKizFixture.GTIN))).getMessage());
  String table=market==Marketplace.WILDBERRIES?"znack_card_registrations":"ozon_card_registrations";
  try(var c=Database.getConnection();var sql=c.createStatement();var row=sql.executeQuery("SELECT status,feed_id,gtin FROM "+table)){assertTrue(row.next());assertEquals("PROCESSING",row.getString(1));assertEquals("accepted-feed",row.getString(2));assertEquals(PendingKizFixture.GTIN,row.getString(3));}
 }
}
