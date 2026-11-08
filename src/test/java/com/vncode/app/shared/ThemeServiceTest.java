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
 @Test void contentStaysOpaqueAndReadableWhileGlassChangesOnlyChrome()throws Exception{
  fx(()->{
   ThemeService.initializeAppearance();ThemeService.setGlassEnabled(true);
   for(var theme:ThemeService.Theme.values())for(int amount:new int[]{0,55,100}){
    ThemeService.setCurrentTheme(theme);ThemeService.setGlassTransparency(amount);
    var label=new Label("Đơn hàng / Заказы / Orders 152DASH-52");
    var muted=new Label("GTIN · SKU · Znack");muted.getStyleClass().add("text-muted");
    var input=new TextField("152DASH-52");var button=new Button("Lưu thay đổi");button.getStyleClass().add("btn-primary");
    var selected=new CheckBox("Kho KIZ / Склад КИЗ");selected.setSelected(true);
    var card=new VBox(12,label,muted,input,button,selected);card.getStyleClass().add("card");
    var table=new TableView<String>();table.getColumns().add(new TableColumn<>("Synthetic column"));table.setPrefHeight(130);
    var chrome=new HBox(new Label("Synthetic native chrome"));chrome.getStyleClass().addAll("header","glass-header");
    var root=new VBox(18,chrome,card,table);root.setStyle("-fx-padding:24;");
    var stage=new Stage();stage.setScene(new Scene(root,700,520));ThemeService.applyTheme(stage.getScene());stage.show();root.applyCss();root.layout();
    String context=theme+" glass="+amount;
    assertEquals(1,root.getOpacity(),context);assertEquals(1,label.getOpacity());assertEquals(1,input.getOpacity());assertEquals(1,stage.getOpacity());
    assertEquals(1,chrome.getOpacity(),"Chrome never fades its controls");
    var material=(Color)chrome.getBackground().getFills().getLast().getFill();
    if(amount==0)assertTrue(material.getOpacity()<.1,"Clear material setting affects chrome: "+context);
    if(amount==100)assertTrue(material.getOpacity()>.9,"Frosted material setting affects chrome: "+context);
    var canvas=paint(root);var panel=paint(card);
    assertEquals(1,panel.getOpacity(),1e-6,"Content panels must remain opaque: "+context);
    assertEquals(1,paint(table).getOpacity(),1e-6,"Working tables must remain opaque: "+context);
    assertNull(table.getEffect(),"Virtualized content must not inherit a glass blur");
    assertTrue(((Color)label.getTextFill()).getOpacity()>=.959,"Primary text is never faded: "+context);
    assertTrue(contrast(over((Color)label.getTextFill(),panel),panel)>=7,"Primary content contrast: "+context);
    assertTrue(contrast(over((Color)muted.getTextFill(),panel),panel)>=4.5,"Secondary content contrast: "+context);
    assertTrue(contrast(over((Color)label.getTextFill(),canvas),canvas)>=4.5,"Primary canvas contrast: "+context);
    assertTrue(contrast(over((Color)muted.getTextFill(),canvas),canvas)>=4.5,"Secondary canvas contrast: "+context);
    var buttonBackground=over(paint(button),panel);
    assertTrue(contrast(over((Color)button.getTextFill(),buttonBackground),buttonBackground)>=4.5,"Action text contrast: "+context);
    var box=(Region)selected.lookup(".box");var mark=(Region)selected.lookup(".mark");
    assertNotNull(mark,"Selected checkbox needs a visible native mark");
    assertTrue(contrast(paint(mark),paint(box))>=4.5,"Selected checkbox mark contrast: "+context);
    assertTrue(root.getStyle().contains("-fx-padding:24"),"Theme must preserve caller inline layout");
    assertTrue(root.getStyleClass().contains("liquid-glass"));
    assertEquals(amount,root.getProperties().get("glass.transparency"));
    String output=System.getProperty("vncode.glass.screenshots");
    if(output!=null){var dir=Path.of(output);Files.createDirectories(dir);var image=root.snapshot(null,null);var pixels=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)pixels.setRGB(x,y,image.getPixelReader().getArgb(x,y));javax.imageio.ImageIO.write(pixels,"png",dir.resolve(theme.getKey()+"-"+amount+".png").toFile());}
    stage.close();
   }return null;
  });
 }
 @Test void previewsDoNotPersistUntilSavedAndAllOpenAndNewRootsReceiveSetting()throws Exception{
  fx(()->{
   ThemeService.setCurrentTheme(ThemeService.Theme.DARK);ThemeService.setGlassTransparency(35);var first=new VBox(new Label("A"));var stage=new Stage();stage.setScene(new Scene(first));ThemeService.applyTheme(stage.getScene());stage.show();
   var pane=new DialogPane();ThemeService.applyTheme(pane);
   ThemeService.previewGlassTransparency(90);assertEquals(35,ThemeService.getGlassTransparency());assertEquals(90,first.getProperties().get("glass.transparency"));assertEquals(90,pane.getProperties().get("glass.transparency"));
   ThemeService.setGlassTransparency(80);assertEquals(80,ThemeService.getGlassTransparency());
   var replacement=new VBox();stage.getScene().setRoot(replacement);assertEquals(80,replacement.getProperties().get("glass.transparency"));
   var nextScene=new Scene(new VBox(new Label("New workspace")));stage.setScene(nextScene);assertEquals(80,nextScene.getRoot().getProperties().get("glass.transparency"));
   var popup=new Stage();popup.setScene(new Scene(new VBox(new Label("Popup"))));popup.show();assertTrue(popup.getScene().getRoot().getStyleClass().contains("theme-dark"));
   assertEquals(80,popup.getScene().getRoot().getProperties().get("glass.transparency"));return null;
  });
 }
 @Test void disabledSemanticAndProductFooterActionsStayDistinctReadableAndNonInvokable()throws Exception{
  fx(()->{
   for(var theme:ThemeService.Theme.values()){
    ThemeService.setCurrentTheme(theme);
    var root=new VBox(14);root.setId("productsPane");root.setStyle("-fx-padding:20;");var regular=new HBox(12);
    var disabledInk=new Label("Disabled action text");disabledInk.getStyleClass().add("text-muted");
    var accentInk=new Label("Sync activity");accentInk.getStyleClass().add("selection-count");
    var selectedPaint=new Region();selectedPaint.setStyle("-fx-background-color:-ui-selected;");selectedPaint.setPrefSize(12,12);
    var buttons=new ArrayList<Button>();var calls=new java.util.concurrent.atomic.AtomicInteger();
    for(String style:List.of("btn-primary","btn-success","btn-danger")){
     var action=new Button(style);action.getStyleClass().add(style);action.setGraphic(new org.kordamp.ikonli.javafx.FontIcon("fth-check"));
     action.setOnAction(e->calls.incrementAndGet());buttons.add(action);regular.getChildren().add(action);
    }
    var products=new VBox();products.getStyleClass().add("products-workspace");
    var footer=new HBox();footer.setId("productBulkActions");footer.getStyleClass().addAll("products-bulk","workspace-batch-footer");
    var footerAction=new Button("Edit product field");footerAction.getStyleClass().add("btn-primary");
    footerAction.setGraphic(new org.kordamp.ikonli.javafx.FontIcon("fth-award"));footerAction.setOnAction(e->calls.incrementAndGet());
    footer.getChildren().add(footerAction);products.getChildren().add(footer);buttons.add(footerAction);
    var sync=new Button("Synchronizing");sync.getStyleClass().add("btn-sync");sync.setGraphic(new org.kordamp.ikonli.javafx.FontIcon("fth-refresh-cw"));
    root.getChildren().setAll(regular,products,sync,disabledInk,accentInk,selectedPaint);
    var stage=new Stage();stage.setScene(new Scene(root,880,350));ThemeService.applyTheme(stage.getScene());stage.show();root.applyCss();root.layout();
    var enabledFill=new HashMap<Button,Color>();var enabledBorder=new HashMap<Button,javafx.scene.paint.Paint>();var enabledInk=new HashMap<Button,Color>();
    for(var action:buttons){
     enabledFill.put(action,paint(action));enabledBorder.put(action,action.getBorder().getStrokes().getFirst().getTopStroke());enabledInk.put(action,(Color)action.getTextFill());
     assertTrue(contrast((Color)action.getTextFill(),paint(action))>=4.5,"Enabled action contrast: "+theme+" "+action.getText());
     var enabledIcon=(org.kordamp.ikonli.javafx.FontIcon)action.getGraphic();
     assertEquals(action.getTextFill(),enabledIcon.getIconColor(),"Enabled icon retains its semantic action ink under the product ID: "+theme+" "+action.getText());
     assertTrue(contrast((Color)enabledIcon.getIconColor(),paint(action))>=4.5,"Enabled icon contrast: "+theme+" "+action.getText());action.setDisable(true);
    }
    sync.setDisable(true);root.applyCss();root.layout();
    for(var action:buttons){
     String context=theme+" "+action.getText();var ink=(Color)action.getTextFill();var fill=paint(action);
     assertNotEquals(enabledFill.get(action),fill,"Disabled fill differs from enabled: "+context);
     assertNotEquals(enabledBorder.get(action),action.getBorder().getStrokes().getFirst().getTopStroke(),"Disabled border differs from enabled: "+context);
     assertNotEquals(enabledInk.get(action),ink,"Disabled text differs from enabled: "+context);
     assertEquals(disabledInk.getTextFill(),ink,"Semantic/footer specificity cannot hide disabled text state: "+context);
     var icon=(org.kordamp.ikonli.javafx.FontIcon)action.getGraphic();assertEquals(ink,icon.getIconColor(),"Disabled icon follows readable muted text: "+context);
     assertTrue(contrast(ink,fill)>=4.5,"Disabled text contrast: "+context);assertTrue(contrast((Color)icon.getIconColor(),fill)>=4.5,"Disabled icon contrast: "+context);
     assertEquals(1,action.getOpacity(),context);assertEquals(1,icon.getOpacity(),context);assertEquals(1,ink.getOpacity(),context);action.fire();
    }
    assertEquals(0,calls.get(),"Disabled buttons cannot invoke their existing callbacks");
    assertEquals(paint(selectedPaint),paint(sync),"Sync uses its activity surface while disabled");
    assertEquals(accentInk.getTextFill(),((org.kordamp.ikonli.javafx.FontIcon)sync.getGraphic()).getIconColor(),"Sync keeps its activity icon color");
    assertTrue(contrast((Color)sync.getTextFill(),paint(sync))>=4.5);assertEquals(1,sync.getOpacity());
    for(var action:buttons){action.setDisable(false);action.fire();}assertEquals(4,calls.get(),"Reenabling preserves all existing callbacks");
    stage.close();
   }return null;
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
 @Test void initializingAppearancePreservesExplicitLightSystemAndMaterialPreferences()throws Exception{
  fx(()->{
   ConfigService.setConfigValue("app_theme","light");ConfigService.setConfigValue("glass_transparency","72");
   ThemeService.initializeAppearance();assertEquals(ThemeService.Theme.LIGHT,ThemeService.getCurrentTheme());
   assertEquals(ThemeService.ThemeMode.LIGHT,ThemeService.getThemeMode());assertEquals(72,ThemeService.getGlassTransparency());
   ThemeService.switchTheme("system");ThemeService.initializeAppearance();
   assertEquals(ThemeService.ThemeMode.SYSTEM,ThemeService.getThemeMode());assertEquals("system",ConfigService.getConfigValue("app_theme"));
   assertNotNull(ThemeService.getCurrentTheme());assertEquals(72,ThemeService.getGlassTransparency());
   ThemeService.setGlassEnabled(false);ThemeService.initializeAppearance();
   assertFalse(ThemeService.isGlassEnabled());assertEquals(72,ThemeService.getGlassTransparency());return null;
  });
 }
 @Test void disablingGlassChangesChromeWithoutFadingTheWorkspaceAndStartupSceneIsUntouched()throws Exception{
  fx(()->{
   var original=new VBox(new Label("Startup"));var startup=new Scene(original);
   startup.getProperties().put(ThemeService.STARTUP_SCENE,Boolean.TRUE);ThemeService.applyTheme(startup);
   assertTrue(startup.getStylesheets().isEmpty());assertFalse(original.getStyleClass().contains("workspace-theme"));
   var panel=new VBox(new Label("Content"));panel.getStyleClass().add("card");
   var root=new VBox(panel);var scene=new Scene(root,500,300);ThemeService.applyTheme(scene);
   var stage=new Stage();stage.setScene(scene);stage.show();
   ThemeService.setGlassEnabled(false);root.applyCss();root.layout();
   assertFalse(root.getStyleClass().contains("liquid-glass"));assertTrue(root.getStyleClass().contains("glass-disabled"));
   assertEquals(1,root.getOpacity());assertEquals(1,paint(panel).getOpacity());
   ThemeService.setGlassEnabled(true);assertTrue(root.getStyleClass().contains("liquid-glass"));
   assertFalse(root.getStyleClass().contains("glass-disabled"));stage.close();return null;
  });
 }
 @Test void reducedMotionUsesTheSavedPreferenceAndCachedReadsUntilAppearanceReload()throws Exception{
  fx(()->{
   ThemeService.initializeAppearance();ThemeService.setReducedMotion(true);
   assertEquals("true",ConfigService.getConfigValue("ui_reduced_motion"));assertTrue(ThemeService.isReducedMotion());
   ConfigService.setConfigValue("ui_reduced_motion","false");
   assertTrue(ThemeService.isReducedMotion(),"Animation checks use the cached preference until the profile reloads");
   ThemeService.initializeAppearance();
   assertEquals(Platform.getPreferences().isReducedMotion(),ThemeService.isReducedMotion(),"The OS accessibility preference remains authoritative");
   ThemeService.setReducedMotion(true);ThemeService.setReducedMotion(false);
   assertEquals("false",ConfigService.getConfigValue("ui_reduced_motion"));
   assertEquals(Platform.getPreferences().isReducedMotion(),ThemeService.isReducedMotion());return null;
  });
 }
 @Test void approvedNativeChromeUsesManageForAddAndKeepsWorkingContentOpaque()throws Exception{
  fx(()->{
   var previousLanguage=I18nService.getInstance().getCurrentLanguage();I18nService.getInstance().setLanguage(AppLanguage.VI);
   try {
    ThemeService.initializeAppearance();ThemeService.setCurrentTheme(ThemeService.Theme.DARK);ThemeService.setGlassTransparency(55);
    var sidebar=FxmlViewLoader.loader(com.vncode.app.ui.shop.ShopSidebarController.class,"shop-sidebar-view.fxml");
    var header=FxmlViewLoader.loader(com.vncode.app.ui.workspace.WorkspaceHeaderController.class,"workspace-header-view.fxml");
    javafx.scene.Parent nav=sidebar.load(),top=header.load();
    var welcome=new com.vncode.app.ui.dashboard.WelcomePane(new VBox(),Collections.nCopies(6,()->{}));
    var shell=new BorderPane(new BorderPane(welcome,top,null,null,null),null,null,null,nav);
    var stage=new Stage();stage.setScene(new Scene(shell,1460,900));ThemeService.applyTheme(stage.getScene());stage.show();shell.applyCss();shell.layout();
    assertTrue(shell.getStyleClass().contains("theme-dark"));assertEquals(6,shell.lookupAll(".feature-card").size());
    assertNull(nav.lookup("#addShopButton"),"Only Manage contains Add shop");
    var add=(Button)header.getNamespace().get("addShopButton");assertNotNull(add);assertEquals(1,add.getOpacity());
    assertFalse(((HBox)top).getChildren().contains(add));
    var manage=(MenuButton)header.getNamespace().get("manageShopButton");manage.show();assertTrue(manage.isShowing());
    assertTrue(add.getScene().getWindow().isShowing());manage.hide();
    for(var node:shell.lookupAll(".btn-icon"))if(node instanceof Button icon&&icon.isVisible()&&icon.isManaged()){
     assertTrue(icon.getWidth()>=28&&icon.getHeight()>=28,"Native icon controls retain a usable target");
     assertEquals(1,icon.getOpacity());
    }
    ((com.vncode.app.ui.workspace.WorkspaceHeaderController)header.getController()).dispose();stage.close();return null;
   } finally { I18nService.getInstance().setLanguage(previousLanguage); }
  });
 }
 @Test void reopeningTheRealManagePopupAfterThemeFlipRefreshesItsSkinInkAndMaterial()throws Exception{
  var loader=new javafx.fxml.FXMLLoader[1];var reference=new Label[1];
  Stage stage=fx(()->{
   ThemeService.initializeAppearance();ThemeService.setGlassEnabled(true);ThemeService.setGlassTransparency(55);ThemeService.setReducedMotion(true);
   loader[0]=FxmlViewLoader.loader(com.vncode.app.ui.workspace.WorkspaceHeaderController.class,"workspace-header-view.fxml");
   javafx.scene.Parent header=loader[0].load();reference[0]=new Label("Synthetic owner theme reference");
   var root=new VBox(reference[0],header);var window=new Stage();window.setScene(new Scene(root,1100,300));ThemeService.applyTheme(window.getScene());window.show();return window;
  });
  try{
   for(String theme:List.of("light","dark","light")){
    // The production path flips the owner theme and reopens the same MenuButton in one FX turn.
    fx(()->{ThemeService.switchTheme(theme);((MenuButton)loader[0].getNamespace().get("manageShopButton")).show();return null;});
    var settled=new CompletableFuture<Void>();
    fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==3){stop();settled.complete(null);}}}.start();return null;});settled.get(5,TimeUnit.SECONDS);
    fx(()->{
     var manage=(MenuButton)loader[0].getNamespace().get("manageShopButton");var add=(Button)loader[0].getNamespace().get("addShopButton");
     assertTrue(manage.isShowing());var popup=add.getScene();WorkspaceMotion.cancelAll();
     stage.getScene().getRoot().applyCss();popup.getRoot().applyCss();popup.getRoot().layout();
     assertEquals(reference[0].getTextFill(),add.getTextFill(),"The reused popup skin must use its owner's current "+theme+" ink");
     assertEquals(1,popup.getRoot().getOpacity());assertEquals(1,popup.getWindow().getOpacity());assertEquals(1,add.getOpacity());
     var bounds=add.localToScene(add.getBoundsInLocal());var pixels=popup.snapshot(null);
     var material=pixels.getPixelReader().getColor((int)Math.floor(bounds.getMinX()+4),(int)Math.floor(bounds.getMinY()+4));
     assertTrue(contrast((Color)add.getTextFill(),material)>=4.5,"The real Manage popup must retain readable text after switching to "+theme+": ink="+add.getTextFill()+" material="+material);
     manage.hide();return null;
    });
   }
  }finally{fx(()->{((com.vncode.app.ui.workspace.WorkspaceHeaderController)loader[0].getController()).dispose();stage.close();return null;});}
 }
 static Color paint(Region region){return (Color)region.getBackground().getFills().getFirst().getFill();}
 static Color over(Color a,Color b){double t=a.getOpacity();return new Color(a.getRed()*t+b.getRed()*(1-t),a.getGreen()*t+b.getGreen()*(1-t),a.getBlue()*t+b.getBlue()*(1-t),1);}
 static double channel(double n){return n<=.04045?n/12.92:Math.pow((n+.055)/1.055,2.4);}
 static double luminance(Color c){return .2126*channel(c.getRed())+.7152*channel(c.getGreen())+.0722*channel(c.getBlue());}
 static double contrast(Color a,Color b){return (Math.max(luminance(a),luminance(b))+.05)/(Math.min(luminance(a),luminance(b))+.05);}
}
