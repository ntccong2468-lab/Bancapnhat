package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationPreflightCancellationTest {
    @TempDir Path directory;Shop shop;RegistrationBatchPreflight.Editor editor;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException active){}}
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        var key=new ProductKey(shop.getId(),Marketplace.OZON,"501","offer-52");
        var finding=new RegistrationBatchPreflight.Finding("material",key,"Article 52","","","");
        editor=fx(()->new RegistrationBatchPreflight.Editor(null,shop,new RegistrationBatchPreflight.Review(List.of(finding),List.of(key),null)));
    }
    @AfterEach void cleanup()throws Exception{fx(()->{editor.dialog.close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    @Test void readDeadlineCancellationUnlocksApplyAndPreservesTheReviewForExplicitRetry()throws Exception{
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var attempts=new AtomicInteger();var onFx=new AtomicBoolean(true);
        try{
            fx(()->{editor.reviewReader=(owner,keys,values,documents,categories)->{attempts.incrementAndGet();onFx.set(Platform.isFxApplicationThread());started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException(stopped);}return editor.review;};editor.dialog.show();editor.freshReview(false);return null;});
            assertTrue(started.await(5,TimeUnit.SECONDS));assertFalse(onFx.get());
            fx(()->{assertTrue(editor.busy);assertTrue(editor.table.isDisabled());assertEquals(1,editor.reads.size());editor.reads.iterator().next().cancel(true);return null;});
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            fx(()->{assertFalse(editor.busy);assertFalse(editor.table.isDisabled());assertFalse(editor.dialog.getDialogPane().lookupButton(editor.applyType).isDisabled());assertTrue(editor.dialog.isShowing());assertTrue(editor.status.getText().contains("VN_NETWORK"));assertEquals(1,editor.table.getItems().size());assertEquals(javafx.animation.Animation.Status.STOPPED,editor.retry.getStatus());return null;});
            assertEquals(1,attempts.get(),"An unavailable dictionary must not automatically restart the entire review indefinitely");assertNoRegistrationWrites();
        }finally{release.countDown();}
    }
    @Test void closingTheRealDialogInterruptsItsReadAndCannotApproveOrStartRegistration()throws Exception{
        var started=new CountDownLatch(1);var exited=new CountDownLatch(1);var release=new CountDownLatch(1);var selectedTask=new AtomicReference<Task<?>>();
        try{
            fx(()->{editor.reviewReader=(owner,keys,values,documents,categories)->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{exited.countDown();}return new RegistrationBatchPreflight.Review(List.of());};editor.dialog.show();editor.freshReview(true);return null;});
            assertTrue(started.await(5,TimeUnit.SECONDS));
            fx(()->{selectedTask.set(editor.reads.iterator().next());var cancel=editor.dialog.getDialogPane().getButtonTypes().stream().filter(button->button.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();((Button)editor.dialog.getDialogPane().lookupButton(cancel)).fire();return null;});
            assertTrue(exited.await(5,TimeUnit.SECONDS));com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            fx(()->{assertTrue(editor.disposed);assertFalse(editor.dialog.isShowing());assertTrue(editor.reads.isEmpty());assertTrue(selectedTask.get().isCancelled());assertNotEquals(Boolean.TRUE,editor.dialog.getResult());return null;});assertNoRegistrationWrites();
        }finally{release.countDown();}
    }
    @Test void explicitInitialReadCancelPillInterruptsTheReadAndRemovesItself()throws Exception{
        var started=new CountDownLatch(1);var exited=new CountDownLatch(1);var release=new CountDownLatch(1);var cancelled=new AtomicInteger();var continued=new AtomicInteger();var host=fx(()->new javafx.scene.layout.StackPane());
        var task=new Task<Void>(){protected Void call()throws Exception{started.countDown();try{release.await(5,TimeUnit.SECONDS);continued.incrementAndGet();return null;}finally{exited.countDown();}}};
        try{
            fx(()->{RegistrationBatchPreflight.readPill(host,"preflightRead",task,cancelled::incrementAndGet);com.vncode.app.shared.AppTaskExecutor.executeCatalog(task,error->fail(error));return null;});assertTrue(started.await(5,TimeUnit.SECONDS));
            fx(()->{var button=(Button)host.lookup("#preflightReadCancel");assertNotNull(button);assertEquals(com.vncode.app.shared.I18nService.getInstance().tr("common.cancel"),button.getText());button.fire();assertTrue(host.getChildren().isEmpty());return null;});
            assertTrue(exited.await(5,TimeUnit.SECONDS));assertTrue(task.isCancelled());assertEquals(1,cancelled.get());assertEquals(0,continued.get());assertNoRegistrationWrites();
        }finally{release.countDown();}
    }
    @Test void fullCatalogQueueReportsTheActualRejectionInsteadOfReplacingItWithTimeout()throws Exception{
        var release=new CountDownLatch(1);var started=new CountDownLatch(2);var blockers=new ArrayList<Task<Void>>();var failure=new AtomicReference<Throwable>();var calls=new AtomicInteger();
        try{
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();var saturated=new AtomicBoolean();var rejected=new AtomicReference<Throwable>();
            java.util.function.Supplier<Task<Void>> filler=()->new Task<>(){protected Void call()throws Exception{started.countDown();release.await(10,TimeUnit.SECONDS);return null;}};
            for(int n=0;n<2;n++){var blocker=filler.get();assertTrue(com.vncode.app.shared.AppTaskExecutor.executeCatalog(blocker,error->fail(error)));blockers.add(blocker);}
            assertTrue(started.await(5,TimeUnit.SECONDS),"Both Catalog workers must be held before filling their queue");
            for(int n=0;n<24;n++){var blocker=filler.get();if(com.vncode.app.shared.AppTaskExecutor.executeCatalog(blocker,rejected::set))blockers.add(blocker);else{saturated.set(true);break;}}
            assertTrue(saturated.get(),"The bounded Catalog executor must be saturated before testing its UI cleanup");assertInstanceOf(RejectedExecutionException.class,rejected.get());
            fx(()->{var read=new Task<Void>(){protected Void call(){fail("A rejected read must not run");return null;}};editor.executeRead(read,error->{failure.set(error);calls.incrementAndGet();});return null;});fx(()->null);
            assertInstanceOf(RejectedExecutionException.class,failure.get());assertEquals(1,calls.get());assertNoRegistrationWrites();
        }finally{release.countDown();for(var blocker:blockers)blocker.get(10,TimeUnit.SECONDS);}
    }
    private void assertNoRegistrationWrites()throws Exception{
        try(var connection=Database.getConnection();var statement=connection.createStatement()){
            for(String table:List.of("ozon_card_registrations","znack_card_registrations"))try(var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(rows.next());assertEquals(0,rows.getInt(1),table);}
        }
    }
    private static <T>T fx(Callable<T> action)throws Exception{var task=new FutureTask<>(action);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
}
