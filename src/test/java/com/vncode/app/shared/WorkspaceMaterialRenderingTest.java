package com.vncode.app.shared;

import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Rendered material, not a screenshot of an HTML substitute or a live seller profile. */
class WorkspaceMaterialRenderingTest {
    @TempDir Path profile;
    private String previous;

    @BeforeAll static void toolkit() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
        fx(() -> { Platform.setImplicitExit(false);return null; });
    }
    @BeforeEach void profile() {
        previous=System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
    }
    @AfterEach void cleanup() throws Exception {
        fx(() -> {
            for(var window:List.copyOf(Window.getWindows()))if(window instanceof Stage stage)stage.close();
            ThemeService.setReducedMotion(false);return null;
        });
        if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);
    }

    @Test void floatingChromeHasDirectionalLightWhileWorkingDataStaysOpaque() throws Exception {
        fx(() -> {
            ThemeService.setReducedMotion(true);ThemeService.setGlassEnabled(true);ThemeService.setGlassTransparency(55);
            var materialChecks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
            for(var theme:ThemeService.Theme.values()) {
                ThemeService.setCurrentTheme(theme);
                var title=new Label("VN Code · Synthetic material evidence");
                var chrome=new HBox(title);chrome.getStyleClass().add("workspace-glass-header");
                var data=new TableView<String>();data.getColumns().add(new TableColumn<>("Synthetic data"));data.setPrefHeight(110);
                var content=new VBox(8,new Label("Opaque, readable working surface"),data);content.getStyleClass().add("workspace-sheet");
                var root=new VBox(16,chrome,content);root.setPadding(new Insets(24));
                var stage=new Stage();stage.setScene(new Scene(root,620,300));ThemeService.applyTheme(stage.getScene());stage.show();
                try {
                    root.applyCss();root.layout();
                    var pixels=root.snapshot(null,null);int center=(int)(chrome.getLayoutX()+chrome.getWidth()/2);
                    var top=pixels.getPixelReader().getColor(center,(int)Math.floor(chrome.getLayoutY()));
                    var bottom=pixels.getPixelReader().getColor(center,(int)Math.ceil(chrome.getLayoutY()+chrome.getHeight())-1);
                    capture(root,theme);
                    materialChecks.add(() -> assertTrue(brightness(top)-brightness(bottom)>.035,
                            "Floating material needs directional edge light rather than a flat translucent rectangle: "+theme+" top="+top+" bottom="+bottom));
                    assertEquals(1,((Color)content.getBackground().getFills().getLast().getFill()).getOpacity(),1e-6);
                    assertEquals(1,((Color)data.getBackground().getFills().getLast().getFill()).getOpacity(),1e-6);
                    assertNull(data.getEffect(),"The large virtualized work list must not acquire a blur/shadow");
                    assertEquals(1,title.getOpacity());assertEquals(1,root.getOpacity());assertEquals(1,stage.getOpacity());
                } finally { stage.close(); }
            }
            assertAll(materialChecks);
            return null;
        });
    }

    private static void capture(VBox root,ThemeService.Theme theme) throws Exception {
        String output=System.getProperty("vncode.material.capture.dir");if(output==null)return;
        Path directory=Path.of(output);Files.createDirectories(directory);WritableImage image=root.snapshot(null,null);
        var pixels=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)pixels.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        javax.imageio.ImageIO.write(pixels,"png",directory.resolve("material-"+theme.getKey()+".png").toFile());
    }
    private static double brightness(Color color){return (color.getRed()+color.getGreen()+color.getBlue())/3;}
    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        var task=new FutureTask<T>(action);Platform.runLater(task);return task.get(25,TimeUnit.SECONDS);
    }
}
