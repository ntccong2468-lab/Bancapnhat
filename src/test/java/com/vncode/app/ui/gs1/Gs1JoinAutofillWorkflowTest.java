package com.vncode.app.ui.gs1;

import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Ready Catalog profiles need only explicit Apply; legal XML signing remains a later operation. */
class Gs1JoinAutofillWorkflowTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException running){}}
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void completeSourcedProfileOpensReviewWithoutCallingApplyOrSigning()throws Exception{
        fx(()->{
            var calls=new AtomicInteger();var original=Gs1JoinFormValidationContractTest.validForm();var before=original.deepCopy();
            var editor=editor(original,(form,prepare)->calls.incrementAndGet());
            assertEquals(5,steps(editor).getSelectionModel().getSelectedIndex(),"All verified required data should open the review step immediately");
            assertEquals(0,calls.get(),"Autofill and navigation cannot save or legally sign a form");assertEquals(before,original);return null;
        });
    }
    @Test void incompleteProfileStartsAtItsActualMissingStepAndKeepsTheBankBlank()throws Exception{
        fx(()->{
            var original=Gs1JoinFormValidationContractTest.validForm();original.getAsJsonObject("bankDetails").remove("bik");
            var editor=editor(original,(form,prepare)->fail("A missing bank field cannot submit"));
            assertEquals(1,steps(editor).getSelectionModel().getSelectedIndex(),"Show the missing bank field instead of asking to re-enter the ready enterprise step");
            assertFalse(form(editor).getAsJsonObject("bankDetails").has("bik"));
            assertFalse(((Label)field(editor,"errors")).getText().isBlank());return null;
        });
    }
    @Test void applyIsExplicitAndReturnsReviewedDataToTheExistingXmlPreparationBoundary()throws Exception{
        fx(()->{
            var calls=new AtomicInteger();var editor=editor(Gs1JoinFormValidationContractTest.validForm(),(form,prepare)->{assertTrue(prepare);calls.incrementAndGet();});
            var apply=apply(editor);assertNotNull(apply,"A ready profile needs the explicit Apply action");
            assertEquals(I18nService.getInstance().tr("gs1.wizard.apply"),apply.getText());assertFalse(apply.isDisabled());assertEquals(0,calls.get());
            apply.fire();assertEquals(1,calls.get(),"Apply prepares the exact XML through the existing callback; no signer is installed here");return null;
        });
    }
    @Test void aRequiredValueRemovedAfterReviewReturnsToItsStepInsteadOfPreparingXml()throws Exception{
        fx(()->{
            var calls=new AtomicInteger();var editor=editor(Gs1JoinFormValidationContractTest.validForm(),(value,prepare)->calls.incrementAndGet());
            form(editor).getAsJsonObject("bankDetails").remove("bik");apply(editor).fire();
            assertEquals(0,calls.get());assertEquals(1,steps(editor).getSelectionModel().getSelectedIndex());assertTrue(apply(editor).isDisabled());return null;
        });
    }
    private static Gs1JoinDialog editor(JsonObject original,java.util.function.BiConsumer<JsonObject,Boolean> save){return new Gs1JoinDialog(new Gs1Pane(),"7701234567",original,Map.of(),query->List.of(),save);}
    private static Button apply(Gs1JoinDialog editor)throws Exception{var pane=dialog(editor).getDialogPane();for(var type:pane.getButtonTypes())if(pane.lookupButton(type) instanceof Button button&&"gs1JoinApply".equals(button.getId()))return button;return null;}
    private static Object field(Object value,String name)throws Exception{var field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    @SuppressWarnings("unchecked")private static Dialog<Void> dialog(Gs1JoinDialog editor)throws Exception{return (Dialog<Void>)field(editor,"dialog");}
    private static TabPane steps(Gs1JoinDialog editor)throws Exception{return (TabPane)field(editor,"steps");}
    private static JsonObject form(Gs1JoinDialog editor)throws Exception{return (JsonObject)field(editor,"form");}
    private static <T>T fx(Callable<T> task)throws Exception{var future=new FutureTask<>(task);Platform.runLater(future);try{return future.get(20,TimeUnit.SECONDS);}catch(ExecutionException failure){if(failure.getCause() instanceof Error cause)throw cause;if(failure.getCause() instanceof Exception cause)throw cause;throw failure;}}
}
