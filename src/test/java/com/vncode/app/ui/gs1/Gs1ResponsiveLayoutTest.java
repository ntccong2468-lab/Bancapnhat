package com.vncode.app.ui.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.features.gs1.Gs1Repository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real native layout with a synthetic sidebar and local GS1 letters; never contacts an upstream. */
class Gs1ResponsiveLayoutTest {
    @TempDir Path directory;
    private Gs1Pane pane;
    private BorderPane shell;
    private Stage stage;
    private AppLanguage previousLanguage;
    private static final String INN="7707083893";

    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();previousLanguage=I18nService.getInstance().getCurrentLanguage();}
    @AfterEach void cleanup()throws Exception{
        fx(()->{if(pane!=null)pane.dispose();if(stage!=null)stage.close();I18nService.getInstance().setLanguage(previousLanguage);return null;});
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }

    @ParameterizedTest @CsvSource({"VI,DARK","VI,LIGHT","RU,DARK","RU,LIGHT"})
    void compactAccountUsesTheCenterHeightAndKeepsTheEmptyInboxAfterTheChecks(AppLanguage language,ThemeService.Theme theme)throws Exception{
        mount(language,theme);
        fx(()->{
            var split=split();var scroll=scroll();
            assertEquals(Orientation.VERTICAL,split.getOrientation());assertEquals(1,split.getItems().size());assertSame(scroll,split.getItems().getFirst());
            assertTrue(scroll.getViewportBounds().getHeight()>pane.getHeight()*.65,
                    "The account must use the short window's center, rather than a fraction above an empty inbox");
            var content=(VBox)scroll.getContent();assertEquals("gs1CompactAccountContent",content.getId());
            var account=pane.lookup("#gs1AccountPanel");var inbox=pane.lookup("#gs1InboxPanel");
            assertSame(account,content.getChildren().getFirst());assertSame(inbox,content.getChildren().getLast());
            assertTrue(inbox.getBoundsInParent().getMinY()>=account.getBoundsInParent().getMaxY());
            assertEquals(6,((javafx.scene.layout.GridPane)pane.lookup("#gs1ReadinessGrid")).getChildren().size());
            var actions=(MenuButton)pane.lookup("#gs1AccountActions");assertTrue(actions.isVisible()&&actions.isManaged()&&actions.isFocusTraversable());
            assertWithin(actions,shell);assertWithin(pane.lookup("#gs1CheckAllButton"),shell);
            assertEquals(0,pane.activeTaskCount(),"Layout cannot authenticate or sign");return null;
        });
    }

    @Test void resizingKeepsTheSelectedLetterAndItsThreadWhileRestoringTheWideWorkspace()throws Exception{
        var request=new Gs1Repository().createRequest(INN,"RENEWAL","mail@example.invalid","Synthetic renewal","Synthetic reviewed letter body");
        mount(AppLanguage.VI,ThemeService.Theme.DARK);
        fx(()->{
            requests().getItems().setAll(request);requests().getSelectionModel().selectFirst();render();layout();return null;
        });
        awaitThread(request.subject());
        final Node[] original=new Node[3];
        fx(()->{
            original[0]=requests();original[1]=pane.lookup("#gs1InboxPanel");original[2]=pane.lookup("#gs1Conversation");
            assertSame(request,requests().getSelectionModel().getSelectedItem());
            scroll().setVvalue(1);layout();
            Bounds viewport=sceneBounds(scroll().lookup(".viewport")),inbox=sceneBounds(original[1]);
            assertTrue(inbox.getMaxY()<=viewport.getMaxY()+2,"The end of the continuous scroll must reveal the letter panel");
            return null;
        });
        resize(1366);
        fx(()->{
            assertEquals(Orientation.HORIZONTAL,split().getOrientation());assertEquals(2,split().getItems().size());
            assertSame(request,requests().getSelectionModel().getSelectedItem());assertSame(original[0],requests());assertSame(original[2],pane.lookup("#gs1Conversation"));
            assertTrue(hasThread(request.subject()));return null;
        });
        resize(1000);
        fx(()->{
            assertEquals(1,split().getItems().size());assertSame(request,requests().getSelectionModel().getSelectedItem());assertSame(original[1],pane.lookup("#gs1InboxPanel"));assertTrue(hasThread(request.subject()));
            return null;
        });
    }
    private void resize(double width)throws Exception{fx(()->{stage.setWidth(width);return null;});pulse();}
    private void pulse()throws Exception{
        var settled=new java.util.concurrent.CompletableFuture<Void>();
        fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames>=3){stop();settled.complete(null);}}}.start();return null;});
        settled.get(5,TimeUnit.SECONDS);fx(()->{layout();return null;});
    }

    private void mount(AppLanguage language,ThemeService.Theme theme)throws Exception{
        fx(()->{
            I18nService.getInstance().setLanguage(language);ThemeService.initializeAppearance();ThemeService.setCurrentTheme(theme);
            pane=new Gs1Pane();field("shop",new Shop(1,"Synthetic shop","fixture"));field("inn",INN);field("environment","production");render();
            var sidebar=new Region();sidebar.setMinWidth(220);sidebar.setPrefWidth(220);sidebar.setMaxWidth(220);
            shell=new BorderPane(pane,null,null,null,sidebar);stage=new Stage();stage.setScene(new Scene(shell,1000,650));ThemeService.applyTheme(stage.getScene());stage.show();layout();return null;
        });pulse();
    }
    private void layout(){for(int pass=0;pass<3;pass++){shell.applyCss();shell.layout();}}
    private void render()throws Exception{var method=Gs1Pane.class.getDeclaredMethod("render");method.setAccessible(true);method.invoke(pane);}
    private void field(String name,Object value)throws Exception{var field=Gs1Pane.class.getDeclaredField(name);field.setAccessible(true);field.set(pane,value);}
    private SplitPane split(){return (SplitPane)pane.lookup("#gs1WorkspaceSplit");}
    private ScrollPane scroll(){return (ScrollPane)pane.lookup("#gs1AccountScroll");}
    @SuppressWarnings("unchecked") private ListView<Gs1Repository.Request> requests()throws Exception{var field=Gs1Pane.class.getDeclaredField("requests");field.setAccessible(true);return (ListView<Gs1Repository.Request>)field.get(pane);}
    private boolean hasThread(String subject){return pane.lookup("#gs1Conversation") instanceof ScrollPane scroll&&scroll.getContent().lookupAll(".label").stream().anyMatch(node->node instanceof Label label&&subject.equals(label.getText()));}
    private void awaitThread(String subject)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<deadline){if(fx(()->hasThread(subject)))return;Thread.sleep(10);}fail("The synthetic letter thread did not load");}
    private static Bounds sceneBounds(Node node){return node.localToScene(node.getBoundsInLocal());}
    private static void assertWithin(Node node,Node container){var bounds=sceneBounds(node);var area=sceneBounds(container);assertTrue(bounds.getMinX()>=area.getMinX()-1&&bounds.getMaxX()<=area.getMaxX()+1,"Header command is outside the available width");}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
}
