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
    var card=new VBox(12,label,muted,input,button);card.getStyleClass().add("card");
    var root=new VBox(18,new Label("VNCode — Liquid Glass"),card);root.setStyle("-fx-padding:24;");
    var stage=new Stage();stage.setScene(new Scene(root,700,420));ThemeService.applyTheme(stage.getScene());stage.show();root.applyCss();root.layout();
    assertEquals(1,root.getOpacity());assertEquals(1,label.getOpacity());assertEquals(1,input.getOpacity());assertEquals(1,stage.getOpacity());
    assertTrue(card.getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius()>=18);
    assertEquals(1,((Color)label.getTextFill()).getOpacity());
    var background=theme==ThemeService.Theme.DARK?Color.web("#24233f"):Color.web("#d5eeef");
    var fill=(Color)card.getBackground().getFills().getFirst().getFill();var blended=over(fill,background);
    assertTrue(contrast((Color)label.getTextFill(),blended)>=7,"Primary text contrast: "+theme+" "+amount);
    assertTrue(contrast((Color)muted.getTextFill(),blended)>=4.5,"Secondary text contrast: "+theme+" "+amount);
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
 static Color over(Color a,Color b){double t=a.getOpacity();return new Color(a.getRed()*t+b.getRed()*(1-t),a.getGreen()*t+b.getGreen()*(1-t),a.getBlue()*t+b.getBlue()*(1-t),1);}
 static double channel(double n){return n<=.04045?n/12.92:Math.pow((n+.055)/1.055,2.4);}
 static double luminance(Color c){return .2126*channel(c.getRed())+.7152*channel(c.getGreen())+.0722*channel(c.getBlue());}
 static double contrast(Color a,Color b){return (Math.max(luminance(a),luminance(b))+.05)/(Math.min(luminance(a),luminance(b))+.05);}
}
