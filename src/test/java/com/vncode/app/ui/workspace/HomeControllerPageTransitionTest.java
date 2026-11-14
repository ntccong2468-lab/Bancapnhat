package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.shared.NavigationContext;
import com.vncode.app.shared.ThemeService;
import com.vncode.app.shared.WorkspaceMotion;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real Home route host with synthetic nodes; no startup, license or seller calls. */
class HomeControllerPageTransitionTest {
    @TempDir Path profile;
    private String previousData;
    private boolean previousReduced;

    @BeforeAll static void start() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }
    private static <T> T fx(Callable<T> work) throws Exception {
        var task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);
    }
    @BeforeEach void initialize() throws Exception {
        previousData=System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        previousReduced=fx(()->{ThemeService.initializeAppearance();boolean prior=ThemeService.isReducedMotion();ThemeService.setReducedMotion(false);return prior;});
    }
    @AfterEach void restore() throws Exception {
        fx(()->{WorkspaceMotion.cancelAll();ThemeService.setReducedMotion(previousReduced);return null;});
        if(previousData==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previousData);
    }
    private static void show(HomeController home,javafx.scene.Node view,NavigationContext context)throws Exception {
        var method=HomeController.class.getDeclaredMethod("displayDynamicContent",javafx.scene.Node.class,NavigationContext.class);
        method.setAccessible(true);method.invoke(home,view,context);
    }

    @Test void changingRoutesKeepsAnOutgoingVisualWhileTheNewPageIsAlreadyInteractive()throws Exception {
        fx(()->{
            var home=new HomeController();home.dynamicContentContainer=new StackPane();
            var header=new StackPane();header.setPrefHeight(40);
            var shell=new BorderPane(home.dynamicContentContainer,header,null,null,null);
            var first=new VBox(new TextField("retained draft"));first.setStyle("-fx-background-color: #c02020");
            var action=new Button("Synthetic action");var calls=new AtomicInteger();action.setOnAction(event->calls.incrementAndGet());
            var second=new VBox(action);second.setStyle("-fx-background-color: #2030c0");
            var stage=new Stage();stage.setScene(new Scene(shell,500,350));
            try {
                stage.show();show(home,first,NavigationContext.SIBLING);
                shell.applyCss();shell.layout();
                show(home,second,NavigationContext.FORWARD);
                assertEquals(2,home.dynamicContentContainer.getChildren().size(),"Outgoing visible content must leave with the incoming route rather than disappearing at once");
                var departure=home.dynamicContentContainer.getChildren().stream().filter(ImageView.class::isInstance).map(ImageView.class::cast).findFirst().orElseThrow();
                assertNotNull(departure.getImage());assertTrue(departure.isMouseTransparent());assertFalse(departure.isManaged());
                assertTrue(departure.getImage().getWidth()>0);assertTrue(departure.getImage().getHeight()>0);
                action.fire();assertEquals(1,calls.get(),"Business work is available during the visual transition");
                assertEquals(1,header.getOpacity());assertEquals(0,header.getTranslateX());assertTrue(header.getTransforms().isEmpty());
                WorkspaceMotion.cancelAll();
                assertEquals(1,home.dynamicContentContainer.getChildren().size(),"The exit bitmap must be released at completion");
                show(home,first,NavigationContext.BACK);
                assertEquals("retained draft",((TextField)first.getChildren().getFirst()).getText());
                WorkspaceMotion.cancelAll();assertEquals(1,home.dynamicContentContainer.getChildren().size());
            } finally { WorkspaceMotion.cancelAll();stage.close(); }
            return null;
        });
    }
}
