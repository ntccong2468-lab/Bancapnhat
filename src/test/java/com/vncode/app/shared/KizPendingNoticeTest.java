package com.vncode.app.shared;

import com.vncode.app.features.kiz.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class KizPendingNoticeTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    String previous;
    @BeforeEach void isolate(){previous=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",directory.toString());com.vncode.app.config.Database.initDatabase();}
    @AfterEach void restore(){if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);}
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException running){}}
    @Test void waitingShowsFullPerGtinCountsInAnInformationDialog()throws Exception{
        String gtin="04645588781154";
        var error=new KizPurchasePendingException(Map.of(gtin,180),new KizStockReplenisher.Result(Map.of(gtin,3),0,true));
        var result=new CompletableFuture<Void>();
        Platform.runLater(()->{try{
            var alert=AlertService.pendingKizAlert(new CompletionException(error));
            assertEquals(Alert.AlertType.INFORMATION,alert.getAlertType());
            var details=(TextArea)alert.getDialogPane().getContent();assertFalse(details.isEditable());assertTrue(details.isWrapText());
            assertTrue(details.getText().contains(gtin));assertTrue(details.getText().contains("177"));assertTrue(details.getText().contains("180"));
            assertFalse(details.getText().contains("kiz.pending."));alert.close();result.complete(null);
        }catch(Throwable failure){result.completeExceptionally(failure);}});
        result.get(10,TimeUnit.SECONDS);
    }
    @Test void otherStockIsNotReportedAsAvailableForTheRequestedGtin(){
        String gtin="04645588781154";
        var pending=new KizPurchasePendingException(Map.of(gtin.substring(1),2),new KizStockReplenisher.Result(Map.of(gtin,2),0,true));
        assertEquals(0,pending.deficits().getFirst().available());assertEquals(2,pending.deficits().getFirst().missing());
        assertTrue(KizPurchasePendingException.isPending(new CompletionException(pending)));
        assertFalse(KizPurchasePendingException.isPending(new IllegalStateException("KIZ_REMOTE_REJECTED")));
        var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();
        try{for(var language:AppLanguage.values()){i18n.setLanguage(language);assertFalse(FriendlyErrorService.format(pending).contains("kiz.pending."));}}
        finally{i18n.setLanguage(previous);}
    }
}
