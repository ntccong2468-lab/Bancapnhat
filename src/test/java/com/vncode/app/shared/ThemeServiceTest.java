package com.vncode.app.shared;

import com.vncode.app.config.Database;
import com.vncode.app.ui.GlassSettingsDialog;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemeServiceTest {
 @TempDir Path temp;
 @BeforeAll static void toolkit()throws Exception{try{Platform.startup(()->{});}catch(IllegalStateException started){}fx(()->{Platform.setImplicitExit(false);return null;});}
 @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();}
 @AfterEach void cleanup()throws Exception{fx(()->{for(var w:List.copyOf(Window.getWindows()))if(w instanceof Stage s)s.close();return null;});System.clearProperty("vncode.appdata.dir");}
 static <T>T fx(Callable<T> call)throws Exception{var task=new FutureTask<>(call);Platform.runLater(task);return task.get(30,TimeUnit.SECONDS);}
 @Test void allMaterialsRoundCornersAndKeepTextOpaqueAndReadableAtSliderExtremes()throws Exception{
  fx(()->{
   for(var theme:ThemeService.Theme.values())for(int amount:new int[]{0,55,100}){
    ThemeService.setCurrentTheme(theme);ThemeService.setGlassTransparency(amount);
    var label=new Label("Đơn hàng / Заказы / Orders 152DASH-52");label.setWrapText(true);
    var muted=new Label("GTIN · SKU · Znack");muted.getStyleClass().add("text-muted");
    var input=new TextField("152DASH-52");var button=new Button("Lưu thay đổi");button.getStyleClass().add("btn-primary");
    var selected=new CheckBox("Kho KIZ / Склад КИЗ");selected.setSelected(true);
    var card=new VBox(12,label,muted,input,button,selected);card.getStyleClass().add("card");
    var root=new VBox(18,new Label("VNCode — Liquid Glass"),card);root.setStyle("-fx-padding:24;");
    var stage=new Stage();stage.setScene(new Scene(root,700,420));ThemeService.applyTheme(stage.getScene());stage.show();root.applyCss();root.layout();
    assertEquals(1,root.getOpacity());assertEquals(1,label.getOpacity());assertEquals(1,input.getOpacity());assertEquals(1,stage.getOpacity());
    assertTrue(card.getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius()>=18);
    assertEquals(1,((Color)label.getTextFill()).getOpacity());
    var background=theme==ThemeService.Theme.DARK?Color.web("#163c60"):Color.web("#74a6dc");
    var fill=(Color)card.getBackground().getFills().getFirst().getFill();var blended=over(fill,background);
    assertTrue(contrast((Color)label.getTextFill(),blended)>=7,"Primary text contrast: "+theme+" "+amount);
    assertTrue(contrast((Color)muted.getTextFill(),blended)>=4.5,"Secondary text contrast: "+theme+" "+amount);
    var strongestCanvas=theme==ThemeService.Theme.DARK?Color.web("#1b517c"):Color.web("#74a6dc");
    assertTrue(contrast((Color)label.getTextFill(),strongestCanvas)>=4.5,"Primary text directly on canvas");
    assertTrue(contrast((Color)muted.getTextFill(),strongestCanvas)>=4.5,"Secondary text directly on canvas");
    for(var stop:((javafx.scene.paint.LinearGradient)button.getBackground().getFills().getFirst().getFill()).getStops())assertTrue(contrast((Color)button.getTextFill(),stop.getColor())>=4.5,"Action text contrast");
    var box=(Region)selected.lookup(".box");assertTrue(contrast(Color.WHITE,(Color)box.getBackground().getFills().getFirst().getFill())>=4.5,"Selected checkbox must remain visible");
    assertTrue(root.getStyle().contains("-fx-padding:24"));
    String output=System.getProperty("vncode.glass.screenshots");
    if(output!=null){var dir=Path.of(output);Files.createDirectories(dir);var image=root.snapshot(null,null);var pixels=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)pixels.setRGB(x,y,image.getPixelReader().getArgb(x,y));javax.imageio.ImageIO.write(pixels,"png",dir.resolve(theme.getKey()+"-"+amount+".png").toFile());}
    stage.close();
   }return null;
  });
 }
 @Test void previewsDoNotPersistUntilSavedAndAllOpenAndNewRootsReceiveSetting()throws Exception{
  fx(()->{
   ThemeService.setGlassTransparency(35);var first=new VBox(new Label("A"));var stage=new Stage();stage.setScene(new Scene(first));ThemeService.applyTheme(stage.getScene());stage.show();
   var pane=new DialogPane();ThemeService.applyTheme(pane);
   ThemeService.previewGlassTransparency(90);assertEquals(35,ThemeService.getGlassTransparency());assertEquals(90,first.getProperties().get("glass.transparency"));assertEquals(90,pane.getProperties().get("glass.transparency"));
   ThemeService.setGlassTransparency(80);assertEquals(80,ThemeService.getGlassTransparency());
   var replacement=new VBox();stage.getScene().setRoot(replacement);assertEquals(80,replacement.getProperties().get("glass.transparency"));
   var nextScene=new Scene(new VBox(new Label("New workspace")));stage.setScene(nextScene);assertEquals(80,nextScene.getRoot().getProperties().get("glass.transparency"));
   var popup=new Stage();popup.setScene(new Scene(new VBox(new Label("Popup"))));popup.show();assertTrue(popup.getScene().getRoot().getStyleClass().contains("liquid-glass"));
   assertEquals(80,popup.getScene().getRoot().getProperties().get("glass.transparency"));return null;
  });
 }
 @Test void settingsDialogActuallySavesAndCancelRestoresPreview()throws Exception{
  fx(()->{
   ThemeService.setGlassTransparency(40);Platform.runLater(()->operateDialog(82,true));GlassSettingsDialog.show();assertEquals(82,ThemeService.getGlassTransparency());
   Platform.runLater(()->operateDialog(10,false));GlassSettingsDialog.show();assertEquals(82,ThemeService.getGlassTransparency());return null;
  });
 }
 static void operateDialog(int value,boolean save){
  for(var w:List.copyOf(Window.getWindows()))if(w.getScene()!=null){var n=w.getScene().getRoot().lookup("#glassTransparencySlider");if(n instanceof Slider slider){slider.setValue(value);DialogPane pane=(DialogPane)w.getScene().getRoot();var type=pane.getButtonTypes().stream().filter(t->save?t.getButtonData()==ButtonBar.ButtonData.OK_DONE:t.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();((Button)pane.lookupButton(type)).fire();return;}}
  throw new AssertionError("Glass settings dialog was not shown");
 }
 @Test void invalidSavedSettingAndOutOfBoundsCannotMakeControlsInvisible(){
  ConfigService.setConfigValue("glass_transparency","NaN");assertEquals(55,ThemeService.getGlassTransparency());
  ConfigService.setConfigValue("glass_transparency","200");assertEquals(100,ThemeService.getGlassTransparency());
  ConfigService.setConfigValue("glass_transparency","-20");assertEquals(0,ThemeService.getGlassTransparency());
 }
 @Test void referenceAppearanceAppliesOnceAndPreservesTransparencyAndLaterThemeChoice(){
  ConfigService.setConfigValue("app_theme","dark");ConfigService.setConfigValue("glass_transparency","72");
  ThemeService.initializeAppearance();assertEquals(ThemeService.Theme.LIGHT,ThemeService.getCurrentTheme());assertEquals(72,ThemeService.getGlassTransparency());
  ConfigService.setConfigValue("app_theme","dark");ThemeService.initializeAppearance();assertEquals(ThemeService.Theme.DARK,ThemeService.getCurrentTheme());
 }
 @Test void blueReferenceRendersTheActualVncodeNavigationAndWelcomePage()throws Exception{
  fx(()->{
   var previousLanguage=I18nService.getInstance().getCurrentLanguage();I18nService.getInstance().setLanguage(AppLanguage.VI);
   try {
   ThemeService.initializeAppearance();ThemeService.setGlassTransparency(55);
   var sidebar=FxmlViewLoader.loader(com.vncode.app.ui.shop.ShopSidebarController.class,"shop-sidebar-view.fxml");
   var header=FxmlViewLoader.loader(com.vncode.app.ui.workspace.WorkspaceHeaderController.class,"workspace-header-view.fxml");
   javafx.scene.Parent nav=sidebar.load(),top=header.load();
   var welcome=new com.vncode.app.ui.dashboard.WelcomePane(new VBox(),Collections.nCopies(6,()->{}));
   var content=new BorderPane(welcome,top,null,null,null);BorderPane.setMargin(welcome,new javafx.geometry.Insets(18));
   var shell=new BorderPane(content,null,null,null,nav);var stage=new Stage();stage.setScene(new Scene(shell,1460,900));ThemeService.applyTheme(stage.getScene());stage.show();shell.applyCss();shell.layout();
   assertTrue(shell.getStyleClass().contains("theme-light"));assertEquals(6,shell.lookupAll(".feature-card").size());
   for(var node:shell.lookupAll(".feature-card"))assertEquals(26,((Region)node).getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius());
   var primary=(Button)shell.lookup(".add-shop");assertNotNull(primary);assertEquals(1,primary.getOpacity());
   String output=System.getProperty("vncode.glass.screenshots");
   if(output!=null){var image=shell.snapshot(null,null);var pixels=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)pixels.setRGB(x,y,image.getPixelReader().getArgb(x,y));var dir=Path.of(output);Files.createDirectories(dir);javax.imageio.ImageIO.write(pixels,"png",dir.resolve("vncode-blue-reference.png").toFile());}
   ((com.vncode.app.ui.workspace.WorkspaceHeaderController)header.getController()).dispose();stage.close();return null;
   } finally { I18nService.getInstance().setLanguage(previousLanguage); }
  });
 }
 static Color over(Color a,Color b){double t=a.getOpacity();return new Color(a.getRed()*t+b.getRed()*(1-t),a.getGreen()*t+b.getGreen()*(1-t),a.getBlue()*t+b.getBlue()*(1-t),1);}
 static double channel(double n){return n<=.04045?n/12.92:Math.pow((n+.055)/1.055,2.4);}
 static double luminance(Color c){return .2126*channel(c.getRed())+.7152*channel(c.getGreen())+.0722*channel(c.getBlue());}
 static double contrast(Color a,Color b){return (Math.max(luminance(a),luminance(b))+.05)/(Math.min(luminance(a),luminance(b))+.05);}
}
