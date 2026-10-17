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
    IOException launchFailure;

    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException running) { }
    }
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", data.toString()); Database.initDatabase();
        ConfigService.setConfigValue("update_api_url", "http://127.0.0.1:1");
        Path installer = data.resolve("downloaded-installer.exe"); Files.writeString(installer, "fixture-only");
        var service = new UpdateInstallerService() {
            @Override public Path downloadInstaller(UpdateInfo info, ProgressListener progress) {
                wrongThread.compareAndSet(false, Platform.isFxApplicationThread());
                progress.onProgress(100, 100); reached100.set(true); return installer;
            }
            @Override public void launchInstallerAfterExit(Path file) throws IOException {
                wrongThread.compareAndSet(false, Platform.isFxApplicationThread());
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
        fx(() -> { home.dispose(); for (var window : List.copyOf(Window.getWindows())) window.hide(); return null; });
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        System.clearProperty("vncode.appdata.dir");
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
    private void start() throws Exception {
        fx(() -> {
            var info = new UpdateInfo(); info.setVersion("1.4.8");
            var method = HomeController.class.getDeclaredMethod("startInstallerUpdate", UpdateInfo.class, boolean.class);
            method.setAccessible(true); method.invoke(home, info, false); return null;
        });
    }
    private Object field(String name) throws Exception { Field f = HomeController.class.getDeclaredField(name); f.setAccessible(true); return f.get(home); }
    private String diagnostics() {
        return Window.getWindows().stream().filter(Window::isShowing)
                .flatMap(w -> w.getScene().getRoot().lookupAll(".text-area").stream())
                .filter(TextArea.class::isInstance).map(TextArea.class::cast).map(TextArea::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
    }
    private static <T>T fx(Callable<T> action) throws Exception { var task = new FutureTask<>(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS); }
}
