package com.vncode.app.ui.ozon;
import com.vncode.app.config.Database;
import com.vncode.app.features.ozon.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class OzonFbsEnrichmentUiTest {
 @TempDir Path dir;
 @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
 static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<T>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
 @Test void productAttributeChangeRedrawsOnlyItsPostingEvenWhenOrderDataIsUnchanged()throws Exception{
  System.setProperty("vncode.appdata.dir",dir.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic','OZON','11','fixture')");}
  var product=new AtomicReference<>(new OzonProductDto("101","offer-a","1001","Synthetic set","",false,"",List.of()).withCardAttributes("a","GREY","XL","Sets",""));
  var window=new javafx.stage.Stage[1];
  var loader=fx(()->{var l=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");var scene=new Scene(l.load(),1000,650);window[0]=new javafx.stage.Stage();window[0].setScene(scene);window[0].show();return l;});OzonDashboardController controller=loader.getController();
  TableView<OzonPostingDto> table=(TableView<OzonPostingDto>)loader.getNamespace().get("newOrdersTable");
  try{
   fx(()->{controller.setFbsReader(q->new OzonFbsPage(List.of(new OzonPostingDto("P-1","","",q.status(),"","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,"1001","1001","offer-a","Synthetic set",1,"RUB","100")))),1,Map.of("1001",product.get())));controller.setShop(new Shop(1,"Synthetic",Marketplace.OZON,"11","fixture"),false);return null;});
   for(int i=0;i<500&&!fx(()->table.getItems().size()==1);i++)Thread.sleep(10);assertEquals(1,fx(()->table.getItems().size()));
   var replaced=new CompletableFuture<Void>();fx(()->{table.getItems().addListener((javafx.collections.ListChangeListener<OzonPostingDto>)c->replaced.complete(null));return null;});
   product.set(product.get().withCardAttributes("a","BLUE","XL","Sets",""));fx(()->{controller.refreshLocalData();return null;});replaced.get(5,TimeUnit.SECONDS);
   assertEquals("P-1",fx(()->table.getItems().getFirst().postingNumber()));
   var rendered=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==3){stop();rendered.complete(null);}}}.start();return null;});rendered.get(5,TimeUnit.SECONDS);
   assertTrue(fx(()->table.lookupAll(".label").stream().anyMatch(node->node instanceof Label label&&label.getText().contains("BLUE"))),"Updated catalog color must reach the rendered posting cell");
  }finally{fx(()->{controller.dispose();window[0].close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
 }
 @Test void unchangedPageRefreshDoesNotDiscardItsPendingThumbnail()throws Exception{
  System.setProperty("vncode.appdata.dir",dir.toString());Database.initDatabase();try(var c=Database.getConnection();var st=c.createStatement()){st.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic','OZON','11','fixture')");}
  var image=new CompletableFuture<byte[]>();var requested=new AtomicInteger();var reads=new AtomicInteger();
  var loader=fx(()->{var l=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");l.load();return l;});OzonDashboardController controller=loader.getController();
  var stage=fx(()->{controller.setImageService(new com.vncode.app.features.fbo.FboProductImageService(){@Override public CompletableFuture<byte[]> loadThumbnailBytes(String url){requested.incrementAndGet();return image;}});
   controller.setFbsReader(q->{reads.incrementAndGet();return new OzonFbsPage(List.of(new OzonPostingDto("P-1","","",q.status(),"","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,"1001","1001","offer-a","Synthetic",1,"RUB","100")))),1,Map.of("1001",new OzonProductDto("101","offer-a","1001","Synthetic","https://cdn1.ozone.ru/synthetic.png",false,"",List.of())));});var window=new javafx.stage.Stage();window.setScene(new Scene((javafx.scene.Parent)loader.getRoot(),1000,650));window.show();controller.setShop(new Shop(1,"Synthetic",Marketplace.OZON,"11","fixture"),false);return window;});
  try{
   for(int n=0;n<500&&requested.get()==0;n++)Thread.sleep(10);assertTrue(requested.get()>0);
   fx(()->{controller.refreshLocalData();return null;});for(int n=0;n<500&&reads.get()<6;n++)Thread.sleep(10);assertTrue(reads.get()>=6);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
   var png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",png);image.complete(png.toByteArray());
   TableView<?> table=(TableView<?>)loader.getNamespace().get("newOrdersTable");boolean shown=false;for(int n=0;n<500;n++){if(fx(()->hasImage(table))){shown=true;break;}Thread.sleep(10);}assertTrue(shown,"Unchanged page must accept its still-owned thumbnail after a background refresh");
  }finally{image.complete(null);fx(()->{controller.dispose();stage.close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
 }
 static boolean hasImage(javafx.scene.Node node){if(node instanceof javafx.scene.image.ImageView view&&view.getImage()!=null)return true;if(node instanceof javafx.scene.Parent parent)for(var child:parent.getChildrenUnmodifiable())if(hasImage(child))return true;return false;}

}
