package com.vncode.app.shared;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationBackgroundLifetimeTest {
    private final FakeWindow window = new FakeWindow();
    private final FakeTray tray = new FakeTray();
    private final AtomicBoolean registrationPending = new AtomicBoolean(true);
    private final AtomicBoolean exitAllowed = new AtomicBoolean(true);
    private final AtomicInteger exitBlocked = new AtomicInteger();
    private final AtomicInteger trayUnavailable = new AtomicInteger();
    private final RegistrationBackgroundLifetime lifetime = new RegistrationBackgroundLifetime(
            window, tray, registrationPending::get, exitAllowed::get,
            exitBlocked::incrementAndGet, trayUnavailable::incrementAndGet);

    @Test void hidesOnlyAfterTrayIsReadyAndKeepsFxAlive() {
        assertTrue(lifetime.requestBackground());
        assertTrue(window.visible, "Installing a tray must not leave the seller without a window or tray");
        assertTrue(window.implicitExit);
        assertTrue(lifetime.requestBackground());
        assertEquals(1, tray.completions.size(), "Repeated X clicks must not install duplicate icons");

        tray.complete(0, true);
        assertFalse(window.visible);
        assertFalse(window.implicitExit);
        assertEquals(1, tray.notifications);
        assertEquals(List.of("implicit:false", "hide"), window.events);
    }

    @Test void openingFromTrayShowsWindowBeforeRestoringImplicitExit() {
        lifetime.requestBackground();
        tray.complete(0, true);
        window.events.clear();

        tray.open.run();

        assertTrue(window.visible);
        assertTrue(window.implicitExit);
        assertEquals(List.of("restore", "implicit:true"), window.events);
        assertEquals(1, tray.removals);
    }

    @Test void unavailableTrayLeavesWindowVisibleAndReportsFallback() {
        lifetime.requestBackground();
        tray.complete(0, false);

        assertTrue(window.visible);
        assertTrue(window.implicitExit);
        assertEquals(1, trayUnavailable.get());
        assertEquals(0, window.exits);
        assertEquals(1, tray.removals);
    }

    @Test void completionDuringTrayStartupDoesNotHideFinishedWork() {
        lifetime.requestBackground();
        registrationPending.set(false);
        tray.complete(0, true);

        assertTrue(window.visible);
        assertTrue(window.implicitExit);
        assertEquals(0, tray.notifications);
        assertEquals(1, tray.removals);
    }

    @Test void nonRegistrationCloseKeepsExistingApplicationClosePolicy() {
        registrationPending.set(false);
        assertFalse(lifetime.requestBackground());
        assertTrue(tray.completions.isEmpty());
        assertTrue(window.visible);
    }

    @Test void explicitTrayExitStopsRuntimeInsteadOfOnlyClosingWindow() {
        lifetime.requestBackground();
        tray.complete(0, true);
        tray.exit.run();

        assertEquals(1, window.exits);
        assertTrue(window.implicitExit);
        assertEquals(1, tray.removals);
        assertFalse(lifetime.requestBackground());
    }

    @Test void otherBackgroundWorkStillBlocksTrayExitAndRestoresVisibleWarning() {
        lifetime.requestBackground();
        tray.complete(0, true);
        exitAllowed.set(false);
        tray.exit.run();

        assertTrue(window.visible);
        assertTrue(window.implicitExit);
        assertEquals(1, exitBlocked.get());
        assertEquals(0, window.exits);
    }

    @Test void updaterStopCleansTrayWithoutConsultingBusyGuard() {
        lifetime.requestBackground();
        tray.complete(0, true);
        exitAllowed.set(false);
        lifetime.close();
        lifetime.close();

        assertEquals(1, tray.removals);
        assertEquals(0, exitBlocked.get());
        assertTrue(window.implicitExit);
        assertEquals(0, window.exits, "Application.stop is already exiting and needs no second exit request");
    }

    @Test void closedApplicationCannotBeHiddenByLateTrayCallback() {
        lifetime.requestBackground();
        lifetime.close();
        tray.complete(0, true);

        assertTrue(window.visible);
        assertTrue(window.implicitExit);
        assertEquals(0, tray.notifications);
        assertEquals(1, tray.removals);
    }

    @Test void staleInstallCallbackCannotHideASecondCloseAttempt() {
        lifetime.requestBackground();
        lifetime.restore();
        lifetime.requestBackground();
        tray.complete(0, true);
        assertTrue(window.visible);
        assertTrue(window.implicitExit);

        tray.complete(1, true);
        assertFalse(window.visible);
        assertFalse(window.implicitExit);
        assertEquals(1, tray.notifications);
    }

    @Test void restoresAnAlreadyDisabledImplicitExitSetting() {
        window.implicitExit = false;
        lifetime.requestBackground();
        tray.complete(0, true);
        tray.open.run();
        assertTrue(window.visible);
        assertFalse(window.implicitExit);
    }

    private static final class FakeWindow implements RegistrationBackgroundLifetime.Window {
        boolean visible = true;
        boolean implicitExit = true;
        int exits;
        final List<String> events = new ArrayList<>();

        public boolean implicitExit() { return implicitExit; }
        public void implicitExit(boolean enabled) {
            implicitExit = enabled;
            events.add("implicit:" + enabled);
        }
        public void hide() { visible = false; events.add("hide"); }
        public void restore() { visible = true; events.add("restore"); }
        public void exit() { exits++; }
    }

    private static final class FakeTray implements RegistrationBackgroundLifetime.Tray {
        Runnable open;
        Runnable exit;
        final List<Consumer<Boolean>> completions = new ArrayList<>();
        int notifications;
        int removals;

        public void install(Runnable open, Runnable exit, Consumer<Boolean> completion) {
            this.open = open;
            this.exit = exit;
            completions.add(completion);
        }
        public void notifyBackground() { notifications++; }
        public void remove() { removals++; }
        void complete(int index, boolean success) { completions.get(index).accept(success); }
    }
}
