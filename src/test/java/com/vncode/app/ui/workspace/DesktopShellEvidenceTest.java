package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.shared.*;
import com.vncode.app.ui.ozon.OzonDashboardController;
import com.vncode.app.ui.products.ProductsPane;
import com.vncode.app.ui.shop.ShopSidebarController;
import com.vncode.app.ui.znackregistration.ZnackCardRegistrationController;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual JavaFX layout evidence. Uses no shop, remote API, seller data or screenshots from production. */
class DesktopShellEvidenceTest {
    @TempDir Path data;
    @BeforeAll static void start() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException alreadyStarted) { } }
    static <T> T fx(Callable<T> work) throws Exception { var task = new FutureTask<>(work); Platform.runLater(task); return task.get(20, TimeUnit.SECONDS); }
    static void pulse() throws Exception {
        var done = new CompletableFuture<Void>();
        fx(() -> { new javafx.animation.AnimationTimer() { int frames; @Override public void handle(long now) {
            if (++frames == 2) { stop(); done.complete(null); }
        }}.start(); return null; });
        done.get(8, TimeUnit.SECONDS);
    }

    @Test void desktopShellKeepsPrimaryListsLargeInBothThemes() throws Exception {
        System.setProperty("vncode.appdata.dir", data.toString()); Database.initDatabase();
        var previousLanguage = I18nService.getInstance().getCurrentLanguage(); var previousTheme = ThemeService.getCurrentTheme();
        Stage stage = fx(Stage::new);
        try {
            fx(() -> { I18nService.getInstance().setLanguage(AppLanguage.VI); return null; });
            for (String theme : List.of("dark", "light")) for (String viewName : List.of("products", "ozon", "znack")) {
                Object[] loaded = fx(() -> {
                    var navLoader = FxmlViewLoader.loader(ShopSidebarController.class, "shop-sidebar-view.fxml");
                    Parent nav = navLoader.load();
                    ((ShopSidebarController)navLoader.getController()).selectNavigation(viewName.equals("products")
                            ? "productsButton" : viewName.equals("ozon") ? "packingButton" : "znackRegistrationButton");
                    var header = FxmlViewLoader.loader(WorkspaceHeaderController.class, "workspace-header-view.fxml").<Parent>load();
                    Parent page; Object controller;
                    if (viewName.equals("products")) { var products = new ProductsPane(); page=products; controller=products; }
                    else if (viewName.equals("ozon")) { var loader=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml"); page=loader.load(); controller=loader.getController(); }
                    else { var loader=FxmlViewLoader.loader(ZnackCardRegistrationController.class,"znack-card-registration-view.fxml"); page=loader.load(); controller=loader.getController(); }
                    var content = new BorderPane(page,header,null,null,null); content.setMinSize(0,0);
                    var shell = new BorderPane(content,null,null,null,nav); shell.setMinSize(0,0);
                    stage.setScene(new Scene(shell,1360,820)); ThemeService.switchTheme(theme); ThemeService.applyTheme(stage.getScene()); stage.show();
                    return new Object[]{shell,page,controller};
                });
                try {
                    for (int[] size : List.of(new int[]{1360,820},new int[]{1000,650})) {
                        fx(() -> { stage.setWidth(size[0]); stage.setHeight(size[1]); return null; }); pulse();
                        fx(() -> {
                            Parent shell=(Parent)loaded[0], page=(Parent)loaded[1]; shell.applyCss(); shell.layout();
                            String id = viewName.equals("products") ? "#productRows" : viewName.equals("ozon") ? "#newOrdersTable" : "#productTable";
                            var list=page.lookup(id); assertNotNull(list, viewName+" list missing");
                            if (viewName.equals("ozon") && theme.equals("light")) {
                                var selected = page.lookup(".packing-tabs .tab:selected .tab-label");
                                assertInstanceOf(javafx.scene.control.Label.class, selected, "Selected tab label missing");
                                var fill = ((javafx.scene.control.Label) selected).getTextFill();
                                assertInstanceOf(javafx.scene.paint.Color.class, fill);
                                var ink = (javafx.scene.paint.Color) fill;
                                assertTrue(ink.getBrightness() < .5, "Selected light-theme tab needs dark text");
                            }
                            var b=list.localToScene(list.getBoundsInLocal());
                            assertTrue(b.getWidth() >= (stage.getScene().getWidth()-260)*0.84, viewName+" list too narrow: "+b);
                            assertTrue(b.getHeight() >= stage.getScene().getHeight()*0.35, viewName+" list too short: "+b);
                            assertTrue(b.getMaxX() <= stage.getScene().getWidth()+1 && b.getMaxY() <= stage.getScene().getHeight()+1, viewName+" list outside viewport");
                            for (var node : shell.lookupAll(".button")) if (node instanceof Labeled && node.isVisible() && node.isManaged()) {
                                boolean ancestorsVisible=true, scrollable=false;
                                for(var p=node;p!=null;p=p.getParent()){ancestorsVisible &= p.isVisible(); if(p instanceof javafx.scene.control.ScrollPane || p instanceof javafx.scene.control.TableView || p instanceof javafx.scene.control.ListView) scrollable=true;}
                                if(!ancestorsVisible||scrollable)continue;
                                var bounds=node.localToScene(node.getBoundsInLocal());
                                assertTrue(bounds.getMaxY()<=stage.getScene().getHeight()+1, viewName+" action below viewport: "+node.getId());
                            }
                            if(size[0]==1360) capture(stage.getScene(),theme,viewName);
                            return null;
                        });
                    }
                } finally {
                    fx(() -> { Object controller=loaded[2]; if(controller instanceof ProductsPane products) products.dispose();
                        else if(controller instanceof OzonDashboardController ozon) ozon.dispose();
                        else try { controller.getClass().getMethod("dispose").invoke(controller); } catch (NoSuchMethodException ignored) { }
                        return null; });
                }
            }
        } finally { fx(() -> { stage.close(); I18nService.getInstance().setLanguage(previousLanguage); ThemeService.setCurrentTheme(previousTheme); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle(); System.clearProperty("vncode.appdata.dir"); }
    }

    private static void capture(Scene scene, String theme, String view) throws Exception {
        String dir=System.getProperty("vncode.shell.capture.dir"); if(dir==null)return;
        var image=scene.snapshot(null); int w=(int)image.getWidth(),h=(int)image.getHeight();
        var png=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.getPixelReader().getPixels(0,0,w,h,javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,w);
        Files.createDirectories(Path.of(dir)); javax.imageio.ImageIO.write(png,"png",Path.of(dir,"shell-"+view+"-"+theme+".png").toFile());
    }
}
