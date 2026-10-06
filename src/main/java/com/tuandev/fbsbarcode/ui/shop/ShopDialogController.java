package com.tuandev.fbsbarcode.ui.shop;

import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ComboBox;

public class ShopDialogController {
    @FXML
    private TextField nameField;

    @FXML
    private ComboBox<Marketplace> marketplaceField;

    @FXML
    private Label clientIdLabel;

    @FXML
    private TextField clientIdField;

    @FXML
    private Label apiKeyLabel;

    @FXML
    private PasswordField apiKeyField;

    @FXML
    private Label errorLabel;

    private boolean editing;

    @FXML
    public void initialize() {
        marketplaceField.getItems().setAll(Marketplace.values());
        marketplaceField.getSelectionModel().select(Marketplace.WILDBERRIES);
        marketplaceField.valueProperty().addListener((obs, oldValue, newValue) -> updateMarketplaceFields());
        nameField.textProperty().addListener((obs, oldVal, newVal) -> {
            clearErrorState(nameField);
        });
        apiKeyField.textProperty().addListener((obs, oldVal, newVal) -> {
            clearErrorState(apiKeyField);
        });
        clientIdField.textProperty().addListener((obs, oldVal, newVal) -> clearErrorState(clientIdField));
        updateMarketplaceFields();
    }

    public void setShop(Shop shop) {
        editing = shop.getId() > 0;
        nameField.setText(shop.getName());
        marketplaceField.setValue(shop.getMarketplace());
        marketplaceField.setDisable(editing);
        clientIdField.setText(shop.getClientId());
        // Credentials are write-only. Blank on edit means retain the current secret.
        apiKeyField.clear();
        updateMarketplaceFields();
    }

    public Shop toShop() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        Marketplace marketplace = marketplaceField.getValue() == null
                ? Marketplace.WILDBERRIES : marketplaceField.getValue();
        String clientId = clientIdField.getText() == null ? "" : clientIdField.getText().trim();
        String apiKey = apiKeyField.getText() == null ? "" : apiKeyField.getText().trim();
        return new Shop(name, marketplace, clientId, apiKey);
    }

    public boolean validate() {
        resetValidationState();

        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        Marketplace marketplace = marketplaceField.getValue() == null
                ? Marketplace.WILDBERRIES : marketplaceField.getValue();
        String clientId = clientIdField.getText() == null ? "" : clientIdField.getText().trim();
        String apiKey = apiKeyField.getText() == null ? "" : apiKeyField.getText().trim();
        I18nService i18n = I18nService.getInstance();

        if (name.isEmpty()) {
            showErrorState(nameField, i18n.tr("shop_dialog.validation.name_empty"));
            return false;
        }

        if (marketplace == Marketplace.OZON && clientId.isEmpty()) {
            showErrorState(clientIdField, i18n.tr("shop_dialog.validation.client_id_empty"));
            return false;
        }

        if (apiKey.isEmpty() && !editing) {
            showErrorState(apiKeyField, i18n.tr("shop_dialog.validation.api_key_empty"));
            return false;
        }

        return true;
    }

    private void resetValidationState() {
        clearErrorState(nameField);
        clearErrorState(clientIdField);
        clearErrorState(apiKeyField);
        if (errorLabel != null) {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            errorLabel.setText("");
        }
    }

    private void showErrorState(TextField field, String message) {
        if (!field.getStyleClass().contains("error")) {
            field.getStyleClass().add("error");
        }
        if (errorLabel != null) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }
        field.requestFocus();
    }

    private void clearErrorState(TextField field) {
        field.getStyleClass().remove("error");
        if (errorLabel != null && !nameField.getStyleClass().contains("error")
                && !clientIdField.getStyleClass().contains("error")
                && !apiKeyField.getStyleClass().contains("error")) {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            errorLabel.setText("");
        }
    }

    private void updateMarketplaceFields() {
        boolean ozon = marketplaceField.getValue() == Marketplace.OZON;
        clientIdLabel.setVisible(ozon);
        clientIdLabel.setManaged(ozon);
        clientIdField.setVisible(ozon);
        clientIdField.setManaged(ozon);
        apiKeyLabel.setText(ozon ? "Ozon API Key *" : "Wildberries API Token *");
        apiKeyField.setPromptText(editing ? "Leave blank to keep current credential" : "");
    }
}
