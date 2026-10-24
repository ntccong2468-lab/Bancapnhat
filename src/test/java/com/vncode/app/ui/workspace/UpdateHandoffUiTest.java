package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.integration.update.*;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateHandoffUiTest {
    @TempDir Path data;
    HomeController home;
    final AtomicReference<Path> handedOff = new AtomicReference<>();
    final CompletableFuture<Void> exited = new CompletableFuture<>();
    final AtomicBoolean wrongThread = new AtomicBoolean();
    final AtomicBoolean reached100 = new AtomicBoolean();
    final AtomicReference<Window> downloadWindow = new AtomicReference<>();
    IOException launchFailure;
    CountDownLatch preparationEntered;
    CountDownLatch allowPreparation;
    boolean emitSnapshotProgress;
    CountDownLatch verificationEntered;
    CountDownLatch allowVerification;

    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException running) { }
    }
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", data.toString()); Database.initDatabase();
        ConfigService.setConfigValue("update_api_url", "http://127.0.0.1:1");
        Path installer = data.resolve("downloaded-installer.exe"); Files.writeString(installer, "fixture-only");
        var service = new UpdateInstallerService() {
            @Override public Path downloadInstaller(UpdateInfo info, ProgressListener progress) throws IOException {
                wrongThread.compareAndSet(false, Platform.isFxApplicationThread());
                progress.onProgress(100, 100); reached100.set(true);
                try { downloadWindow.set(fx(() -> Window.getWindows().stream().filter(Window::isShowing).findFirst().orElseThrow())); }
                catch (Exception failed) { throw new IOException("fixture cannot inspect progress window", failed); }
                return installer;
            }
            @Override public void launchInstallerAfterExit(Path file,java.util.function.Consumer<Phase> phase,java.util.function.Consumer<LocalDataSnapshotService.Progress> progress) throws IOException {
                wrongThread.compareAndSet(false, Platform.isFxApplicationThread());
                phase.accept(Phase.BACKUP);
                if(emitSnapshotProgress)progress.accept(new LocalDataSnapshotService.Progress("COPYING_DATA",1024,4096));
                if(preparationEntered!=null){preparationEntered.countDown();try{if(!allowPreparation.await(5,TimeUnit.SECONDS))throw new IOException("fixture preparation deadline");}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("fixture cancelled",cancelled);}}
                if(emitSnapshotProgress){progress.accept(new LocalDataSnapshotService.Progress("VERIFYING_SNAPSHOT",0,0));verificationEntered.countDown();try{if(!allowVerification.await(5,TimeUnit.SECONDS))throw new IOException("fixture integrity deadline");}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("fixture cancelled",cancelled);}}
                handedOff.set(file);
                if (launchFailure != null) throw launchFailure;
            }
        };
        fx(() -> {
            var loader = FxmlViewLoader.loader(HomeController.class, "home-view.fxml");
            loader.setControllerFactory(type -> new HomeController(service, () -> {
                if (!Platform.isFxApplicationThread()) wrongThread.set(true);
                exited.complete(null);
            }));
            new Scene(loader.load(), 1024, 768); home = loader.getController(); return null;
        });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
    }
    @AfterEach void cleanup() throws Exception {
        if(allowPreparation!=null)allowPreparation.countDown();
        if(allowVerification!=null)allowVerification.countDown();
        fx(() -> { home.dispose(); for (var window : List.copyOf(Window.getWindows())) window.hide(); return null; });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        System.clearProperty("vncode.appdata.dir");
    }
    @Test void completedDownloadShowsBackupPhaseWithoutBlockingFxOrClaimingOneHundredPercent() throws Exception {
        preparationEntered=new CountDownLatch(1);allowPreparation=new CountDownLatch(1);start();
        assertTrue(preparationEntered.await(5,TimeUnit.SECONDS));
        fx(()->{
            var labels=Window.getWindows().stream().filter(Window::isShowing).flatMap(w->w.getScene().getRoot().lookupAll(".label").stream()).filter(Label.class::isInstance).map(Label.class::cast).map(Label::getText).toList();
            assertTrue(labels.contains(I18nService.getInstance().tr("update.progress.phase.BACKUP")),labels.toString());
            assertFalse(labels.contains("100%"));assertTrue(home.contentPane.isDisabled());
            assertTrue(downloadWindow.get().isShowing(), "The same download dialog must remain visible during backup");
            assertEquals(1,Window.getWindows().stream().filter(Window::isShowing).count());return null;
        });
        assertFalse(exited.isDone());allowPreparation.countDown();exited.get(5,TimeUnit.SECONDS);assertFalse(wrongThread.get());
    }
    @Test void hundredPercentDownloadHandsImmutablePathToInstallerWorkerAndExitsOnFx() throws Exception {
        start();
        try { exited.get(5, TimeUnit.SECONDS); }
        catch (TimeoutException failure) {
            fail("Update did not complete after 100%: " + fx(this::diagnostics), failure);
        }
        assertTrue(reached100.get()); assertFalse(wrongThread.get());
        assertEquals(data.resolve("downloaded-installer.exe"), handedOff.get());
        assertFalse(fx(() -> (boolean) field("updateDownloadRunning")));
    }
    @Test void busyApplicationStillDownloadsAndAutomaticallyInstallsAfterTheSavedBoundary() throws Exception {
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var writer=executor.submit(()->{try(var work=UpdateHandoff.work()){entered.countDown();finish.await();}return null;});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            try {
                start();
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!reached100.get()&&System.nanoTime()<until)Thread.sleep(10);
                assertTrue(reached100.get(),"Download must not be rejected because another operation is running");
                until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!UpdateHandoff.requested()&&System.nanoTime()<until)Thread.sleep(10);
                assertTrue(UpdateHandoff.requested());assertFalse(exited.isDone());assertNull(handedOff.get());
                assertTrue(fx(()->home.contentPane.isDisabled()));
            } finally {finish.countDown();}
            writer.get(2,TimeUnit.SECONDS);exited.get(5,TimeUnit.SECONDS);
            assertEquals(data.resolve("downloaded-installer.exe"),handedOff.get());assertFalse(wrongThread.get());
        }
    }
    @Test void realBackupByteProgressKeepsFxResponsiveAndResetsBeforeIntegrityVerification()throws Exception {
        emitSnapshotProgress=true;preparationEntered=new CountDownLatch(1);allowPreparation=new CountDownLatch(1);
        verificationEntered=new CountDownLatch(1);allowVerification=new CountDownLatch(1);start();
        assertTrue(preparationEntered.await(5,TimeUnit.SECONDS));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until&&!fx(()->progressBars().stream().anyMatch(bar->Math.abs(bar.getProgress()-.25)<.001)))Thread.sleep(10);
        fx(()->{
            assertTrue(progressBars().stream().anyMatch(bar->Math.abs(bar.getProgress()-.25)<.001));
            assertTrue(labels().stream().anyMatch(text->text.contains(I18nService.getInstance().tr("update.progress.snapshot.COPYING_DATA").split("\\{0\\}")[0])));
            assertFalse(labels().contains("100%"));assertTrue(home.contentPane.isDisabled());return null;
        });
        assertFalse(exited.isDone());allowPreparation.countDown();assertTrue(verificationEntered.await(5,TimeUnit.SECONDS));
        until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until&&!fx(()->labels().contains(I18nService.getInstance().tr("update.progress.snapshot.VERIFYING_SNAPSHOT"))))Thread.sleep(10);
        fx(()->{assertTrue(labels().contains(I18nService.getInstance().tr("update.progress.snapshot.VERIFYING_SNAPSHOT")));assertFalse(progressBars().isEmpty());assertTrue(progressBars().stream().allMatch(bar->bar.getProgress()<0));assertFalse(labels().contains("100%"));return null;});
        assertFalse(exited.isDone());allowVerification.countDown();exited.get(5,TimeUnit.SECONDS);assertFalse(wrongThread.get());
    }
    @Test void installerPreparationFailureIsReportedAsLaunchFailureAndAppStaysOpen() throws Exception {
        launchFailure = new IOException("fixture-installer-rejected"); start();
        String details = "";
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            details = fx(this::diagnostics);
            if (details.contains("fixture-installer-rejected")) break;
            Thread.sleep(10);
        }
        assertTrue(details.contains("fixture-installer-rejected"), details);
        assertFalse(details.contains("Task must only be used"), details);
        fx(() -> {
            boolean launchSummary = Window.getWindows().stream().filter(Window::isShowing)
                    .flatMap(w -> w.getScene().getRoot().lookupAll(".label").stream())
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .anyMatch(l -> I18nService.getInstance().tr("update.error.launch_failed").equals(l.getText()));
            assertTrue(launchSummary, "Completed download must not be reported as download failure");
            assertFalse(home.contentPane.isDisabled()); assertFalse(home.sidebarContainer.isDisabled()); return null;
        });
        assertEquals(data.resolve("downloaded-installer.exe"), handedOff.get());
        assertFalse(exited.isDone()); assertFalse(wrongThread.get());
    }
    @Test void cancellingBackupKeepsTheAppOpenAndDoesNotLaunchTheInstaller() throws Exception {
        preparationEntered=new CountDownLatch(1);allowPreparation=new CountDownLatch(1);start();
        assertTrue(preparationEntered.await(5,TimeUnit.SECONDS));
        fx(() -> {
            var cancel = downloadWindow.get().getScene().getRoot().lookupAll(".button").stream()
                    .filter(javafx.scene.control.Button.class::isInstance)
                    .map(javafx.scene.control.Button.class::cast)
                    .filter(button -> button.isCancelButton()).findFirst().orElseThrow();
            cancel.fire();return null;
        });
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(fx(()->(boolean)field("updateDownloadRunning"))&&System.nanoTime()<until)Thread.sleep(10);
        fx(()->{
            assertFalse((boolean)field("updateDownloadRunning"));
            assertFalse(home.contentPane.isDisabled());assertFalse(home.sidebarContainer.isDisabled());return null;
        });
        assertNull(handedOff.get());assertFalse(exited.isDone());
    }
    private void start() throws Exception {
        fx(() -> {
            var info = new UpdateInfo(); info.setVersion("1.4.8");
            var method = HomeController.class.getDeclaredMethod("startInstallerUpdate", UpdateInfo.class, boolean.class);
            method.setAccessible(true); method.invoke(home, info, false); return null;
        });
    }
    private Object field(String name) throws Exception { Field f = HomeController.class.getDeclaredField(name); f.setAccessible(true); return f.get(home); }
    private List<ProgressBar> progressBars(){return Window.getWindows().stream().filter(Window::isShowing).flatMap(w->w.getScene().getRoot().lookupAll(".progress-bar").stream()).filter(ProgressBar.class::isInstance).map(ProgressBar.class::cast).toList();}
    private List<String> labels(){return Window.getWindows().stream().filter(Window::isShowing).flatMap(w->w.getScene().getRoot().lookupAll(".label").stream()).filter(Label.class::isInstance).map(Label.class::cast).map(Label::getText).toList();}
    private String diagnostics() {
        return Window.getWindows().stream().filter(Window::isShowing)
                .flatMap(w -> w.getScene().getRoot().lookupAll(".text-area").stream())
                .filter(TextArea.class::isInstance).map(TextArea.class::cast).map(TextArea::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
    }
    private static <T>T fx(Callable<T> action) throws Exception { var task = new FutureTask<>(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS); }
}
