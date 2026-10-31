package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.ProductRow;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Crosses UI and real local queues; marketplace/Catalog identity readers are synthetic. */
class WbOzonSellerLoopbackIntegrationTest {
    @TempDir static Path root;
    @BeforeAll static void init(){System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException already){}}
    @AfterAll static void cleanup()throws Exception{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    private static <T>T fx(Callable<T> work)throws Exception{var f=new FutureTask<>(work);Platform.runLater(f);return f.get(20,TimeUnit.SECONDS);}
    private static void until(Callable<Boolean> condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<end){if(fx(condition))return;Thread.sleep(40);}fail("UI operation did not complete");
    }
    @Test void sellerReviewsRealLinkAndQueueWritesToLoopbackThenReadsBarcode()throws Exception{
        try(var h=new WbOzonUiIntegrationHarness()){
            WbOzonGtinLinkDialog.Review.Backend backend=new WbOzonGtinLinkDialog.Review.Backend(){
                public List<Shop> shops(){return List.of(h.wb);}
                public WbOzonGtinReviewService.Snapshot scan(Shop w,Shop o,boolean cached,Consumer<String> p)throws Exception{return h.scan(p);}
                public List<WbOzonGtinMatcher.Result> manual(WbOzonGtinReviewService.Snapshot s,List<ProductRow> r,String c){throw new AssertionError();}
                public WbOzonGtinReviewService.ApprovalReport approve(Shop w,Shop o,WbOzonGtinReviewService.Snapshot s,List<WbOzonGtinMatcher.Candidate> rows,Consumer<String> p)throws Exception{return h.approve(s,rows,p);}
                public List<WbOzonGtinDispatchService.Report> apply(Shop w,Shop o,List<WbOzonLinkRepository.Link> rows,Consumer<WbOzonGtinDispatchService.Report> p)throws Exception{return h.apply(rows,p);}
            };
            var stage=fx(()->{var view=new WbOzonGtinLinkDialog.Review(h.ozon,backend);var s=new Stage();s.setScene(new Scene(view,1100,700));s.show();return s;});
            try{
                until(()->!((Button)stage.getScene().lookup("#wbOzonScan")).isDisabled());
                fx(()->{((Button)stage.getScene().lookup("#wbOzonScan")).fire();return null;});
                until(()->((TableView<?>)stage.getScene().lookup("#wbOzonCandidates")).getItems().size()==1);
                fx(()->{((Button)stage.getScene().lookup("#wbOzonSelectSafe")).fire();return null;});
                assertEquals(1,fx(()->((TableView<?>)stage.getScene().lookup("#wbOzonCandidates")).getSelectionModel().getSelectedItems().size()));
                fx(()->{((WbOzonGtinLinkDialog.Review)stage.getScene().getRoot()).setConfirmation(x->false);((Button)stage.getScene().lookup("#wbOzonApply")).fire();return null;});
                assertEquals(0,h.writes.get());assertTrue(h.links.list(h.wb,h.ozon).isEmpty());
                fx(()->{((WbOzonGtinLinkDialog.Review)stage.getScene().getRoot()).setConfirmation(x->true);((Button)stage.getScene().lookup("#wbOzonApply")).fire();return null;});
                until(()->h.writes.get()==1&&!((Button)stage.getScene().lookup("#wbOzonScan")).isDisabled());
                assertEquals(1,h.writes.get());assertTrue(h.readsAfterWrite.get()>0,"barcode must be read after simulated add");
                assertEquals(1,h.links.list(h.wb,h.ozon).size());
                var key=new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(h.ozon.getId(),com.vncode.app.integration.marketplace.Marketplace.OZON,"501","offer-a");
                assertEquals("00000000000017",new GtinSyncRepository().verifiedMappings(h.ozon).get(key).gtin());
                fx(()->{var table=(TableView<?>)stage.getScene().lookup("#wbOzonCandidates");
                    @SuppressWarnings("unchecked") var state=(TableColumn<Object,String>)table.getColumns().getLast();
                    assertTrue(state.getCellData(table.getItems().getFirst()).contains(com.vncode.app.shared.I18nService.getInstance().tr("wb_ozon.result.verified")));
                    return null;});
            }finally{fx(()->{stage.close();return null;});}
        }
    }
}
