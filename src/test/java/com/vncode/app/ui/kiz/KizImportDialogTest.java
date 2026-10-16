package com.vncode.app.ui.kiz;

import com.vncode.app.features.kiz.KizWarehouseRepository.*;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class KizImportDialogTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @BeforeEach void init(){System.setProperty("vncode.appdata.dir",root.toString());com.vncode.app.config.Database.initDatabase();}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @Test void reportDistinguishesRecoveredStockFromBlockedDuplicatesInEveryLanguage()throws Exception {
        var task=new FutureTask<Void>(()->{
            var i18n=I18nService.getInstance();var before=i18n.getCurrentLanguage();
            try {for(var language:AppLanguage.values()) {
                i18n.setLanguage(language);
                var review=new ImportReview(0,200,0,0,0,0,0,0,0,0);
                var result=KizImportDialog.resultText(new Imported("batch",0,200,200,0,review));assertTrue(result.contains("200"));assertFalse(result.contains("kiz.import."));assertEquals(1,result.lines().count());
                var blocked=new ImportReview(0,0,3,4,5,6,7,8,9,10);
                var details=KizImportDialog.resultText(new Imported("batch",0,52,0,2,blocked));assertEquals(9,details.lines().count());assertFalse(details.contains("kiz.import."));
                assertTrue(KizImportDialog.reviewText(blocked).contains("10"));
            }}finally{i18n.setLanguage(before);}return null;
        });Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
}
