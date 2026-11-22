package com.vncode.app.shared;

import com.vncode.app.config.Database;
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
 @Test void workspaceUsesOpaqueFlatThemesWhileTextAndControlsStayReadable()throws Exception{
  fx(()->{
   ThemeService.initializeAppearance();
   for(var theme:ThemeService.Theme.values()){
    ThemeService.setCurrentTheme(theme);
    var label=new Label("Đơn hàng / Заказы / Orders 152DASH-52");
    var muted=new Label("GTIN · SKU · Znack");muted.getStyleClass().add("text-muted");
    var input=new TextField("152DASH-52");var button=new Button("Lưu thay đổi");button.getStyleClass().add("btn-primary");
    var selected=new CheckBox("Kho KIZ / Склад КИЗ");selected.setSelected(true);
    var card=new VBox(12,label,muted,input,button,selected);card.getStyleClass().add("card");
    var table=new TableView<String>();table.getColumns().add(new TableColumn<>("Synthetic column"));table.setPrefHeight(130);
    var chrome=new HBox(new Label("Synthetic native chrome"));chrome.getStyleClass().add("header");
    var root=new VBox(18,chrome,card,table);root.getStyleClass().add("workspace-shell");root.setStyle("-fx-padding:24;");
    var stage=new Stage();stage.setScene(new Scene(root,700,520));ThemeService.applyTheme(stage.getScene());stage.show();root.applyCss();root.layout();
    String context=theme.toString();var canvas=paint(root);var panel=paint(card);
    assertEquals(theme==ThemeService.Theme.DARK?Color.BLACK:Color.web("#f2f2f2"),canvas,"Flat workspace ground: "+context);
    assertEquals(theme==ThemeService.Theme.DARK?Color.web("#101010"):Color.WHITE,panel,"Opaque card ground: "+context);
    assertEquals(1,canvas.getOpacity());assertEquals(1,panel.getOpacity());assertEquals(1,paint(table).getOpacity());
    for(var surface:List.of(root,card,table,chrome))if(surface.getBackground()!=null)
     assertTrue(surface.getBackground().getFills().stream().noneMatch(f->f.getFill() instanceof javafx.scene.paint.ImagePattern),"No sampled image: "+context);
    assertEquals(1,root.getOpacity());assertEquals(1,label.getOpacity());assertEquals(1,input.getOpacity());assertEquals(1,stage.getOpacity());
    assertEquals("Inter",label.getFont().getFamily(),"The bundled app font remains applied: "+context);
    assertTrue(((Color)label.getTextFill()).getOpacity()>=.959,"Primary text is never faded: "+context);
    assertTrue(contrast(over((Color)label.getTextFill(),panel),panel)>=7,"Primary content contrast: "+context);
    assertTrue(contrast(over((Color)muted.getTextFill(),panel),panel)>=4.5,"Secondary content contrast: "+context);
    assertTrue(contrast(over((Color)label.getTextFill(),canvas),canvas)>=4.5,"Primary canvas contrast: "+context);
    assertTrue(contrast(over((Color)muted.getTextFill(),canvas),canvas)>=4.5,"Secondary canvas contrast: "+context);
    assertTrue(contrast((Color)button.getTextFill(),paint(button))>=4.5,"Action text contrast: "+context);
    var box=(Region)selected.lookup(".box");var mark=(Region)selected.lookup(".mark");assertNotNull(mark);
    assertTrue(contrast(paint(mark),paint(box))>=4.5,"Selected checkbox mark contrast: "+context);
    assertTrue(root.getStyle().contains("-fx-padding:24"),"Theme preserves caller inline layout");stage.close();
   }return null;
  });
 }
 @Test void oldSavedGlassPreferencesDoNotChangeFlatThemeOrRethemeNewScenes()throws Exception{
  fx(()->{
   for(var theme:ThemeService.Theme.values()){
    Color reference=null;
    for(String value:List.of("0","55","100","NaN"))for(String enabled:List.of("true","false")){
     ConfigService.setConfigValue("glass_enabled",enabled);ConfigService.setConfigValue("glass_transparency",value);
     ThemeService.initializeAppearance();ThemeService.setCurrentTheme(theme);
     var root=new VBox(new Label("Seller workspace"));root.getStyleClass().add("workspace-shell");var stage=new Stage();stage.setScene(new Scene(root,400,250));ThemeService.applyTheme(stage.getScene());stage.show();root.applyCss();root.layout();
     var ground=paint(root);if(reference==null)reference=ground;else assertEquals(reference,ground,"Retired settings cannot alter the current appearance");
     assertEquals(theme==ThemeService.Theme.DARK?Color.BLACK:Color.web("#f2f2f2"),ground);
     assertEquals(1,ground.getOpacity());assertFalse(root.getStyleClass().contains("liquid-glass"));stage.close();
    }
   }return null;
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
 @Test void initializingAppearancePreservesExplicitLightAndSystemPreferences()throws Exception{
  fx(()->{
   ConfigService.setConfigValue("app_theme","light");ConfigService.setConfigValue("glass_transparency","72");
   ThemeService.initializeAppearance();assertEquals(ThemeService.Theme.LIGHT,ThemeService.getCurrentTheme());
   assertEquals(ThemeService.ThemeMode.LIGHT,ThemeService.getThemeMode());
   ThemeService.switchTheme("system");ThemeService.initializeAppearance();
   assertEquals(ThemeService.ThemeMode.SYSTEM,ThemeService.getThemeMode());assertEquals("system",ConfigService.getConfigValue("app_theme"));
   assertNotNull(ThemeService.getCurrentTheme());assertEquals("72",ConfigService.getConfigValue("glass_transparency"));return null;
  });
 }
 @Test void startupSceneIsUntouchedAndNewRootsReceiveTheTheme()throws Exception{
  fx(()->{
   var original=new VBox(new Label("Startup"));var startup=new Scene(original);
   startup.getProperties().put(ThemeService.STARTUP_SCENE,Boolean.TRUE);ThemeService.applyTheme(startup);
   assertTrue(startup.getStylesheets().isEmpty());assertFalse(original.getStyleClass().contains("workspace-theme"));
   ThemeService.setCurrentTheme(ThemeService.Theme.DARK);var root=new VBox();var scene=new Scene(root,500,300);ThemeService.applyTheme(scene);
   var stage=new Stage();stage.setScene(scene);stage.show();assertTrue(root.getStyleClass().contains("theme-dark"));
   var replacement=new VBox();scene.setRoot(replacement);assertTrue(replacement.getStyleClass().contains("theme-dark"));
   var next=new Scene(new VBox(new Label("New scene")));stage.setScene(next);assertTrue(next.getRoot().getStyleClass().contains("theme-dark"));
   assertEquals(1,stage.getOpacity());stage.close();return null;
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
 @Test void approvedNativeChromeUsesManageForAddAndKeepsLiveContentReadable()throws Exception{
  fx(()->{
   var previousLanguage=I18nService.getInstance().getCurrentLanguage();I18nService.getInstance().setLanguage(AppLanguage.VI);
   try {
    ThemeService.initializeAppearance();ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
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
   ThemeService.initializeAppearance();ThemeService.setReducedMotion(true);
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
     assertEquals(1,material.getOpacity(),.001,"The real popup uses an opaque surface after switching to "+theme);
     assertTrue(contrast((Color)add.getTextFill(),material)>=4.5,"The real Manage popup must retain readable text after switching to "+theme+": ink="+add.getTextFill()+" material="+material);
     manage.hide();return null;
    });
   }
  }finally{fx(()->{((com.vncode.app.ui.workspace.WorkspaceHeaderController)loader[0].getController()).dispose();stage.close();return null;});}
 }
 @Test void prethemedContextMenuKeepsItsRetainedSkinPaletteInSyncAfterThemeFlip()throws Exception{
  var popup=new ContextMenu[1];var action=new Button[1];var skin=new javafx.scene.Node[1];
  Stage owner=fx(()->{
   ThemeService.initializeAppearance();ThemeService.setCurrentTheme(ThemeService.Theme.DARK);
   ThemeService.setReducedMotion(true);
   var anchor=new Button("Manage shops");var root=new VBox(anchor);var stage=new Stage();
   stage.setScene(new Scene(root,500,220));ThemeService.applyTheme(stage.getScene());stage.show();
   action[0]=new Button("Add shop");popup[0]=new ContextMenu(new CustomMenuItem(action[0],false));
   popup[0].getStyleClass().add("seller-actions");
   // A popup scene may be themed before its lazily created skin is first shown.
   ThemeService.applyTheme(popup[0].getScene());
   assertTrue(popup[0].getStyleClass().contains("theme-dark"));
   popup[0].show(anchor,javafx.geometry.Side.BOTTOM,0,0);
   popup[0].getScene().getRoot().applyCss();popup[0].getScene().getRoot().layout();
   assertNotNull(popup[0].getSkin());skin[0]=popup[0].getSkin().getNode();
   assertTrue(skin[0].getStyleClass().contains("theme-dark"));
   popup[0].hide();ThemeService.switchTheme("light");popup[0].show(anchor,javafx.geometry.Side.BOTTOM,0,0);
   return stage;
  });
  try{
   var settled=new CompletableFuture<Void>();
   fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==3){stop();settled.complete(null);}}}.start();return null;});
   settled.get(5,TimeUnit.SECONDS);
   fx(()->{
    assertTrue(popup[0].isShowing());assertSame(skin[0],popup[0].getSkin().getNode(),"The public popup retains its existing skin");
    owner.getScene().getRoot().applyCss();var scene=popup[0].getScene();scene.getRoot().applyCss();scene.getRoot().layout();
    var bounds=action[0].localToScene(action[0].getBoundsInLocal());var pixels=scene.snapshot(null);
    var material=pixels.getPixelReader().getColor((int)Math.floor(bounds.getMinX()+4),(int)Math.floor(bounds.getMinY()+4));
    assertEquals(1,material.getOpacity(),.001,"The retained popup skin uses an opaque surface");
    var ink=(Color)action[0].getTextFill();String state="skin="+skin[0].getStyleClass()+" ink="+ink+" material="+material;
    assertAll("A retained popup skin must follow the current light palette: "+state,
     ()->assertTrue(skin[0].getStyleClass().contains("theme-light"),state),
     ()->assertFalse(skin[0].getStyleClass().contains("theme-dark"),state),
     ()->assertTrue(skin[0].getStyleClass().contains("seller-actions"),"Retheming preserves caller-owned classes"),
     ()->assertTrue(contrast(ink,material)>=4.5,state));
    return null;
   });
  }finally{fx(()->{popup[0].hide();owner.close();return null;});}
 }
 static Color paint(Region region){
  var fills=region.getBackground().getFills();
  for(int i=fills.size()-1;i>=0;i--)if(fills.get(i).getFill() instanceof Color colour)return colour;
  throw new AssertionError("No final material tint on "+region);
 }
 static Color over(Color a,Color b){double t=a.getOpacity();return new Color(a.getRed()*t+b.getRed()*(1-t),a.getGreen()*t+b.getGreen()*(1-t),a.getBlue()*t+b.getBlue()*(1-t),1);}
 static double channel(double n){return n<=.04045?n/12.92:Math.pow((n+.055)/1.055,2.4);}
 static double luminance(Color c){return .2126*channel(c.getRed())+.7152*channel(c.getGreen())+.0722*channel(c.getBlue());}
 static double contrast(Color a,Color b){return (Math.max(luminance(a),luminance(b))+.05)/(Math.min(luminance(a),luminance(b))+.05);}
}
