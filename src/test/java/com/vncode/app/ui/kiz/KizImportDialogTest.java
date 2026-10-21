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
    @Test void validStructureDoesNotClaimTheMarketplaceAcceptedTheCode()throws Exception {
        var file=root.resolve("well-formed.txt");java.nio.file.Files.writeString(file,"0104645588781154210000000000001");
        var preview=com.vncode.app.features.kiz.KizImportReader.read(file);assertEquals(1,preview.marks().size());assertEquals(0,preview.damaged());
        var task=new FutureTask<Void>(()->{
            var i18n=I18nService.getInstance();var previous=i18n.getCurrentLanguage();var seen=new java.util.concurrent.atomic.AtomicBoolean();var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var driver=new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(30),event->{
                for(var window:java.util.List.copyOf(javafx.stage.Window.getWindows()))if(window.isShowing()&&window.getScene()!=null){
                    var status=window.getScene().lookup("#kiz-import-marketplace-status");
                    if(status instanceof javafx.scene.control.Label label){
                      try{
                        assertEquals(i18n.tr("kiz.import.marketplace_unchecked"),label.getText());assertFalse(label.getText().contains("kiz.import."));
                        var report=(javafx.scene.control.Label)window.getScene().lookup("#kiz-import-file-report");
                        assertTrue(report.getText().contains("lỗi định dạng"));assertFalse(report.getText().contains("mã hỏng"));seen.set(true);
                        String screenshot=System.getProperty("vncode.kiz.report.screenshot");
                        if(screenshot!=null){
                            var snapshot=window.getScene().snapshot(null);int width=(int)snapshot.getWidth(),height=(int)snapshot.getHeight();var pixels=new int[width*height];
                            snapshot.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,width);
                            var bitmap=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);bitmap.setRGB(0,0,width,height,pixels,0,width);
                            javax.imageio.ImageIO.write(bitmap,"png",java.nio.file.Path.of(screenshot).toFile());
                        }
                      }catch(Throwable error){failure.set(error);}
                      finally{if(window.getScene().getRoot() instanceof javafx.scene.control.DialogPane dialog)((javafx.scene.control.Button)dialog.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();}
                    }
                }
            }));driver.setCycleCount(javafx.animation.Animation.INDEFINITE);
            try{i18n.setLanguage(AppLanguage.VI);driver.play();KizImportDialog.report(null,"Giả lập nhập KIZ",preview,null);if(failure.get()!=null)throw new AssertionError("Import report validation failed",failure.get());assertTrue(seen.get());}
            finally{driver.stop();i18n.setLanguage(previous);}return null;
        });Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
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
