package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.features.jobs.*;
import com.vncode.app.features.sync.*;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class JobCenterDialogTest {
    @TempDir Path root;Stage owner;Shop shop;String run;SyncRunRepository.Item itemToChange;DialogPane dialog;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> f)throws Exception{var t=new FutureTask<>(f);Platform.runLater(t);return t.get(10,TimeUnit.SECONDS);}
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        var repo=new SyncRunRepository();run=repo.begin(shop,"gs1","MATCHING");for(int n=0;n<310;n++){var id=repo.add(shop,run,"product-"+n,"variant-"+n,"sku-"+n,"article-"+n,"Black","M",GtinWriteTestFixture.GTIN,"QUEUED","","{}");if(n==10)itemToChange=id;}
        var report=new JobReportRepository().list().stream().filter(r->r.id().equals(run)).findFirst().orElseThrow();
        fx(()->{owner=new Stage();owner.setScene(new Scene(new StackPane(),920,600));owner.show();JobCenterDialog.detail(owner,report);dialog=(DialogPane)Window.getWindows().stream().filter(w->w!=owner&&w.getScene()!=null&&w.getScene().getRoot().getId()!=null&&w.getScene().getRoot().getId().equals("jobDetailDialog")).findFirst().orElseThrow().getScene().getRoot();return null;});
        waitFor(()->table().getItems().size()==300);
    }
    @SuppressWarnings("unchecked") TableView<JobReportRepository.Line> table(){return (TableView<JobReportRepository.Line>)dialog.lookup("#jobReportItems");}
    Button button(String id){return (Button)dialog.lookup("#"+id);}
    void waitFor(Callable<Boolean> condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<until){if(fx(condition))return;Thread.sleep(25);}fail("Report did not reach expected state");}
    @AfterEach void cleanup()throws Exception{
        fx(()->{
            // Close the Dialog itself so its onHidden handler stops periodic report reads.
            if(dialog!=null)((Button)dialog.lookupButton(ButtonType.CLOSE)).fire();
            for(var window:List.copyOf(Window.getWindows()))if(window instanceof Stage stage&&stage.getOwner()==owner)stage.close();
            owner.close();return null;
        });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        System.clearProperty("vncode.appdata.dir");
    }
    @Test void refreshingPreservesSelectedProductAndUpdatesItsError()throws Exception{
        fx(()->{var row=table().getItems().stream().filter(v->v.product().equals("product-10")).findFirst().orElseThrow();table().getSelectionModel().select(row);return null;});
        new SyncRunRepository().update(shop,itemToChange,"NEEDS_ACTION","READBACK","OZON_SKU_CHANGED",null);
        fx(()->{button("jobDetailRefresh").fire();return null;});
        waitFor(()->table().getSelectionModel().getSelectedItem()!=null&&table().getSelectionModel().getSelectedItem().error().equals("OZON_SKU_CHANGED"));
        fx(()->{assertEquals("product-10",table().getSelectionModel().getSelectedItem().product());assertTrue(((TextArea)dialog.lookup("#jobSelectedDetail")).getText().contains("OZON_SKU_CHANGED"));assertTrue(dialog.getScene().getWindow().getWidth()<=owner.getWidth()+1);return null;});
    }
    @Test void nextPageAndRefreshDoNotReturnToTheFirstThreeHundredRows()throws Exception{
        fx(()->{button("jobNextPage").fire();return null;});waitFor(()->table().getItems().size()==10);
        String product=fx(()->table().getItems().getFirst().product());
        fx(()->{button("jobDetailRefresh").fire();return null;});waitFor(()->!button("jobDetailRefresh").isDisabled());
        fx(()->{assertEquals(10,table().getItems().size());assertEquals(product,table().getItems().getFirst().product());assertTrue(button("jobNextPage").isDisabled());button("jobPreviousPage").fire();return null;});waitFor(()->table().getItems().size()==300);
    }
}
