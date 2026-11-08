package com.vncode.app.ui.kizmapping;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class KizMappingIssuesDialogTest {
    @TempDir Path directory;
    AppLanguage prior;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void init(){System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();prior=I18nService.getInstance().getCurrentLanguage();}
    @AfterEach void clean()throws Exception{fx(()->{for(var window:List.copyOf(Window.getWindows()))window.hide();I18nService.getInstance().setLanguage(prior);return null;});System.clearProperty("vncode.appdata.dir");}
    @ParameterizedTest @EnumSource(AppLanguage.class)
    void reportShowsBothColorsAndSeparatesMissingExpiredAndUnresolvedPaper(AppLanguage language)throws Exception{
        fx(()->{
            var i18n=I18nService.getInstance();i18n.setLanguage(language);
            var loader=new FXMLLoader(getClass().getResource("/com/vncode/app/ui/kizmapping/kiz-mapping-view.fxml"),i18n.getBundle());
            Parent root=loader.load();var stage=new Stage();stage.setScene(new Scene(root,1100,700));stage.show();
            var controller=(KizMappingController)loader.getController();var shopField=KizMappingController.class.getDeclaredField("shop");shopField.setAccessible(true);shopField.set(controller,new Shop(1,"Synthetic WB report",Marketplace.WILDBERRIES,"","synthetic"));
            var issueType=Class.forName(KizMappingController.class.getName()+"$MappingIssue");var constructor=issueType.getDeclaredConstructor(String.class,String.class,String.class,String.class,String.class,String.class,String.class);constructor.setAccessible(true);
            var issues=new ArrayList<>();var reasons=List.of("GTIN_COLOR_MISMATCH","GTIN_DOCUMENT_MISSING","GTIN_DOCUMENT_EXPIRED","REGISTRY_DOCUMENTS_PENDING","REGISTRY_DOCUMENTS_UNAVAILABLE");
            for(String reason:reasons)issues.add(constructor.newInstance("SYNTHETIC","XL","","",reason,"Black","White"));
            var reportType=Class.forName(KizMappingController.class.getName()+"$MappingReport");var reportConstructor=reportType.getDeclaredConstructor(int.class,int.class,List.class,int.class);reportConstructor.setAccessible(true);
            var show=KizMappingController.class.getDeclaredMethod("showMappingIssues",reportType);show.setAccessible(true);show.invoke(controller,reportConstructor.newInstance(0,0,issues,0));
            var pane=Window.getWindows().stream().map(w->w.getScene().getRoot()).filter(p->p.lookup("#mappingIssueTable")!=null).findFirst().orElseThrow();
            var table=(TableView<?>)pane.lookup("#mappingIssueTable");assertEquals(5,table.getItems().size());
            var reasonColumn=table.getColumns().getLast();var displayed=new ArrayList<String>();
            for(int index=0;index<reasons.size();index++){
                String text=Objects.toString(reasonColumn.getCellObservableValue(index).getValue());displayed.add(text);
                assertTrue(text.startsWith(i18n.tr("mapping.reason."+reasons.get(index))),text);assertFalse(text.contains("[VN_LOCAL]"));assertFalse(text.contains("mapping.reason."));
            }
            assertTrue(displayed.getFirst().contains("Black"));assertTrue(displayed.getFirst().contains("White"));assertEquals(5,new HashSet<>(displayed).size());
            assertTrue(stage.isShowing());return null;
        });
    }
    private static <T>T fx(Callable<T> callable)throws Exception{var work=new FutureTask<>(callable);Platform.runLater(work);return work.get(15,TimeUnit.SECONDS);}
}
