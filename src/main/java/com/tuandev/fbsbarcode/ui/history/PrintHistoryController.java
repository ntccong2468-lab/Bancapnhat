package com.tuandev.fbsbarcode.ui.history;

import com.tuandev.fbsbarcode.features.print.history.PrintHistoryItem;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryJobSummary;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

public class PrintHistoryController {
    @FXML
    private Label titleLabel;
    @FXML
    private Label emptyStateLabel;
    @FXML
    private TableView<PrintHistoryJobSummary> jobsTable;
    @FXML
    private TableColumn<PrintHistoryJobSummary, String> printedAtTC;
    @FXML
    private TableColumn<PrintHistoryJobSummary, String> supplyNameTC;
    @FXML
    private TableColumn<PrintHistoryJobSummary, String> supplyIdTC;
    @FXML
    private TableColumn<PrintHistoryJobSummary, Integer> itemCountTC;
    @FXML
    private TableColumn<PrintHistoryJobSummary, String> statusTC;
    @FXML
    private TableColumn<PrintHistoryJobSummary, Void> actionTC;
    private Consumer<PrintHistoryJobSummary> onReprint;

    @FXML
    public void initialize() {
        printedAtTC.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatTimestamp(cell.getValue().printedAt())));
        supplyNameTC.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().supplyName()));
        supplyIdTC.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().supplyId()));
        itemCountTC.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().itemCount()));
        statusTC.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().status()));
        actionTC.setCellFactory(col -> new TableCell<>() {
            private final Button button = new Button();
            {
                button.getStyleClass().add("btn-icon");
                button.getStyleClass().add("btn-primary");
                button.setPrefSize(32, 32);
                button.setMinSize(32, 32);
                button.setMaxSize(32, 32);
                button.setGraphic(new FontIcon("fth-printer:14:white"));
                button.setOnAction(event -> {
                    PrintHistoryJobSummary job = getTableRow().getItem();
                    if (job != null && job.canReprint() && onReprint != null) {
                        onReprint.accept(job);
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                PrintHistoryJobSummary job = getTableRow() == null ? null : (PrintHistoryJobSummary) getTableRow().getItem();
                if (empty || job == null) {
                    setGraphic(null);
                } else {
                    button.setDisable(!job.canReprint());
                    setGraphic(button);
                }
            }
        });
        applyTranslations();
    }

    public void setJobs(List<PrintHistoryJobSummary> jobs) {
        jobsTable.getItems().setAll(jobs == null ? List.of() : jobs);
        boolean hasJobs = jobs != null && !jobs.isEmpty();
        emptyStateLabel.setVisible(!hasJobs);
        emptyStateLabel.setManaged(!hasJobs);
        jobsTable.setVisible(hasJobs);
        jobsTable.setManaged(hasJobs);
    }

    public void setOnReprint(Consumer<PrintHistoryJobSummary> onReprint) {
        this.onReprint = onReprint;
    }

    public void applyTranslations() {
        I18nService i18n = I18nService.getInstance();
        titleLabel.setText(i18n.tr("history.title"));
        emptyStateLabel.setText(i18n.tr("history.empty"));
        printedAtTC.setText(i18n.tr("history.col.printed_at"));
        supplyNameTC.setText(i18n.tr("history.col.supply_name"));
        supplyIdTC.setText(i18n.tr("history.col.supply_id"));
        itemCountTC.setText(i18n.tr("history.col.item_count"));
        statusTC.setText(i18n.tr("history.col.status"));
    }

    private String formatTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        } catch (Exception ignored) {
            return value;
        }
    }
}
