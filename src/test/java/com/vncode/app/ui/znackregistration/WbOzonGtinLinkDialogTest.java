package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.gtinsync.WbOzonGtinDispatchService;
import com.vncode.app.features.gtinsync.WbOzonGtinMatcher;
import com.vncode.app.features.gtinsync.WbOzonGtinReviewService;
import com.vncode.app.features.gtinsync.WbOzonLinkRepository;
import com.vncode.app.features.products.DocumentState;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.products.ProductRow;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.I18nService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WbOzonGtinLinkDialogTest {
    @TempDir static java.nio.file.Path appData;
    @BeforeAll static void toolkit(){System.setProperty("vncode.appdata.dir",appData.toString());com.vncode.app.config.Database.initDatabase();try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException alreadyStarted){}}
    @AfterAll static void cleanup()throws Exception{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    private static <T>T fx(Callable<T> action)throws Exception {var task=new FutureTask<>(action);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    private static void until(Callable<Boolean> condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(fx(condition))return;Thread.sleep(40);}fail("Timed out waiting for UI");}
    @Test void sellerCancellationDoesNotApproveOrWriteAndEachOutcomeRemainsDistinct()throws Exception {
        var wb=new Shop(1,"WB",Marketplace.WILDBERRIES,null,"wb-token");var ozon=new Shop(2,"Ozon",Marketplace.OZON,"123","ozon-token");
        var one=candidate(wb,ozon,"101","201","offer-a","4006381333931");var two=candidate(wb,ozon,"102","202","offer-b","4006381333931");
        var snapshot=new WbOzonGtinReviewService.Snapshot(List.of(one.wb(),two.wb()),List.of(new WbOzonGtinMatcher.Result(one.ozon(),List.of(one),""),new WbOzonGtinMatcher.Result(two.ozon(),List.of(two),"")),List.of(),List.of(one.catalog()),Map.of(),List.of(),"wb-owner","oz-owner","wb-source","oz-source",1,2,Instant.now());
        var approvals=new AtomicInteger();var writes=new AtomicInteger();
        var link=new WbOzonLinkRepository.Link(1,"101","11","wb-owner",2,"201","offer-a","77","oz-owner",one.catalog().gtin(),12,"wb-source","oz-source","wbr","ozr","EXACT_ARTICLE","digest",Instant.now());
        WbOzonGtinLinkDialog.Review.Backend backend=new WbOzonGtinLinkDialog.Review.Backend(){
            public List<Shop> shops(){return List.of(wb);}
            public WbOzonGtinReviewService.Snapshot scan(Shop w,Shop o,boolean cached,Consumer<String> progress){return snapshot;}
            public List<WbOzonGtinMatcher.Result> manual(WbOzonGtinReviewService.Snapshot s,List<ProductRow> rows,String card){return rows.stream().map(row->new WbOzonGtinMatcher.Result(row,List.of(),"NO_SAFE_MATCH")).toList();}
            public WbOzonGtinReviewService.ApprovalReport approve(Shop w,Shop o,WbOzonGtinReviewService.Snapshot s,List<WbOzonGtinMatcher.Candidate> rows,Consumer<String> progress){approvals.incrementAndGet();return new WbOzonGtinReviewService.ApprovalReport(List.of(link),List.of(new WbOzonGtinReviewService.ApprovalFailure(two,"GTIN_TNVED_MISSING")));}
            public List<WbOzonGtinDispatchService.Report> apply(Shop w,Shop o,List<WbOzonLinkRepository.Link> rows,Consumer<WbOzonGtinDispatchService.Report> progress){writes.incrementAndGet();assertEquals(List.of(link),rows);var report=new WbOzonGtinDispatchService.Report(link,WbOzonGtinDispatchService.State.WAITING,"GTIN_READBACK_PENDING","job");progress.accept(report);return List.of(report);}
        };
        var stage=fx(()->{var review=new WbOzonGtinLinkDialog.Review(ozon,backend);var window=new Stage();window.setScene(new Scene(review,1100,700));window.show();return window;});
        try {
            until(()->!((Button)stage.getScene().lookup("#wbOzonScan")).isDisabled());
            fx(()->{((Button)stage.getScene().lookup("#wbOzonScan")).fire();return null;});
            until(()->((TableView<?>)stage.getScene().lookup("#wbOzonCandidates")).getItems().size()==2);
            fx(()->{((Button)stage.getScene().lookup("#wbOzonSelectSafe")).fire();return null;});
            assertEquals(2,fx(()->((TableView<?>)stage.getScene().lookup("#wbOzonCandidates")).getSelectionModel().getSelectedItems().size()));
            fx(()->{((WbOzonGtinLinkDialog.Review)stage.getScene().getRoot()).setConfirmation(c->false);((Button)stage.getScene().lookup("#wbOzonApply")).fire();return null;});
            assertEquals(0,approvals.get());assertEquals(0,writes.get());
            fx(()->{((WbOzonGtinLinkDialog.Review)stage.getScene().getRoot()).setConfirmation(c->true);((Button)stage.getScene().lookup("#wbOzonApply")).fire();return null;});
            until(()->writes.get()==1&&!((Button)stage.getScene().lookup("#wbOzonScan")).isDisabled());
            assertEquals(1,approvals.get());assertEquals(1,writes.get());
            fx(()->{var table=(TableView<?>)stage.getScene().lookup("#wbOzonCandidates");@SuppressWarnings("unchecked") var state=(TableColumn<Object,String>)table.getColumns().getLast();
                assertTrue(state.getCellData(table.getItems().get(0)).contains(I18nService.getInstance().tr("wb_ozon.result.waiting")));
                assertTrue(state.getCellData(table.getItems().get(1)).contains(I18nService.getInstance().tr("wb_ozon.result.failed")));
                assertTrue(state.getCellData(table.getItems().get(1)).contains(I18nService.getInstance().tr("wb_ozon.tnved_problem")));return null;});
        } finally {fx(()->{stage.close();return null;});}
    }
    @Test void savedLinkIsOfferedForReadbackButNeverPresentedAsVerifiedUntilDispatchConfirms()throws Exception {
        var wb=new Shop(1,"WB",Marketplace.WILDBERRIES,null,"wb-token");var ozon=new Shop(2,"Ozon",Marketplace.OZON,"123","ozon-token");
        var candidate=candidate(wb,ozon,"101","201","offer-a","4006381333931");
        var link=new WbOzonLinkRepository.Link(1,"101","11","wb-owner",2,"201","offer-a","77","oz-owner",candidate.catalog().gtin(),12,"wb-source","oz-source","wbr","ozr","EXACT_ARTICLE","digest",Instant.now());
        var result=new WbOzonGtinMatcher.Result(candidate.ozon(),List.of(),"NO_SAFE_MATCH");
        var saved=new WbOzonLinkRepository.ReviewStatus("201","offer-a",false,link);
        var snapshot=new WbOzonGtinReviewService.Snapshot(List.of(candidate.wb()),List.of(result),List.of("CACHE_INCOMPLETE"),List.of(candidate.catalog()),Map.of(),List.of(saved),"wb-owner","oz-owner","wb-source","oz-source",1,2,Instant.now());
        var writes=new AtomicInteger();
        WbOzonGtinLinkDialog.Review.Backend backend=new WbOzonGtinLinkDialog.Review.Backend(){
            public List<Shop> shops(){return List.of(wb);}
            public WbOzonGtinReviewService.Snapshot scan(Shop w,Shop o,boolean cached,Consumer<String> progress){assertTrue(cached);return snapshot;}
            public List<WbOzonGtinMatcher.Result> manual(WbOzonGtinReviewService.Snapshot s,List<ProductRow> rows,String card){throw new AssertionError();}
            public WbOzonGtinReviewService.ApprovalReport approve(Shop w,Shop o,WbOzonGtinReviewService.Snapshot s,List<WbOzonGtinMatcher.Candidate> rows,Consumer<String> progress){throw new AssertionError("Saved link must not be silently re-approved");}
            public List<WbOzonGtinDispatchService.Report> apply(Shop w,Shop o,List<WbOzonLinkRepository.Link> rows,Consumer<WbOzonGtinDispatchService.Report> progress){assertEquals(List.of(link),rows);writes.incrementAndGet();return List.of(new WbOzonGtinDispatchService.Report(link,WbOzonGtinDispatchService.State.WAITING,"GTIN_READBACK_PENDING","job"));}
        };
        var stage=fx(()->{var review=new WbOzonGtinLinkDialog.Review(ozon,backend);var window=new Stage();window.setScene(new Scene(review,940,650));window.show();return window;});
        try {
            until(()->!((Button)stage.getScene().lookup("#wbOzonScanCached")).isDisabled());
            fx(()->{((Button)stage.getScene().lookup("#wbOzonScanCached")).fire();return null;});
            until(()->((TableView<?>)stage.getScene().lookup("#wbOzonCandidates")).getItems().size()==1);
            fx(()->{var table=(TableView<?>)stage.getScene().lookup("#wbOzonCandidates");@SuppressWarnings("unchecked") var state=(TableColumn<Object,String>)table.getColumns().getLast();String value=state.getCellData(table.getItems().getFirst());
                assertTrue(value.contains(I18nService.getInstance().tr("wb_ozon.link_saved")));assertFalse(value.contains(I18nService.getInstance().tr("wb_ozon.result.verified")));return null;});
            fx(()->{((Button)stage.getScene().lookup("#wbOzonSelectSafe")).fire();((WbOzonGtinLinkDialog.Review)stage.getScene().getRoot()).setConfirmation(c->true);((Button)stage.getScene().lookup("#wbOzonApply")).fire();return null;});
            until(()->writes.get()==1&&!((Button)stage.getScene().lookup("#wbOzonScan")).isDisabled());
            fx(()->{var table=(TableView<?>)stage.getScene().lookup("#wbOzonCandidates");@SuppressWarnings("unchecked") var state=(TableColumn<Object,String>)table.getColumns().getLast();String value=state.getCellData(table.getItems().getFirst());
                assertTrue(value.contains(I18nService.getInstance().tr("wb_ozon.result.waiting")));assertFalse(value.contains(I18nService.getInstance().tr("wb_ozon.result.verified")));return null;});
        } finally {fx(()->{stage.close();return null;});}
    }
    private static WbOzonGtinMatcher.Candidate candidate(Shop wb,Shop ozon,String w,String o,String offer,String gtin){
        var a=new ProductRow(new ProductKey(wb.getId(),Marketplace.WILDBERRIES,w,"11"),"WB name","shared","","Suit","Brand","black","XL",List.of(gtin),gtin,"","cotton","6103109000",DocumentState.UNVERIFIED,"male",true);
        var b=new ProductRow(new ProductKey(ozon.getId(),Marketplace.OZON,o,offer),"Different name","shared","","Suit","Brand","black","XL",List.of(),"","","cotton","6103109000",DocumentState.UNVERIFIED,"male",true);
        var card=new RegisteredGtin(gtin,"shared","black","XL",true,true,"source",Instant.now(),CatalogProductData.EMPTY);
        return new WbOzonGtinMatcher.Candidate(a,b,card,WbOzonGtinMatcher.Evidence.EXACT_ARTICLE,true);
    }
}
