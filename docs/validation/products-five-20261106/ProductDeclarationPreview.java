package com.vncode.app.ui.products;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.stage.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public class ProductDeclarationPreview {
 static <T>T fx(Callable<T> action)throws Exception{var f=new FutureTask<>(action);Platform.runLater(f);return f.get(10,TimeUnit.SECONDS);}
 public static void main(String[] args)throws Exception{
  var output=Path.of(args[0]);Files.createDirectories(output);var profile=Files.createTempDirectory("vncode-declaration-ui-");System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
  try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Cửa hàng mẫu','fixture','WILDBERRIES')");}
  var shop=new Shop(1,"Cửa hàng mẫu","fixture");var repo=new ZnackRepository(new ZnackModels.ShopContext(1,shop.getName()));repo.saveSettings(repo.getSettings().withDefaultGoodsDocument("CONFORMITY_DECLARATION","DECL-FIXTURE-01","01.11.2026"));
  try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_settings SET document_expiry_date='01.11.2031' WHERE shop_id=1");}
  var started=new CountDownLatch(1);Platform.startup(()->{Platform.setImplicitExit(false);started.countDown();});if(!started.await(10,TimeUnit.SECONDS))throw new IllegalStateException();
  var owner=fx(()->{I18nService.getInstance().setLanguage(AppLanguage.VI);var stage=new Stage();stage.setScene(new Scene(new StackPane(),900,600));stage.show();ProductBulkEditPane.showDialog(stage,shop,"documents",List.of(new ProductKey(1,Marketplace.WILDBERRIES,"123","1")),()->{});return stage;});
  DialogPane dialog=null;
  for(int i=0;i<100;i++){dialog=fx(()->Window.getWindows().stream().filter(w->w.getScene()!=null&&w.getScene().getRoot() instanceof DialogPane d&&d.getId()!=null&&d.getId().equals("productBulkEditDialog")).map(w->(DialogPane)w.getScene().getRoot()).filter(d->d.lookup("#editDocumentNumber") instanceof TextField f&&f.getText().equals("DECL-FIXTURE-01")).findFirst().orElse(null));if(dialog!=null)break;Thread.sleep(50);}
  if(dialog==null)throw new IllegalStateException("Configured declaration was not shown");var pane=dialog;
  fx(()->{if(!pane.lookupAll(".radio-button").isEmpty()||pane.lookup("#bulkExistingDocument")!=null)throw new AssertionError("Old type selector remains");ThemeService.switchTheme("dark");ThemeService.applyTheme(pane.getScene());pane.applyCss();pane.layout();var scene=pane.getScene();var image=scene.snapshot(null);var png=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);image.getPixelReader().getPixels(0,0,png.getWidth(),png.getHeight(),javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,png.getWidth());ImageIO.write(png,"png",output.resolve("configured-declaration.png").toFile());return null;});
  fx(()->{var cancel=pane.getButtonTypes().stream().filter(b->b.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();((Button)pane.lookupButton(cancel)).fire();owner.close();Platform.exit();return null;});System.exit(0);
 }
}
