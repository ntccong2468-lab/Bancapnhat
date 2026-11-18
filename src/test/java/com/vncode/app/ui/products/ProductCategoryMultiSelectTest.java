package com.vncode.app.ui.products;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.ShopReadCoordinator;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real local WB/Ozon product queries and the real five action callbacks; no seller requests. */
class ProductCategoryMultiSelectTest {
    @TempDir Path directory;
    private ShopReadCoordinator reads;
    private ProductsPane pane;
    private Stage stage;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var connection=Database.getConnection();var sql=connection.createStatement()){
            sql.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'WB fixture','WILDBERRIES',NULL,'fixture-wb'),(2,'Ozon fixture','OZON','fixture-client','fixture-ozon')");
            try(var insert=connection.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,subject_name,synced_at) VALUES(1,?,?,'fixture',?,'now')")){
                for(int index=1;index<=125;index++){insert.setInt(1,index);insert.setString(2,"Product "+index);insert.setString(3,index<=60?"Shirts":index<=120?"Suits":"Hats");insert.addBatch();}insert.executeBatch();
            }
            try(var insert=connection.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,category,archived,synced_at) VALUES(2,?,?,?,? ,?,0,'now')")){
                for(int index=1;index<=50;index++){insert.setString(1,"O"+index);insert.setString(2,"offer-"+index);insert.setString(3,"sku-"+index);insert.setString(4,"Ozon Product "+index);insert.setString(5,index<=25?"Coats":"Vests");insert.addBatch();}insert.executeBatch();
            }
        }
        reads=new ShopReadCoordinator(2,32);var repository=new ProductRepository();
        pane=fx(()->new ProductsPane(repository::loadProductPage,reads));
        stage=fx(()->{var window=new Stage();window.setScene(new Scene(pane,1100,700));window.show();return window;});
    }
    @AfterEach void cleanup()throws Exception{
        if(pane!=null)fx(()->{pane.dispose();stage.close();return null;});
        com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait(reads);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }
    @Test void twoCategoriesReachAllPagesAndEveryWbBulkActionThenResetForOzon()throws Exception{
        open(wb());
        fx(()->{tick("Suits");tick("Shirts");return null;});await(()->idle()&&lastQuery().categoryIds().size()==2&&rows().getItems().size()==100);
        fx(()->{assertEquals(List.of("Shirts","Suits"),lastQuery().categoryIds());return null;});
        fx(()->{button("productMore").fire();return null;});await(()->idle()&&rows().getItems().size()==120);
        fx(()->{assertEquals(120,rows().getItems().stream().map(ProductRow::key).distinct().count());return null;});
        verifyAllFiveActions(120,1,Marketplace.WILDBERRIES);
        open(ozon());
        fx(()->{assertTrue(pane.selectedKeys().isEmpty());assertEquals(Set.of("Coats","Vests"),categoryNames());tick("Vests");tick("Coats");return null;});
        await(()->idle()&&lastQuery().shopId()==2&&lastQuery().categoryIds().size()==2);
        verifyAllFiveActions(50,2,Marketplace.OZON);
    }
    @Test void changingSearchOrClearingCategoriesCannotKeepThePreviousBulkSelection()throws Exception{
        open(wb());fx(()->{tick("Suits");tick("Shirts");return null;});await(()->idle()&&lastQuery().categoryIds().size()==2);
        fx(()->{((CheckBox)pane.lookup("#productSelectAll")).fire();return null;});await(()->pane.selectedKeys().size()==120);
        fx(()->{((TextField)pane.lookup("#productSearch")).setText("Product");assertTrue(pane.selectedKeys().isEmpty());assertTrue(((CheckBox)pane.lookup("#productSelectAll")).isDisabled());return null;});
        await(()->idle()&&lastQuery().text().equals("Product"));
        fx(()->{assertEquals(List.of("Shirts","Suits"),lastQuery().categoryIds());((CheckBox)pane.lookup("#productSelectAll")).fire();return null;});await(()->pane.selectedKeys().size()==120);
        fx(()->{menu().getItems().getFirst().fire();assertTrue(pane.selectedKeys().isEmpty());for(String field:List.of("brand","material","gender","tnved","documents"))assertTrue(button("productEdit_"+field).isDisabled());return null;});
        await(()->idle()&&lastQuery().categoryIds().isEmpty());
        fx(()->{assertEquals("Product",lastQuery().text());assertEquals(100,rows().getItems().size());return null;});
    }
    @Test void refreshedCategoryRemovalClearsTheOldSelectionAndLoadsTheNewFilter()throws Exception{
        open(wb());fx(()->{tick("Suits");return null;});await(()->idle()&&lastQuery().categoryIds().equals(List.of("Suits")));
        fx(()->{((CheckBox)pane.lookup("#productSelectAll")).fire();return null;});await(()->pane.selectedKeys().size()==60);
        try(var connection=Database.getConnection();var statement=connection.createStatement()){statement.execute("DELETE FROM wb_product_cards WHERE shop_id=1 AND subject_name='Suits'");statement.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,subject_name,synced_at) VALUES(1,500,'New product','new','Jackets','now')");}
        fx(()->{pane.refresh();return null;});await(()->idle()&&categoryNames().contains("Jackets")&&!categoryNames().contains("Suits")&&lastQuery().categoryIds().isEmpty());
        fx(()->{assertTrue(pane.selectedKeys().isEmpty());assertEquals(66,rows().getItems().size());for(String field:List.of("brand","material","gender","tnved","documents"))assertTrue(button("productEdit_"+field).isDisabled());return null;});
    }
    @Test void exactCategoryValuesDoNotLoseProductsWhenNamesDifferOnlyByCaseOrWhitespace()throws Exception{
        try(var connection=Database.getConnection();var statement=connection.createStatement()){statement.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,subject_name,synced_at) VALUES(1,501,'Case product','case','SUITS','now'),(1,502,'Space product','space',' Suits ','now')");}
        open(wb());fx(()->{assertTrue(categoryNames().containsAll(Set.of("Suits","SUITS"," Suits ")));tick("SUITS");return null;});await(()->idle()&&lastQuery().categoryIds().equals(List.of("SUITS")));
        fx(()->{assertEquals(List.of("501"),rows().getItems().stream().map(row->row.key().cardId()).toList());tick(" Suits ");return null;});await(()->idle()&&lastQuery().categoryIds().size()==2);
        fx(()->{assertEquals(Set.of("501","502"),rows().getItems().stream().map(row->row.key().cardId()).collect(java.util.stream.Collectors.toSet()));assertEquals(List.of(" Suits ","SUITS"),lastQuery().categoryIds());return null;});
    }
    private void verifyAllFiveActions(int count,int shop,Marketplace marketplace)throws Exception{
        var actions=new ArrayList<String>();fx(()->{pane.setOnEdit((field,keys)->{assertEquals(count,keys.size());assertTrue(keys.stream().allMatch(key->key.shopId()==shop&&key.marketplace()==marketplace));actions.add(field);});((CheckBox)pane.lookup("#productSelectAll")).fire();return null;});await(()->pane.selectedKeys().size()==count);
        fx(()->{for(String field:List.of("brand","material","gender","tnved","documents")){assertFalse(button("productEdit_"+field).isDisabled());button("productEdit_"+field).fire();}assertEquals(List.of("brand","material","gender","tnved","documents"),actions);return null;});
    }
    private void open(Shop shop)throws Exception{fx(()->{pane.setShop(shop);return null;});await(()->idle()&&!menu().isDisabled()&&lastQuery().shopId()==shop.getId());}
    private boolean idle(){return !button("productSync").isDisabled();}
    private ProductQuery lastQuery(){try{var field=ProductsPane.class.getDeclaredField("displayedQuery");field.setAccessible(true);return (ProductQuery)field.get(pane);}catch(Exception error){throw new AssertionError(error);}}
    private Button button(String id){if(id.equals("productMore")){try{var field=ProductsPane.class.getDeclaredField("more");field.setAccessible(true);return (Button)field.get(pane);}catch(Exception error){throw new AssertionError(error);}}return (Button)pane.lookup("#"+id);}
    private MenuButton menu(){return assertInstanceOf(MenuButton.class,pane.lookup("#productCategoryFilter"),"Products must allow more than one category to remain selected");}
    private Set<String> categoryNames(){var values=new HashSet<String>();for(var item:menu().getItems())if(item instanceof CustomMenuItem custom&&custom.getContent() instanceof CheckBox check)values.add(check.getText());return values;}
    private void tick(String name){for(var item:menu().getItems())if(item instanceof CustomMenuItem custom&&custom.getContent() instanceof CheckBox check&&check.getText().equals(name)){check.fire();return;}fail("Missing category "+name);}
    @SuppressWarnings("unchecked")private ListView<ProductRow> rows(){return (ListView<ProductRow>)pane.lookup("#productRows");}
    private static Shop wb(){return new Shop(1,"WB fixture",Marketplace.WILDBERRIES,null,"fixture-wb");}
    private static Shop ozon(){return new Shop(2,"Ozon fixture",Marketplace.OZON,"fixture-client","fixture-ozon");}
    private static void await(Callable<Boolean> condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(fx(condition))return;Thread.sleep(10);}assertTrue(fx(condition));}
    private static <T>T fx(Callable<T> work)throws Exception{var future=new FutureTask<>(work);Platform.runLater(future);try{return future.get(15,TimeUnit.SECONDS);}catch(ExecutionException failed){if(failed.getCause() instanceof Error cause)throw cause;if(failed.getCause() instanceof Exception cause)throw cause;throw failed;}}
}
