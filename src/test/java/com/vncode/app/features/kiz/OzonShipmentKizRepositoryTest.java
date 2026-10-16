package com.vncode.app.features.kiz;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OzonShipmentKizRepositoryTest {
 @TempDir Path root;Shop shop;ShipmentKizRepository repository=new ShipmentKizRepository();
 @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);}
 @AfterEach void done(){System.clearProperty("vncode.appdata.dir");}
 private OzonProductDto product(String id,String type){return new OzonProductDto(id,"offer-"+id,"sku-"+id,"Synthetic","",false,"",List.of()).withCardAttributes("a","","","Same category","").withCategoryIdentity("10",type);}
 @Test void categoriesUseNativePairNotNameAndPersistThroughReadbackAndRename()throws Exception {
  var catalog=new OzonCatalogRepository();catalog.upsertPage(shop.getId(),List.of(product("101","20"),product("102","30")),"");
  assertEquals("20",catalog.findAll(shop.getId()).stream().filter(p->p.productId().equals("101")).findFirst().orElseThrow().typeId());
  var rows=repository.refreshCategories(shop);assertEquals(2,rows.size());assertNotEquals(rows.get(0).id(),rows.get(1).id());assertTrue(rows.stream().allMatch(c->c.ready()==0));
  String id=rows.stream().filter(c->c.name().endsWith("10 / 20")).findFirst().orElseThrow().id();
  catalog.upsertPage(shop.getId(),List.of(product("101","20").withCardAttributes("a","","","Renamed","")),"");
  assertEquals(id,repository.refreshCategories(shop).stream().filter(c->c.name().endsWith("10 / 20")).findFirst().orElseThrow().id());
  var source=new KizSourceSelection(false,true);repository.saveSelection(shop,"OZON:FBS",source);assertEquals(source,repository.selection(shop,"OZON:FBS"));assertEquals(KizSourceSelection.SELLER_ONLY,repository.selection(shop,"another-batch"));
 }
 @Test void importedStockImmediatelyCountsWithoutZnackAndCannotMoveOnRepeatedImport()throws Exception {
  new OzonCatalogRepository().upsertPage(shop.getId(),List.of(product("101","20"),product("102","30")),"");
  var groups=repository.refreshCategories(shop);var file=root.resolve("seller.txt");Files.writeString(file,KizWarehouseTest.mark(1));var preview=KizImportReader.read(file);
  try(var c=Database.getConnection();var st=c.createStatement()){st.execute("DELETE FROM znack_settings");}
  var service=new KizWarehouseService();assertEquals(1,service.importFile(shop,preview,true,groups.get(0).id()).inserted());assertEquals(0,service.importFile(shop,preview,true,groups.get(1).id()).inserted());
  assertEquals(1,repository.refreshCategories(shop).stream().filter(c->c.id().equals(groups.get(0).id())).findFirst().orElseThrow().ready());
  assertEquals(0,repository.refreshCategories(shop).stream().filter(c->c.id().equals(groups.get(1).id())).findFirst().orElseThrow().ready());
 }
 @Test void missingNativeCategoryDoesNotGuessFromNameAndDuplicateSkuCannotSupplyAnImage(){
  new OzonCatalogRepository().upsertPage(shop.getId(),List.of(new OzonProductDto("1","a","same","One","https://img/one",false,"",List.of()),new OzonProductDto("2","b","same","Two","https://img/two",false,"",List.of())),"");
  assertTrue(repository.refreshCategories(shop).isEmpty());assertTrue(new OzonCatalogRepository().findBySkus(shop.getId(),List.of("same")).isEmpty());
 }
}
