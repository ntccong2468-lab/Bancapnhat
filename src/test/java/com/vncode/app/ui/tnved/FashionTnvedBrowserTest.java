package com.vncode.app.ui.tnved;

import com.vncode.app.features.tnved.FashionTnvedCatalog.Entry;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class FashionTnvedBrowserTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",root.toString());com.vncode.app.config.Database.initDatabase();}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> f)throws Exception{var t=new FutureTask<>(f);Platform.runLater(t);return t.get(10,TimeUnit.SECONDS);}
    @Test void filteringThenExplicitApplyReturnsTheChosenCode()throws Exception{
        fx(()->{
            var browser=new FashionTnvedBrowser();new Scene(browser,1000,620);browser.applyCss();browser.layout();var selected=new AtomicReference<Entry>();browser.setOnSelect(selected::set);
            @SuppressWarnings("unchecked") var list=(ListView<Entry>)browser.lookup("#tnvedReferenceRows");assertEquals(526,list.getItems().size());
            ((ComboBox<String>)browser.lookup("#tnvedReferenceGender")).setValue("Мужской");((ComboBox<String>)browser.lookup("#tnvedReferenceMaterial")).setValue("Хлопок");((ComboBox<String>)browser.lookup("#tnvedReferenceFabric")).setValue("Трикотаж");
            var target=list.getItems().stream().filter(e->e.code().equals("6112110000")).findFirst().orElseThrow();list.getSelectionModel().select(target);assertNull(selected.get());
            var apply=(Button)browser.lookup("#tnvedReferenceApply");assertFalse(apply.isDisabled());apply.fire();assertEquals("6112110000",selected.get().code());return null;
        });
    }
    @Test void actualMouseClickCopiesCodeWithoutApplyingOrClosingThePicker()throws Exception{
        var selected=new AtomicReference<Entry>();var stage=new javafx.stage.Stage[1];
        String code=fx(()->{
            var browser=new FashionTnvedBrowser();browser.setOnSelect(selected::set);var scene=new Scene(browser,900,580);
            com.vncode.app.shared.ThemeService.applyTheme(scene);stage[0]=new javafx.stage.Stage();stage[0].setScene(scene);stage[0].show();stage[0].toFront();stage[0].requestFocus();browser.applyCss();browser.layout();
            @SuppressWarnings("unchecked")var list=(ListView<Entry>)browser.lookup("#tnvedReferenceRows");return list.getItems().getFirst().code();
        });
        try{
            fx(()->{var browser=stage[0].getScene().getRoot();browser.applyCss();browser.layout();var button=(Button)browser.lookup("#tnvedCopy_"+code);assertNotNull(button);assertTrue(button.getWidth()>=36);var bounds=button.localToScreen(button.getBoundsInLocal());assertNotNull(bounds);var old=new javafx.scene.input.ClipboardContent();old.putString("before-copy");javafx.scene.input.Clipboard.getSystemClipboard().setContent(old);var robot=new javafx.scene.robot.Robot();robot.mouseMove(bounds.getCenterX(),bounds.getCenterY());robot.mouseClick(javafx.scene.input.MouseButton.PRIMARY);return null;});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(!fx(()->code.equals(javafx.scene.input.Clipboard.getSystemClipboard().getString()))&&System.nanoTime()<deadline)Thread.sleep(20);
            fx(()->{assertEquals(code,javafx.scene.input.Clipboard.getSystemClipboard().getString());assertTrue(((Label)stage[0].getScene().getRoot().lookup("#tnvedCopyStatus")).getText().contains(code));assertTrue(stage[0].isShowing());assertNull(selected.get(),"Copy must never trigger Apply");return null;});
        }finally{fx(()->{stage[0].close();return null;});}
    }
}
