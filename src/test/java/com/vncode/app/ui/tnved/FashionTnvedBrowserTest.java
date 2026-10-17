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
}
