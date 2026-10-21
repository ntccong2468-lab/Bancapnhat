package com.vncode.app.shared;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Keeps registration alive after a window close, only while a usable tray control exists. */
public final class RegistrationBackgroundLifetime implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationBackgroundLifetime.class);

    interface Window {
        boolean implicitExit();
        void implicitExit(boolean enabled);
        void hide();
        void restore();
        void exit();
    }

    interface Tray {
        /** Completion and actions must be delivered on the window's event thread. */
        void install(Runnable open, Runnable exit, Consumer<Boolean> completion);
        void notifyBackground();
        /** Removes the icon and cancels any installation still in progress. */
        void remove();
    }

    private enum State { VISIBLE, INSTALLING, HIDDEN, CLOSED }

    private final Window window;
    private final Tray tray;
    private final BooleanSupplier registrationPending;
    private final BooleanSupplier exitAllowed;
    private final Runnable exitBlocked;
    private final Runnable trayUnavailable;
    private State state = State.VISIBLE;
    private boolean savedImplicitExit;
    private boolean hasSavedImplicitExit;
    private long installation;

    RegistrationBackgroundLifetime(Window window, Tray tray, BooleanSupplier registrationPending,
                                   BooleanSupplier exitAllowed, Runnable exitBlocked, Runnable trayUnavailable) {
        this.window = Objects.requireNonNull(window);
        this.tray = Objects.requireNonNull(tray);
        this.registrationPending = Objects.requireNonNull(registrationPending);
        this.exitAllowed = Objects.requireNonNull(exitAllowed);
        this.exitBlocked = Objects.requireNonNull(exitBlocked);
        this.trayUnavailable = Objects.requireNonNull(trayUnavailable);
    }

    public static RegistrationBackgroundLifetime attach(Stage stage, String appName,
                                                        BooleanSupplier registrationPending,
                                                        BooleanSupplier exitAllowed,
                                                        Runnable exitBlocked, Runnable trayUnavailable) {
        I18nService i18n = I18nService.getInstance();
        Tray tray = new AwtTray(appName,
                i18n.tr("app.background.open", "Mở VNCode"),
                i18n.tr("app.background.exit", "Thoát VNCode"),
                i18n.tr("app.background.title", "Đăng ký Znack chạy nền"),
                i18n.tr("app.background.content", "VNCode vẫn tiếp tục đăng ký thẻ. Mở lại ứng dụng từ biểu tượng ở khay hệ thống. Nếu thoát hẳn, các thẻ còn lại sẽ tiếp tục khi mở VNCode."));
        Window window = new Window() {
            public boolean implicitExit() { return Platform.isImplicitExit(); }
            public void implicitExit(boolean enabled) { Platform.setImplicitExit(enabled); }
            public void hide() { stage.hide(); }
            public void restore() {
                stage.show();
                stage.setIconified(false);
                stage.toFront();
                stage.requestFocus();
            }
            public void exit() { Platform.exit(); }
        };
        return new RegistrationBackgroundLifetime(window, tray, registrationPending, exitAllowed,
                exitBlocked, trayUnavailable);
    }

    /** Returns true when the close request must be consumed. Never hides before tray installation. */
    public boolean requestBackground() {
        if (state == State.CLOSED) return false;
        if (state == State.HIDDEN || state == State.INSTALLING) return true;
        if (!registrationPending.getAsBoolean()) return false;
        state = State.INSTALLING;
        long attempt = ++installation;
        try {
            tray.install(this::restore, this::requestExit, success -> installed(attempt, success));
        } catch (RuntimeException failure) {
            LOG.warn("Unable to install background registration tray", failure);
            installed(attempt, false);
        }
        return true;
    }

    private void installed(long attempt, boolean success) {
        if (state != State.INSTALLING || installation != attempt) return;
        if (!success) {
            state = State.VISIBLE;
            tray.remove();
            trayUnavailable.run();
            return;
        }
        if (!registrationPending.getAsBoolean()) {
            state = State.VISIBLE;
            tray.remove();
            return;
        }
        savedImplicitExit = window.implicitExit();
        hasSavedImplicitExit = true;
        window.implicitExit(false);
        state = State.HIDDEN;
        window.hide();
        tray.notifyBackground();
    }

    public void restore() {
        if (state == State.CLOSED) return;
        installation++;
        state = State.VISIBLE;
        // Show the stage before restoring implicit exit; otherwise zero visible windows could exit FX.
        window.restore();
        restoreImplicitExit();
        tray.remove();
    }

    private void requestExit() {
        if (state == State.CLOSED) return;
        if (!exitAllowed.getAsBoolean()) {
            restore();
            exitBlocked.run();
            return;
        }
        close();
        window.exit();
    }

    private void restoreImplicitExit() {
        if (hasSavedImplicitExit) {
            hasSavedImplicitExit = false;
            window.implicitExit(savedImplicitExit);
        }
    }

    /** Application.stop calls this, including updater exits, without running a close-request guard. */
    @Override public void close() {
        if (state == State.CLOSED) return;
        installation++;
        state = State.CLOSED;
        tray.remove();
        restoreImplicitExit();
    }

    private static final class AwtTray implements Tray {
        private final String appName;
        private final String openText;
        private final String exitText;
        private final String notificationTitle;
        private final String notificationContent;
        private final AtomicLong revision = new AtomicLong();
        // Only accessed on the AWT event thread.
        private TrayIcon icon;

        private AwtTray(String appName, String openText, String exitText,
                        String notificationTitle, String notificationContent) {
            this.appName = appName;
            this.openText = openText;
            this.exitText = exitText;
            this.notificationTitle = notificationTitle;
            this.notificationContent = notificationContent;
        }

        @Override public void install(Runnable open, Runnable exit, Consumer<Boolean> completion) {
            long installRevision = revision.incrementAndGet();
            EventQueue.invokeLater(() -> {
                if (revision.get() != installRevision) return;
                boolean installed = false;
                try {
                    if (SystemTray.isSupported()) {
                        removeIcon();
                        var image = ImageIO.read(RegistrationBackgroundLifetime.class.getResource(
                                "/com/vncode/app/assets/images/logo.png"));
                        if (image != null) {
                            PopupMenu menu = new PopupMenu();
                            MenuItem openItem = new MenuItem(openText);
                            openItem.addActionListener(event -> dispatch(open));
                            MenuItem exitItem = new MenuItem(exitText);
                            exitItem.addActionListener(event -> dispatch(exit));
                            menu.add(openItem);
                            menu.addSeparator();
                            menu.add(exitItem);
                            icon = new TrayIcon(image, appName, menu);
                            icon.setImageAutoSize(true);
                            icon.addActionListener(event -> dispatch(open));
                            SystemTray.getSystemTray().add(icon);
                            installed = true;
                        }
                    }
                } catch (Exception | LinkageError failure) {
                    LOG.warn("System tray unavailable for background registration", failure);
                }
                if (revision.get() != installRevision) {
                    removeIcon();
                    return;
                }
                boolean ready = installed;
                if (!dispatch(() -> completion.accept(ready))) remove();
            });
        }

        @Override public void notifyBackground() {
            EventQueue.invokeLater(() -> {
                if (icon != null) icon.displayMessage(notificationTitle, notificationContent, TrayIcon.MessageType.INFO);
            });
        }

        @Override public void remove() {
            revision.incrementAndGet();
            EventQueue.invokeLater(this::removeIcon);
        }

        private void removeIcon() {
            if (icon != null) {
                SystemTray.getSystemTray().remove(icon);
                icon = null;
            }
        }

        private boolean dispatch(Runnable action) {
            try {
                Platform.runLater(action);
                return true;
            } catch (IllegalStateException stopped) {
                return false;
            }
        }
    }
}
