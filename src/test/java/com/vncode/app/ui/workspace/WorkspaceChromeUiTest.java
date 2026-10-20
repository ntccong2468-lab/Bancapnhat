package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.shared.*;
import com.vncode.app.ui.shop.ShopSidebarController;
import com.vncode.app.features.sync.SyncCoordinator;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceChromeUiTest {
 @TempDir Path root;
 @BeforeEach void initialize(){System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();}
 @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
 @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
 static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<T>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
 static void pulse()throws Exception{var done=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int count;public void handle(long now){if(++count==2){stop();done.complete(null);}}}.start();return null;});done.get(5,TimeUnit.SECONDS);}
 @Test void sharedChromeFitsLocalesThemesAndRetainsActionsInCompactMenu()throws Exception{
  System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();var original=I18nService.getInstance().getCurrentLanguage();
  var side=new FXMLLoader[1];var head=new FXMLLoader[1];var stage=fx(()->{
   side[0]=FxmlViewLoader.loader(ShopSidebarController.class,"shop-sidebar-view.fxml");head[0]=FxmlViewLoader.loader(WorkspaceHeaderController.class,"workspace-header-view.fxml");
   Parent nav=side[0].load(),header=head[0].load();var content=new BorderPane(new Label("Synthetic workspace"),header,null,null,null);var shell=new BorderPane(content,null,null,null,nav);var window=new Stage();var scene=new Scene(shell,1024,600);scene.getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());window.setScene(scene);window.show();return window;
  });
  ShopSidebarController sidebar=side[0].getController();WorkspaceHeaderController header=head[0].getController();
  try{
   for(var lang:AppLanguage.values())for(String theme:List.of("theme-dark","theme-light"))for(var size:List.of(new int[]{1024,600},new int[]{1366,768},new int[]{1600,900})){
    fx(()->{I18nService.getInstance().setLanguage(lang);sidebar.applyTranslations();header.applyTranslations();header.setAvailableUpdate("1.4.14");var shell=stage.getScene().getRoot();shell.getStyleClass().removeAll("theme-dark","theme-light");shell.getStyleClass().add(theme);stage.setWidth(size[0]);stage.setHeight(size[1]);sidebar.resetSidebarLayout();sidebar.setViewportWidth(size[0]);return null;});pulse();
    fx(()->{var scene=stage.getScene();scene.getRoot().applyCss();scene.getRoot().layout();var chrome=(HBox)head[0].getRoot();for(var node:chrome.getChildren())if(node.isManaged()&&node.isVisible()){var b=node.localToScene(node.getBoundsInLocal());assertTrue(b.getMinX()>=-1&&b.getMaxX()<=scene.getWidth()+1,lang+" "+size[0]+" clipped header: "+node.getId()+" "+b);}
     var more=(MenuButton)head[0].getNamespace().get("moreButton");if(more.isManaged()){assertEquals(1,more.getItems().size());var body=((CustomMenuItem)more.getItems().getFirst()).getContent();assertNotNull(body);assertNotNull(((Button)head[0].getNamespace().get("supportButton")).getParent());}
     assertTrue(((Button)head[0].getNamespace().get("updateButton")).isManaged());assertEquals(I18nService.getInstance().tr("sidebar.automatic"),((Button)side[0].getNamespace().get("sidebarToggleButton")).getContextMenu().getItems().getFirst().getText());return null;});
   }
   fx(()->{sidebar.setViewportWidth(1024);((Button)side[0].getNamespace().get("sidebarToggleButton")).fire();assertEquals(224,((Region)side[0].getRoot()).getPrefWidth());sidebar.setViewportWidth(1100);assertEquals(224,((Region)side[0].getRoot()).getPrefWidth());sidebar.resetSidebarLayout();assertEquals(64,((Region)side[0].getRoot()).getPrefWidth());sidebar.setGs1Unread(12);assertTrue(((Button)side[0].getNamespace().get("gs1Button")).getText().isBlank());assertTrue(((Button)side[0].getNamespace().get("gs1Button")).getAccessibleText().contains("12"));return null;});
   fx(()->{header.setSyncHealth(new SyncCoordinator.Health(1,SyncCoordinator.Stream.FBS_QUEUE,false,Instant.now(),null,Instant.now().plusSeconds(10),""));var health=(Label)head[0].getNamespace().get("syncHealthLabel");assertTrue(health.getText().startsWith(I18nService.getInstance().tr("sync.health.partial")));header.setSyncHealth(new SyncCoordinator.Health(1,SyncCoordinator.Stream.SUPPLY_CONTENT,false,Instant.now(),Instant.now(),Instant.now().plusSeconds(10),"NETWORK_TIMEOUT"));assertTrue(health.getText().startsWith(I18nService.getInstance().tr("sync.health.delayed")));assertTrue(health.getTooltip().getText().contains("NETWORK_TIMEOUT"));return null;});
  }finally{fx(()->{header.dispose();stage.close();I18nService.getInstance().setLanguage(original);return null;});ShopReadCoordinator.shared().cancelReads(1);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
 }
 @Test void sharedDialogsFitOwnerWithoutReplacingTheirContent()throws Exception{
  fx(()->{var owner=new Stage();owner.setScene(new Scene(new StackPane(),1024,600));owner.show();try{var dialog=new Dialog<Void>();dialog.initOwner(owner);var content=new VBox(new Label("Synthetic"),new TableView<>());dialog.getDialogPane().setContent(content);AlertService.applyTheme(dialog.getDialogPane());dialog.getDialogPane().setPrefSize(1600,1200);assertSame(content,dialog.getDialogPane().getContent());assertTrue(dialog.getDialogPane().getPrefWidth()<=owner.getWidth()-32);assertTrue(dialog.getDialogPane().getPrefHeight()<=owner.getHeight()-60);}finally{owner.close();}return null;});
 }
}
