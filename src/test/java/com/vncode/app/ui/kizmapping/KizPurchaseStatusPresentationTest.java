package com.vncode.app.ui.kizmapping;

import com.vncode.app.config.Database;
import com.vncode.app.features.kizmapping.KizMappingRepository;
import com.vncode.app.integration.znack.ZnackGtinInventorySummary;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import com.vncode.app.ui.znack.ZnackAutomationController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real FXML/table rendering and disposable SQLite; no seller APIs or signing. */
class KizPurchaseStatusPresentationTest {
    @TempDir Path root;
    private AppLanguage previousLanguage;
    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        previousLanguage=I18nService.getInstance().getCurrentLanguage();
        sql("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture','fixture'),(2,'Other fixture','other-fixture')");
    }
    @AfterEach void cleanup()throws Exception{
        fx(()->{for(var window:List.copyOf(Window.getWindows()))window.hide();I18nService.getInstance().setLanguage(previousLanguage);return null;});
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }

    @ParameterizedTest @EnumSource(AppLanguage.class)
    void downloadedCodesAreNotPresentedAsConfirmedCirculationAndOnlyLegalStockIsAvailable(AppLanguage language)throws Exception{
        String complete="04600000000011",introduced="04600000000028",failed="04600000000035",introFailed="04600000000042";
        product(1,complete);product(1,introduced);product(1,failed);product(1,introFailed);product(2,introduced);
        pipeline(complete,"COMPLETED");pipeline(introduced,"INTRODUCED");pipeline(failed,"FAILED");pipeline(introFailed,"INTRODUCTION_FAILED");
        code(1,complete,"downloaded","AVAILABLE","RECEIVED");
        code(1,introduced,"available","AVAILABLE","IN_CIRCULATION");
        code(1,introduced,"reserved","RESERVED","IN_CIRCULATION");
        code(1,introduced,"consumed","CONSUMED","IN_CIRCULATION");
        code(2,introduced,"other-shop","AVAILABLE","IN_CIRCULATION");
        var summaries=new KizMappingRepository().findGtinSummaries(1);
        assertEquals(0,summaries.stream().filter(s->s.gtin().equals(complete)).findFirst().orElseThrow().available());
        assertEquals(1,summaries.stream().filter(s->s.gtin().equals(introduced)).findFirst().orElseThrow().available());
        fx(()->{
            I18nService.getInstance().setLanguage(language);
            var loader=FxmlViewLoader.loader(KizMappingController.class,"kiz-mapping-view.fxml");Parent view=FxmlViewLoader.load(loader);
            @SuppressWarnings("unchecked") var table=(TableView<ZnackGtinInventorySummary>)loader.getNamespace().get("gtinTable");
            @SuppressWarnings("unchecked") var column=(TableColumn<ZnackGtinInventorySummary,String>)loader.getNamespace().get("pipelineColumn");
            table.getItems().setAll(summaries);var stage=new Stage();stage.setScene(new Scene(view,1400,650));stage.show();view.applyCss();view.layout();table.layout();
            var downloaded=chip(table,column,index(summaries,complete));
            assertEquals(completedLabel(language),downloaded.getText());
            assertTrue(downloaded.getStyleClass().contains("badge-warning"));assertFalse(downloaded.getStyleClass().contains("badge-green"));
            assertTrue(downloaded.isWrapText());assertNotNull(downloaded.getTooltip());assertEquals(downloaded.getText(),downloaded.getTooltip().getText());
            assertTrue(downloaded.getMaxWidth()>0&&downloaded.getMaxWidth()<=column.getWidth());
            var confirmed=chip(table,column,index(summaries,introduced));assertEquals(I18nService.getInstance().tr("znack.status_value.introduced"),confirmed.getText());
            assertTrue(confirmed.getStyleClass().contains("badge-green"));assertFalse(confirmed.getStyleClass().contains("badge-warning"));
            for(String gtin:List.of(failed,introFailed))assertTrue(chip(table,column,index(summaries,gtin)).getStyleClass().contains("badge-red"));
            stage.hide();return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void savedAutoIntroductionChoiceIsVisibleAndLoadingOrEditingDoesNotSaveConsent(boolean enabled)throws Exception{
        var repository=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture"));repository.saveSettings(ZnackModels.Settings.empty().withAutoIntroduction(enabled));
        sql("UPDATE znack_settings SET updated_at='2020-01-01T00:00:00Z' WHERE shop_id=1");
        var before=repository.getSettings();
        fx(()->{
            var loader=FxmlViewLoader.loader(ZnackAutomationController.class,"znack-automation-view.fxml");Parent view=FxmlViewLoader.load(loader);
            var controller=(ZnackAutomationController)loader.getController();controller.setShop(new Shop(1,"Fixture","fixture"));
            var settings=(Tab)loader.getNamespace().get("settingsTab");settings.getTabPane().getSelectionModel().select(settings);
            var checkbox=(CheckBox)loader.getNamespace().get("autoIntroductionCheck");
            var menu=((Label)loader.getNamespace().get("titleLabel")).getContextMenu();
            var menuChoice=menu.getItems().stream().filter(CheckMenuItem.class::isInstance).map(CheckMenuItem.class::cast).findFirst().orElseThrow();
            assertTrue(checkbox.isVisible(),"The seller must not need a hidden title context menu to find circulation settings");assertTrue(checkbox.isManaged());
            assertEquals(enabled,checkbox.isSelected(),"Loading must preserve the saved choice instead of enabling circulation");assertTrue(checkbox.isDisabled());
            assertTrue(menuChoice.isDisable(),"The old menu entry must follow the same read-only mode as the visible checkbox");assertEquals(enabled,menuChoice.isSelected());
            var scope=(Label)loader.getNamespace().get("autoIntroductionScopeLabel");
            for(var language:List.of(AppLanguage.VI,AppLanguage.EN,AppLanguage.VI)){
                I18nService.getInstance().setLanguage(language);controller.applyTranslations();
                assertEquals(I18nService.getInstance().tr("znack.auto_introduction_scope"),scope.getText());
                assertEquals(enabled,checkbox.isSelected());assertEquals(enabled,menuChoice.isSelected());assertTrue(menuChoice.isDisable());
                assertEquals(before,repository.getSettings(),"Changing display language must not change saved consent");
            }
            ((Button)loader.getNamespace().get("editSettingsButton")).fire();assertFalse(checkbox.isDisabled());assertFalse(menuChoice.isDisable());checkbox.fire();assertEquals(!enabled,checkbox.isSelected());assertEquals(!enabled,menuChoice.isSelected());
            assertEquals(before,repository.getSettings(),"Editing the choice must not submit or save it automatically");
            ((Button)loader.getNamespace().get("editSettingsButton")).fire();assertEquals(enabled,checkbox.isSelected());assertTrue(menuChoice.isDisable());assertEquals(enabled,menuChoice.isSelected());
            assertEquals(before,repository.getSettings());controller.setShop(null);return null;
        });
        try(var c=Database.getConnection();var p=c.createStatement();var r=p.executeQuery("SELECT updated_at FROM znack_settings WHERE shop_id=1")){
            assertTrue(r.next());assertEquals("2020-01-01T00:00:00Z",r.getString(1));
        }
    }

    private static String completedLabel(AppLanguage language){return switch(language){case VI->"Đã tải mã · chưa xác nhận lưu thông";case EN->"Codes downloaded · circulation not confirmed";case RU->"Коды загружены · ввод в оборот не подтверждён";case ZH->"已下载码 · 尚未确认流通";};}
    private static int index(List<ZnackGtinInventorySummary> rows,String gtin){for(int n=0;n<rows.size();n++)if(rows.get(n).gtin().equals(gtin))return n;throw new AssertionError(gtin);}
    private static Label chip(TableView<?> table,TableColumn<?,?> column,int index){
        return table.lookupAll(".table-cell").stream().filter(TableCell.class::isInstance).map(TableCell.class::cast)
                .filter(cell->cell.getTableColumn()==column&&cell.getIndex()==index).map(TableCell::getGraphic).filter(Label.class::isInstance).map(Label.class::cast).findFirst().orElseThrow();
    }
    private void product(int shop,String gtin)throws Exception{
        sql("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES("+shop+",'"+gtin+"','Fixture','2026-11-11T00:00:00Z')");
        sql("INSERT INTO kiz_orders(shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES("+shop+",'"+gtin+"',4,'CODES_DOWNLOADED','2026-11-11T00:00:00Z','2026-11-11T00:00:00Z')");
    }
    private void pipeline(String gtin,String stage)throws Exception{sql("INSERT INTO znack_purchase_pipelines(shop_id,gtin,quantity,stage,order_id,created_at,updated_at) VALUES(1,'"+gtin+"',4,'"+stage+"',(SELECT id FROM kiz_orders WHERE shop_id=1 AND gtin='"+gtin+"'),'2026-11-11T00:00:00Z','2026-11-11T00:00:00Z')");}
    private void code(int shop,String gtin,String raw,String state,String legal)throws Exception{sql("INSERT INTO kiz_codes(shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES("+shop+",(SELECT id FROM kiz_orders WHERE shop_id="+shop+" AND gtin='"+gtin+"'),'"+raw+"','"+raw+"','"+gtin+"','"+state+"','"+legal+"','2026-11-11T00:00:00Z','2026-11-11T00:00:00Z')");}
    private void sql(String sql)throws Exception{try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate(sql);}}
    private static <T>T fx(Callable<T> action)throws Exception{var task=new FutureTask<>(action);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
}
