package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.Optional;

public class PrintOptionsDialogService {
    private final PrintPreferenceService preferenceService = new PrintPreferenceService();

    public Optional<PrintJobOptions> chooseOptions() {
        PrintJobOptions saved = preferenceService.load();

        Dialog<ButtonType> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        I18nService i18n = I18nService.getInstance();
        dialog.setTitle(i18n.tr("print_options.title"));
        dialog.setHeaderText(null);
        dialog.getDialogPane().setPadding(new Insets(18));
        dialog.getDialogPane().setMinWidth(460);

        ButtonType confirmButton = new ButtonType(i18n.tr("print_options.confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType(i18n.tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(confirmButton, cancelButton);

        ComboBox<PrintPageOrder> pageOrderComboBox = new ComboBox<>();
        pageOrderComboBox.getItems().setAll(PrintPageOrder.values());
        pageOrderComboBox.setValue(saved.pageOrder());
        pageOrderComboBox.setMaxWidth(Double.MAX_VALUE);

        TextField barcodeCopiesField = new TextField(String.valueOf(saved.barcodeCopies()));
        barcodeCopiesField.setPromptText("1");

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("error-label");
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);

        VBox content = new VBox(10,
                new Label(i18n.tr("print_options.page_order")),
                pageOrderComboBox,
                new Label(i18n.tr("print_options.barcode_copies")),
                barcodeCopiesField,
                errorLabel
        );
        content.getStyleClass().add("dialog-content");
        content.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(content);

        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(confirmButton);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            PrintJobOptions options = parseOptions(pageOrderComboBox.getValue(), barcodeCopiesField.getText());
            if (options == null) {
                errorLabel.setText(i18n.tr("print_options.invalid_copies"));
                errorLabel.setManaged(true);
                errorLabel.setVisible(true);
                barcodeCopiesField.requestFocus();
                barcodeCopiesField.selectAll();
                event.consume();
                return;
            }
            preferenceService.save(options);
        });

        return dialog.showAndWait()
                .filter(result -> result == confirmButton)
                .map(result -> parseOptions(pageOrderComboBox.getValue(), barcodeCopiesField.getText()))
                .map(PrintJobOptions::normalized);
    }

    private PrintJobOptions parseOptions(PrintPageOrder pageOrder, String barcodeCopiesText) {
        try {
            int barcodeCopies = Integer.parseInt(barcodeCopiesText == null ? "" : barcodeCopiesText.trim());
            if (barcodeCopies < 1) {
                return null;
            }
            return new PrintJobOptions(pageOrder, barcodeCopies).normalized();
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
