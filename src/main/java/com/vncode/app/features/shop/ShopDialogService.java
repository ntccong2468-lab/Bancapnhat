package com.vncode.app.features.shop;

import com.vncode.app.models.Shop;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import com.vncode.app.ui.shop.ShopDialogController;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import java.util.Optional;

public class ShopDialogService {
    public Optional<Shop> showCreateDialog() {
        I18nService i18n = I18nService.getInstance();
        return showDialog(i18n.tr("shop_dialog.create_title"), i18n.tr("common.add"), new Shop());
    }

    public Optional<Shop> showUpdateDialog(Shop shop) {
        I18nService i18n = I18nService.getInstance();
        return showDialog(i18n.tr("shop_dialog.update_title"), i18n.tr("common.save"), shop);
    }

    private Optional<Shop> showDialog(String title, String submitLabel, Shop initialValue) {
        Dialog<ButtonType> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(title);

        FXMLLoader loader = FxmlViewLoader.loader(ShopDialogController.class, "shop-dialog.fxml");
        dialog.getDialogPane().setContent(FxmlViewLoader.load(loader));
        ShopDialogController controller = loader.getController();
        controller.setShop(initialValue);

        ButtonType submitBtn = new ButtonType(submitLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType(I18nService.getInstance().tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(cancelBtn, submitBtn);

        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(submitBtn);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!controller.validate()) {
                event.consume();
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != submitBtn) {
            return Optional.empty();
        }

        return Optional.of(controller.toShop());
    }
}
