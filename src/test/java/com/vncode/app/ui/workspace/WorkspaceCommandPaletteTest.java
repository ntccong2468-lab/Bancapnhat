package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Native keyboard flow over existing callbacks only; no HomeController/API/license setup. */
class WorkspaceCommandPaletteTest {
    @TempDir Path profile;
    private WorkspaceCommandPalette palette;
    private Stage owner;
    @BeforeAll static void toolkit() {
        try {Platform.startup(()->Platform.setImplicitExit(false));}
        catch(IllegalStateException started) { }
    }
    private static <T>T fx(Callable<T> work)throws Exception {
        var task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);
    }
    private static void pulse()throws Exception {
        var complete=new CompletableFuture<Void>();
        fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==3){stop();complete.complete(null);}}}.start();return null;});
        complete.get(5,TimeUnit.SECONDS);
    }
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        fx(()->{ThemeService.initializeAppearance();palette=new WorkspaceCommandPalette();owner=new Stage();
            owner.setScene(new Scene(new StackPane(new Label("Synthetic callback host")),900,600));
            ThemeService.applyTheme(owner.getScene());owner.show();return null;});
    }
    @AfterEach void cleanup()throws Exception {
        fx(()->{palette.close();owner.close();return null;});System.clearProperty("vncode.appdata.dir");
    }
    @Test void typedSearchFiltersCaseInsensitivelyAndEnterRunsOnlyTheSelectedExistingCallback()throws Exception {
        var calls=new ArrayList<String>();var products=new Label("Synthetic products route");
        var host=new StackPane();var navigator=new WorkspaceNavigator(node -> host.getChildren().setAll(node));
        navigator.register("products",()->products);
        var actions=List.of(new WorkspaceCommandPalette.Action("Sản phẩm · SKU",()->{calls.add("products");navigator.show("products");}),
                new WorkspaceCommandPalette.Action("Đăng ký · GTIN",()->calls.add("gtin")),
                new WorkspaceCommandPalette.Action("Cài đặt",()->calls.add("settings")));
        fx(()->{palette.show(owner,actions);return null;});pulse();
        var query=fx(WorkspaceCommandPaletteTest::query);
        var paletteWindow=fx(()->query.getScene().getWindow());
        for(String theme:List.of("light","dark")) {
            fx(()->{ThemeService.switchTheme(theme);return null;});pulse();
            fx(()->{com.vncode.app.testsupport.UiEvidence.capture(query.getScene(),"native-command-palette-"+theme);return null;});
        }
        fx(()->{
            assertSame(query,query.getScene().getFocusOwner(),"Opening places native keyboard focus in search");
            assertEquals(3,results(query).size());
            type(query,"sKu");assertEquals("sKu",query.getText(),"Typed key events reach the native text field");
            var filtered=results(query);assertEquals(1,filtered.size());assertEquals("Sản phẩm · SKU",filtered.getFirst().getText());
            assertTrue(calls.isEmpty(),"Filtering does not execute a command");
            com.vncode.app.testsupport.UiEvidence.capture(query.getScene(),"native-command-palette-filtered-dark");
            enter(query);return null;
        });pulse();
        fx(()->{
            assertEquals(List.of("products"),calls,"Enter invokes exactly the existing filtered callback");
            assertEquals("products",navigator.currentRoute());assertSame(products,host.getChildren().getFirst());
            assertFalse(paletteWindow.isShowing(),"Invocation dismisses the palette");return null;
        });
    }
    @Test void emptySearchAndCancelCannotExecuteAnActionAndRepeatedOpenKeepsOneDialog()throws Exception {
        var calls=new ArrayList<String>();
        var actions=List.of(new WorkspaceCommandPalette.Action("Synthetic products",()->calls.add("products")));
        fx(()->{palette.show(owner,actions);return null;});pulse();
        var first=fx(WorkspaceCommandPaletteTest::query);
        var paletteWindow=fx(()->first.getScene().getWindow());
        fx(()->{
            type(first,"unknown");assertTrue(results(first).isEmpty());enter(first);
            assertTrue(first.getScene().getWindow().isShowing(),"No match keeps search open");
            palette.show(owner,List.of(new WorkspaceCommandPalette.Action("Unexpected replacement",()->calls.add("replacement"))));
            assertSame(first,query(),"Repeated opening reuses the visible palette");
            assertEquals("unknown",first.getText());assertTrue(calls.isEmpty());
            var pane=(DialogPane)first.getScene().getRoot();((Button)pane.lookupButton(ButtonType.CANCEL)).fire();return null;
        });pulse();
        fx(()->{assertTrue(calls.isEmpty(),"Cancel must not invoke a command");assertFalse(paletteWindow.isShowing());
            assertEquals("",first.getText(),"Closing clears the previous query");return null;});
    }
    private static TextField query() {
        return Window.getWindows().stream().filter(w->w.isShowing()&&w.getScene()!=null)
                .map(w->w.getScene().getRoot().lookup("#workspaceCommandQuery"))
                .filter(TextField.class::isInstance).map(TextField.class::cast).findFirst().orElseThrow();
    }
    private static List<Button> results(TextField query) {
        return query.getScene().getRoot().lookupAll(".workspace-command-result").stream()
                .filter(Button.class::isInstance).map(Button.class::cast).toList();
    }
    private static void type(TextField query,String text) {
        for(char letter:text.toCharArray())query.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED,Character.toString(letter),"",KeyCode.UNDEFINED,false,false,false,false));
    }
    private static void enter(TextField query) {
        query.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ENTER,false,false,false,false));
        query.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED,"","",KeyCode.ENTER,false,false,false,false));
    }
}
