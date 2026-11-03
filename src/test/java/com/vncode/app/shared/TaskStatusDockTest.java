package com.vncode.app.shared;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TaskStatusDockTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path profile;
    @org.junit.jupiter.api.BeforeEach void isolatedProfile() {
        System.setProperty("vncode.appdata.dir", profile.toString());
        com.vncode.app.config.Database.initDatabase();
    }
    @org.junit.jupiter.api.AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }
    @BeforeAll static void start() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }

    @Test void wrappedProgressReservesSpaceAndDismissalRestoresTheSameSelectedList() throws Exception {
        var task = new FutureTask<Void>(() -> {
            var rows = new ListView<String>();
            rows.getItems().setAll("first", "selected", "last"); rows.getSelectionModel().select(1);
            var items = rows.getItems(); var host = new StackPane(rows);
            Insets original = new Insets(3, 4, 5, 6); StackPane.setMargin(rows, original);
            var stage = new Stage(); stage.setScene(new Scene(host, 420, 360)); stage.show();
            try {
                TaskStatusDock.install(host); TaskStatusDock.install(host);
                var status = new Label("Sending confirmed requests. ".repeat(12));
                status.setWrapText(true); status.setMinWidth(0); status.setMaxSize(Double.MAX_VALUE, Region.USE_PREF_SIZE);
                status.getStyleClass().add("task-status-pill"); StackPane.setMargin(status, new Insets(12)); StackPane.setAlignment(status, Pos.BOTTOM_CENTER);
                host.getChildren().add(status); layout(host);
                assertTrue(rows.getBoundsInParent().getMaxY() <= status.getBoundsInParent().getMinY(), "Progress must not cover a list row");
                double before = rows.getHeight();
                status.setText("Finished"); layout(host);
                assertTrue(rows.getHeight() > before, "Shorter feedback must return space to the list");
                status.setVisible(false); layout(host); assertEquals(original, StackPane.getMargin(rows));
                status.setVisible(true); layout(host); assertNotEquals(original, StackPane.getMargin(rows));
                host.getChildren().remove(status); layout(host);
                assertEquals(original, StackPane.getMargin(rows));
                assertSame(items, rows.getItems()); assertEquals("selected", rows.getSelectionModel().getSelectedItem());
                status.setText("Detached status must no longer affect the list"); layout(host);
                assertEquals(original, StackPane.getMargin(rows));
            } finally { stage.close(); }
            return null;
        }); Platform.runLater(task); task.get(15, TimeUnit.SECONDS);
    }

    @Test void initiallyAbsentMarginIsRestoredAfterReplacementStatus() throws Exception {
        var task = new FutureTask<Void>(() -> {
            var content = new Region(); var host = new StackPane(content);
            new Scene(host, 400, 300); host.resize(400, 300);
            TaskStatusDock.install(host);
            for (int i = 0; i < 3; i++) {
                var status = new Label("Task " + i); status.getStyleClass().add("task-status-pill");
                status.setMaxHeight(Region.USE_PREF_SIZE); host.getChildren().add(status); layout(host);
                assertNotNull(StackPane.getMargin(content));
                host.getChildren().remove(status); assertNull(StackPane.getMargin(content));
            }
            return null;
        }); Platform.runLater(task); task.get(15, TimeUnit.SECONDS);
    }
    private static void layout(StackPane host) { for (int i = 0; i < 4; i++) { host.applyCss(); host.layout(); } }
}
