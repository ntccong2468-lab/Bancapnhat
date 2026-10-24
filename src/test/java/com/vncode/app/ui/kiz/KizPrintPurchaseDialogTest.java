package com.vncode.app.ui.kiz;

import com.vncode.app.features.kiz.KizPrintPurchaseSession;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KizPrintPurchaseDialogTest {
    @TempDir java.nio.file.Path path;
    @BeforeAll static void startup(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException running){}}
    @BeforeEach void init(){System.setProperty("vncode.appdata.dir",path.toString());com.vncode.app.config.Database.initDatabase();}
    @AfterEach void reset(){System.clearProperty("vncode.appdata.dir");}
    @Test void applyAndCancelButtonsCloseActualReportInAllFourLanguages()throws Exception{
        var result=new CompletableFuture<Void>();Platform.runLater(()->{
            var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
            try{
                var rows=new ArrayList<KizPrintPurchaseSession.Row>();for(int i=0;i<200;i++)rows.add(new KizPrintPurchaseSession.Row("04645588781154",5,2,3,3));
                for(var language:AppLanguage.values()){
                    i18n.setLanguage(language);
                    for(boolean apply:List.of(false,true)){
                        var dialog=KizPrintPurchaseDialog.create(new Shop(1,"Fixture","fixture"),new KizPrintPurchaseSession.Proposal(rows));
                        dialog.setOnShown(e->Platform.runLater(()->{
                            try{
                                var table=(TableView<?>)dialog.getDialogPane().lookup("#kizPrintPurchaseReport");assertEquals(200,table.getItems().size());assertEquals(5,table.getColumns().size());
                                assertFalse(dialog.getTitle().contains("kiz.print."));
                                assertTrue(dialog.getDialogPane().getScene().getWindow().getHeight()<=javafx.stage.Screen.getPrimary().getVisualBounds().getHeight());
                                ((Button)dialog.getDialogPane().lookup(apply?"#kizPrintPurchaseApply":"#kizPrintPurchaseCancel")).fire();
                            }catch(Throwable failure){result.completeExceptionally(failure);dialog.close();}
                        }));
                        assertEquals(apply,dialog.showAndWait().orElse(false));assertFalse(dialog.isShowing());
                    }
                }
                result.complete(null);
            }catch(Throwable failure){result.completeExceptionally(failure);}finally{i18n.setLanguage(previous);}
        });result.get(30,TimeUnit.SECONDS);
    }
    @Test void workerWaitsForRealApplyButtonAndContinuesAfterDialogCloses()throws Exception{
        var applied=new CompletableFuture<Void>();
        Platform.runLater(()->{
            var timer=new javafx.animation.Timeline();timer.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.millis(30),e->{
                for(var window:javafx.stage.Window.getWindows()){
                    var button=window.getScene()==null?null:window.getScene().lookup("#kizPrintPurchaseApply");
                    if(button instanceof Button apply){timer.stop();apply.fire();applied.complete(null);break;}
                }
            }));timer.setCycleCount(300);timer.play();
        });
        var answer=CompletableFuture.supplyAsync(()->KizPrintPurchaseDialog.confirm(new Shop(1,"Fixture","fixture"),
                new KizPrintPurchaseSession.Proposal(List.of(new KizPrintPurchaseSession.Row("04645588781154",5,2,3,3))),()->false));
        assertTrue(answer.get(10,TimeUnit.SECONDS));applied.get(2,TimeUnit.SECONDS);
    }
    @Test void cancelledWorkerDoesNotOpenProposal(){assertThrows(CancellationException.class,()->KizPrintPurchaseDialog.confirm(new Shop(1,"Fixture","fixture"),new KizPrintPurchaseSession.Proposal(List.of()),()->true));}
}
