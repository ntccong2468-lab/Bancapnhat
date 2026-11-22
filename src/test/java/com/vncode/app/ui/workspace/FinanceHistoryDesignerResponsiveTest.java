package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.features.print.PrintTemplate;
import com.vncode.app.features.print.PrintTemplateService;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FinanceHistoryDesignerResponsiveTest {
    @TempDir Path data;
    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException started) { }
    }
    private static <T> T fx(Callable<T> work) throws Exception {
        var task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
    private static FXMLLoader load(String path) throws Exception {
        var loader = FxmlViewLoader.loader(com.vncode.app.MainApplication.class, "/com/vncode/app/ui/" + path);
        loader.load();
        return loader;
    }
    private static Stage show(FXMLLoader loader, double width, double height) {
        Parent page = loader.getRoot();
        var stage = new Stage();
        stage.setScene(new Scene(page, width, height));
        ThemeService.applyTheme(stage.getScene());
        stage.show();
        page.applyCss();
        page.layout();
        return stage;
    }
    @Test void financeAndHistoryKeepColumnsAndCardsReachableAtNarrowWidth() throws Exception {
        System.setProperty("vncode.appdata.dir", data.toString());
        Database.initDatabase();
        try {
            fx(() -> {
                var finance = load("finance/finance-dashboard-view.fxml");
                var stage = show(finance, 760, 570);
                try {
                    var grid = (GridPane) finance.getNamespace().get("kpiGrid");
                    var scroll = (ScrollPane) ((Parent) finance.getRoot()).lookup(".finance-kpi-scroll");
                    assertNotNull(scroll);
                    assertEquals(10, grid.getChildren().size());
                    assertTrue(grid.getWidth() >= 950, "Cards retain usable widths and scroll horizontally");
                    assertNotEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getHbarPolicy());
                    assertNotEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getVbarPolicy());
                    var table = (TableView<?>) finance.getNamespace().get("dailyTable");
                    assertSame(TableView.UNCONSTRAINED_RESIZE_POLICY, table.getColumnResizePolicy());
                    assertTrue(table.getHeight() >= 100, "Financial table remains usable below the cards");
                } finally { stage.close(); ((com.vncode.app.ui.finance.FinanceDashboardController) finance.getController()).dispose(); }
                var history = load("history/print-history-view.fxml");
                var table = (TableView<?>) history.getNamespace().get("jobsTable");
                var controller = (com.vncode.app.ui.history.PrintHistoryController) history.getController();
                controller.setJobs(java.util.List.of(new com.vncode.app.features.print.history.PrintHistoryJobSummary(
                        1, 1, "Fixture", "shipment", "Shipment", "2026-01-01T00:00:00Z", 1,
                        null, "", "", "success", null)));
                var historyStage = show(history, 760, 570);
                try {
                    var page = (Parent) history.getRoot(); page.applyCss(); page.layout();
                    double totalWidth = table.getColumns().stream().mapToDouble(javafx.scene.control.TableColumn::getWidth).sum();
                    assertTrue(totalWidth <= table.getWidth(), "All history columns, including actions, fit at compact width");
                    assertTrue(table.getColumns().getLast().getWidth() >= 185, "Reprint and reconciliation actions remain visible");
                } finally { historyStage.close(); }
                return null;
            });
        } finally { System.clearProperty("vncode.appdata.dir"); }
    }
    @Test void designerKeepsControlsReachableWithoutResizingTheLabel() throws Exception {
        System.setProperty("vncode.appdata.dir", data.toString());
        Database.initDatabase();
        try {
            fx(() -> {
                var designer = load("print/print-template-designer-view.fxml");
                var stage = show(designer, 760, 570);
                try {
                    var root = (BorderPane) designer.getRoot();
                    var outer = (ScrollPane) root.getCenter();
                    assertNotEquals(ScrollPane.ScrollBarPolicy.NEVER, outer.getHbarPolicy());
                    var list = (ListView<?>) designer.getNamespace().get("elementListView");
                    assertTrue(list.getWidth() >= 100, "The list of label elements stays readable");
                    var preview = (AnchorPane) designer.getNamespace().get("previewPane");
                    double width = preview.getPrefWidth(), height = preview.getPrefHeight();
                    assertTrue(width > 0 && height > 0);
                    stage.setWidth(1100);
                    root.applyCss(); root.layout();
                    assertEquals(width, preview.getPrefWidth(), 0.01);
                    assertEquals(height, preview.getPrefHeight(), 0.01);
                } finally { stage.close(); }
                return null;
            });
        } finally { System.clearProperty("vncode.appdata.dir"); }
    }

    @Test void designerKeepsEditedNonDefaultTemplateSelectedAfterSaveAndReset() throws Exception {
        System.setProperty("vncode.appdata.dir", data.toString());
        Database.initDatabase();
        try {
            fx(() -> {
                var templates = new PrintTemplateService();
                var defaultId = templates.getDefaultTemplate().getId();
                var custom = templates.createTemplate("Custom fixture");
                var designer = load("print/print-template-designer-view.fxml");
                var stage = show(designer, 1100, 650);
                try {
                    var root = (Parent) designer.getRoot();
                    var choice = (ComboBox<?>) designer.getNamespace().get("templateComboBox");
                    var elements = (ListView<?>) designer.getNamespace().get("elementListView");
                    choice.getSelectionModel().select(1);
                    assertEquals(custom.getId(), ((PrintTemplate) choice.getValue()).getId());
                    elements.getSelectionModel().select(1);
                    ((TextField) designer.getNamespace().get("labelField")).setText("Edited fixture label");
                    designerAction(root, "template.save").fire();
                    assertEquals("Edited fixture label", templates.findById(custom.getId()).orElseThrow().getElements().get(1).getLabel());
                    assertEquals(custom.getId(), ((PrintTemplate) choice.getValue()).getId(), "Saving keeps the edited template selected");
                    designerAction(root, "template.reset").fire();
                    assertEquals(custom.getId(), ((PrintTemplate) choice.getValue()).getId(), "Resetting keeps the edited template selected");
                    assertEquals(custom.getElements().get(1).getLabel(), templates.findById(custom.getId()).orElseThrow().getElements().get(1).getLabel());
                    assertEquals(defaultId, templates.getDefaultTemplate().getId());
                } finally { stage.close(); }
                return null;
            });
        } finally { System.clearProperty("vncode.appdata.dir"); }
    }

    private static Button designerAction(Parent root, String key) {
        return root.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> ("i18n:" + key).equals(button.getUserData())).findFirst().orElseThrow();
    }
}
