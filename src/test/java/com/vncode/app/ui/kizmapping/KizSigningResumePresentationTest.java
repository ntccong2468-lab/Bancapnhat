package com.vncode.app.ui.kizmapping;

import com.vncode.app.config.Database;
import com.vncode.app.features.kizmapping.KizMappingRepository;
import com.vncode.app.integration.znack.ZnackGtinInventorySummary;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Renders the actual FXML with synthetic persisted purchases, without a signer or seller API. */
class KizSigningResumePresentationTest {
    private static final String GTIN = "04601234567890";
    private static final String WAITING = "Waiting for the user to select this shop before signing.";
    @TempDir Path directory;
    private AppLanguage previousLanguage;

    @BeforeAll static void startFx() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        previousLanguage = I18nService.getInstance().getCurrentLanguage();
        sql("INSERT INTO shops(id,name,api_key) VALUES(1,'Synthetic shop','fixture')");
        sql("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'" + GTIN + "','Synthetic product','2026-11-14T00:00:00Z')");
        sql("INSERT INTO kiz_orders(shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,'" + GTIN + "',1,'CODES_DOWNLOADED','2026-11-14T00:00:00Z','2026-11-14T00:00:00Z')");
        sql("INSERT INTO znack_purchase_pipelines(shop_id,gtin,quantity,stage,order_id,error_message,created_at,updated_at) VALUES(1,'" + GTIN + "',1,'WAITING_INTRODUCTION_READINESS',1,'" + WAITING + "','2026-11-14T00:00:00Z','2026-11-14T00:00:00Z')");
    }

    @AfterEach void cleanup() throws Exception {
        fx(() -> {
            for (Window window : List.copyOf(Window.getWindows())) window.hide();
            I18nService.getInstance().setLanguage(previousLanguage);
            return null;
        });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        System.clearProperty("vncode.appdata.dir");
    }

    @ParameterizedTest @EnumSource(AppLanguage.class)
    void selectedShopHasAnExplicitResumeForItsAlreadyPurchasedPausedLot(AppLanguage language) throws Exception {
        var rows = new KizMappingRepository().findGtinSummaries(1);
        assertEquals(1, rows.size());
        assertEquals(0, rows.getFirst().available(), "Paused is not proof of legal circulation");
        fx(() -> {
            I18nService.getInstance().setLanguage(language);
            var loader = FxmlViewLoader.loader(KizMappingController.class, "kiz-mapping-view.fxml");
            Parent view = FxmlViewLoader.load(loader);
            KizMappingController controller = loader.getController();
            // Bind only the foreground identity; setShop would intentionally resume real services.
            var selected = KizMappingController.class.getDeclaredField("shop");
            selected.setAccessible(true);
            selected.set(controller, new Shop(1, "Synthetic shop", "fixture"));
            @SuppressWarnings("unchecked")
            var table = (TableView<ZnackGtinInventorySummary>) loader.getNamespace().get("gtinTable");
            @SuppressWarnings("unchecked")
            var actions = (TableColumn<ZnackGtinInventorySummary, ?>) loader.getNamespace().get("actionsColumn");
            @SuppressWarnings("unchecked")
            var pipeline = (TableColumn<ZnackGtinInventorySummary, String>) loader.getNamespace().get("pipelineColumn");
            table.getItems().setAll(rows);
            var stage = new Stage();
            stage.setScene(new Scene(view, 1450, 650));
            stage.show(); view.applyCss(); view.layout(); table.layout();
            var cell = table.lookupAll(".table-cell").stream()
                    .filter(TableCell.class::isInstance).map(TableCell.class::cast)
                    .filter(item -> item.getTableColumn() == actions && item.getIndex() == 0)
                    .findFirst().orElseThrow();
            var buttons = ((HBox) cell.getGraphic()).getChildren().stream()
                    .filter(Button.class::isInstance).map(Button.class::cast).toList();
            String resumeLabel = I18nService.getInstance().tr("kiz_mapping.action.resume_purchase");
            assertEquals(1, buttons.stream().filter(button -> button.isVisible()
                            && button.isManaged() && resumeLabel.equals(button.getAccessibleText())).count(),
                    "A paused purchase in the already-selected shop needs an explicit resume action");
            var buy = buttons.stream().filter(button -> I18nService.getInstance()
                    .tr("kiz_mapping.action.buy").equals(button.getAccessibleText())).findFirst().orElseThrow();
            assertTrue(buy.isDisabled(), "The recovery action must continue the paid lot instead of buying it again");
            assertFalse(buy.isVisible(), "Use the paused row's existing action space for recovery");
            assertFalse(buy.isManaged());
            var actionBounds = cell.localToScene(cell.getBoundsInLocal());
            for (Button button : buttons) if (button.isVisible() && button.isManaged()) {
                var bounds = button.localToScene(button.getBoundsInLocal());
                assertTrue(bounds.getMinX() >= actionBounds.getMinX() - 1
                                && bounds.getMaxX() <= actionBounds.getMaxX() + 1,
                        "Every visible action must fit within the row's action column");
            }
            assertEquals(I18nService.getInstance().tr("znack.status_value.signing_paused"),
                    pipeline.getCellObservableValue(rows.getFirst()).getValue());
            stage.hide();
            return null;
        });
        try (var c = Database.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM kiz_orders")) {
            assertTrue(r.next()); assertEquals(1, r.getInt(1));
        }
    }
    @Test void compactMappingKeepsAllFiveActionIconsInsideTheTableAndRetainsTheSameRows()throws Exception{
        var paused=new KizMappingRepository().findGtinSummaries(1).getFirst();
        var failed=new ZnackGtinInventorySummary("04601234567906","Synthetic long product name","Fixture",2,0,0,1,5,"CODES_DOWNLOADED",paused.latestPipelineId(),"INTRODUCTION_FAILED","ZNACK_REQUIRED_ATTRIBUTES_MISSING",paused.syncedAt());
        var previousTheme=com.vncode.app.shared.ThemeService.getCurrentTheme();
        var loaded=fx(()->{
            var loader=FxmlViewLoader.loader(KizMappingController.class,"kiz-mapping-view.fxml");Parent view=FxmlViewLoader.load(loader);
            var selected=KizMappingController.class.getDeclaredField("shop");selected.setAccessible(true);selected.set(loader.getController(),new Shop(1,"Synthetic shop","fixture"));
            @SuppressWarnings("unchecked") var table=(TableView<ZnackGtinInventorySummary>)loader.getNamespace().get("gtinTable");table.getItems().setAll(paused,failed);
            var sidebar=new javafx.scene.layout.Region();sidebar.setMinWidth(244);sidebar.setPrefWidth(244);sidebar.setMaxWidth(244);
            var shell=new javafx.scene.layout.BorderPane(view,null,null,null,sidebar);shell.setMinSize(0,0);var window=new Stage();window.setScene(new Scene(shell,1000,650));com.vncode.app.shared.ThemeService.applyTheme(window.getScene());window.show();
            return new Object[]{loader,table,window};
        });
        var loader=(javafx.fxml.FXMLLoader)loaded[0];@SuppressWarnings("unchecked") var table=(TableView<ZnackGtinInventorySummary>)loaded[1];var stage=(Stage)loaded[2];
        @SuppressWarnings("unchecked") var actions=(TableColumn<ZnackGtinInventorySummary,?>)loader.getNamespace().get("actionsColumn");
        try{
            for(var locale:List.of(AppLanguage.VI,AppLanguage.RU))for(var theme:com.vncode.app.shared.ThemeService.Theme.values()){
                fx(()->{I18nService.getInstance().setLanguage(locale);((KizMappingController)loader.getController()).applyTranslations();com.vncode.app.shared.ThemeService.setCurrentTheme(theme);return null;});
                for(int width:new int[]{1000,1600,1000}){
                    fx(()->{stage.setWidth(width);return null;});nativeLayoutPulse();
                    fx(()->{
                        var root=stage.getScene().getRoot();root.applyCss();root.layout();assertEquals(List.of(paused,failed),table.getItems());
                        var bounds=table.localToScene(table.getBoundsInLocal());int checked=0;
                        for(var node:table.lookupAll(".table-cell"))if(node instanceof TableCell<?,?> cell&&cell.getTableColumn()==actions&&!cell.isEmpty()&&cell.getIndex()==1){
                            var available=cell.localToScene(cell.getBoundsInLocal());assertTrue(available.getMinX()>=bounds.getMinX()-1&&available.getMaxX()<=bounds.getMaxX()+1,"Actions must remain inside the native table viewport");
                            assertInstanceOf(HBox.class,cell.getGraphic());int visible=0;
                            for(var child:((HBox)cell.getGraphic()).getChildren())if(child instanceof Button button&&button.isVisible()&&button.isManaged()){
                                visible++;var b=button.localToScene(button.getBoundsInLocal());assertTrue(b.getMinX()>=available.getMinX()-1&&b.getMaxX()<=available.getMaxX()+1,"Every action icon must fit its cell");assertFalse(button.getAccessibleText().isBlank());assertNotNull(button.getOnAction());
                            }assertEquals(5,visible,"The existing retry, mapping, export, archive and buy actions cannot disappear");checked++;
                        }
                        assertEquals(1,checked);var mapping=(TableColumn<?,?>)loader.getNamespace().get("mappingColumn");assertEquals(width>1200,mapping.isVisible());
                        assertTrue(table.getHeight()>=stage.getScene().getHeight()*.55,"Wrapping toolbar controls must keep the list as the primary work area");return null;
                    });
                }
            }
        }finally{fx(()->{stage.close();com.vncode.app.shared.ThemeService.setCurrentTheme(previousTheme);return null;});}
        try(var connection=Database.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM kiz_orders")){assertTrue(rows.next());assertEquals(1,rows.getInt(1),"Presentation cannot enqueue another purchase");}
    }
    private static void nativeLayoutPulse()throws Exception{
        var complete=new java.util.concurrent.CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==2){stop();complete.complete(null);}}}.start();return null;});complete.get(8,TimeUnit.SECONDS);
    }

    private void sql(String sql) throws Exception {
        try (var c = Database.getConnection(); var s = c.createStatement()) { s.executeUpdate(sql); }
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        var future = new FutureTask<>(action);
        Platform.runLater(future);
        return future.get(20, TimeUnit.SECONDS);
    }
}
