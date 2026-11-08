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
    static void awaitFx(Callable<Boolean> condition,long deadline,Callable<String> diagnostic)throws Exception{
        while(!fx(condition)&&System.nanoTime()<deadline)Thread.sleep(20);
        assertTrue(fx(condition),fx(diagnostic));
    }
    @Test void filteringThenExplicitApplyReturnsTheChosenCode()throws Exception{
        fx(()->{
            var browser=new FashionTnvedBrowser();new Scene(browser,1000,620);browser.applyCss();browser.layout();var selected=new AtomicReference<Entry>();browser.setOnSelect(selected::set);
            @SuppressWarnings("unchecked") var list=(ListView<Entry>)browser.lookup("#tnvedReferenceRows");assertEquals(526,list.getItems().size());
            ((ComboBox<String>)browser.lookup("#tnvedReferenceGender")).setValue("Мужской");((ComboBox<String>)browser.lookup("#tnvedReferenceMaterial")).setValue("Хлопок");((ComboBox<String>)browser.lookup("#tnvedReferenceFabric")).setValue("Трикотаж");
            var target=list.getItems().stream().filter(e->e.code().equals("6112110000")).findFirst().orElseThrow();list.getSelectionModel().select(target);assertNull(selected.get());
            var apply=(Button)browser.lookup("#tnvedReferenceApply");assertFalse(apply.isDisabled());apply.fire();assertEquals("6112110000",selected.get().code());return null;
        });
    }
    @Test void applyUsesTheCompletePerProductProposalUntilSellerChoosesAGlobalCode()throws Exception{
        fx(()->{var browser=new FashionTnvedBrowser();new Scene(browser,1000,620);browser.applyCss();browser.layout();var chosen=new AtomicReference<Entry>();var applied=new java.util.concurrent.atomic.AtomicInteger();browser.setOnSelect(chosen::set);browser.setDefaultApply(applied::incrementAndGet,true);var apply=(Button)browser.lookup("#tnvedReferenceApply");assertFalse(apply.isDisabled());apply.fire();assertEquals(1,applied.get());assertNull(chosen.get());browser.selectCode("6112110000");apply.fire();assertEquals("6112110000",chosen.get().code());assertEquals(1,applied.get());return null;});
    }
    @Test void actualMouseClickCopiesCodeWithoutApplyingOrClosingThePicker()throws Exception{
        var selected=new AtomicReference<Entry>();var stage=new javafx.stage.Stage[1];var copy=new AtomicReference<Button>();
        var presses=new java.util.concurrent.atomic.AtomicInteger();var actions=new java.util.concurrent.atomic.AtomicInteger();
        String code=fx(()->{
            var browser=new FashionTnvedBrowser();browser.setOnSelect(selected::set);var scene=new Scene(browser,900,580);
            com.vncode.app.shared.ThemeService.applyTheme(scene);stage[0]=new javafx.stage.Stage();stage[0].setScene(scene);stage[0].show();stage[0].toFront();stage[0].requestFocus();browser.applyCss();browser.layout();
            @SuppressWarnings("unchecked")var list=(ListView<Entry>)browser.lookup("#tnvedReferenceRows");return list.getItems().getFirst().code();
        });
        try{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            awaitFx(()->stage[0].isShowing()&&stage[0].isFocused(),deadline,()->"Copy window did not receive native focus");
            fx(()->{var browser=stage[0].getScene().getRoot();browser.applyCss();browser.layout();var button=(Button)browser.lookup("#tnvedCopy_"+code);assertNotNull(button);assertTrue(button.getWidth()>=36);assertFalse(button.isDisabled());copy.set(button);button.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED,e->presses.incrementAndGet());button.addEventFilter(javafx.event.ActionEvent.ACTION,e->actions.incrementAndGet());var bounds=button.localToScreen(button.getBoundsInLocal());assertNotNull(bounds);var old=new javafx.scene.input.ClipboardContent();old.putString("before-copy");assertTrue(javafx.scene.input.Clipboard.getSystemClipboard().setContent(old));new javafx.scene.robot.Robot().mouseMove(bounds.getCenterX(),bounds.getCenterY());return null;});
            Callable<String> diagnostic=()->"Copy delivery: focused="+stage[0].isFocused()+", hover="+copy.get().isHover()+", presses="+presses.get()+", actions="+actions.get()+", status="+((Label)stage[0].getScene().getRoot().lookup("#tnvedCopyStatus")).getText();
            awaitFx(()->stage[0].isFocused()&&copy.get().isHover(),deadline,diagnostic);
            fx(()->{new javafx.scene.robot.Robot().mouseClick(javafx.scene.input.MouseButton.PRIMARY);return null;});
            awaitFx(()->actions.get()==1&&code.equals(javafx.scene.input.Clipboard.getSystemClipboard().getString()),deadline,diagnostic);
            fx(()->{assertEquals(1,presses.get(),diagnostic.call());assertEquals(1,actions.get(),diagnostic.call());assertEquals(code,javafx.scene.input.Clipboard.getSystemClipboard().getString());assertTrue(((Label)stage[0].getScene().getRoot().lookup("#tnvedCopyStatus")).getText().contains(code));assertTrue(stage[0].isShowing());assertNull(selected.get(),"Copy must never trigger Apply");return null;});
        }finally{fx(()->{stage[0].close();return null;});}
    }
}
