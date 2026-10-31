package com.vncode.app.ui.shop;

import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.FxmlViewLoader;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShopSellerIsolationDialogTest {
    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    @Test void editRejectsDifferentSellerAndAllowsSameSellerKeyRotation() throws Exception {
        FutureTask<Void> run = new FutureTask<>(() -> {
            FXMLLoader loader = FxmlViewLoader.loader(ShopDialogController.class, "shop-dialog.fxml");
            FxmlViewLoader.load(loader);
            ShopDialogController controller = loader.getController();
            controller.setShop(new Shop(101, "A", Marketplace.OZON, "seller-a", "old-key"));
            TextField client = (TextField) loader.getNamespace().get("clientIdField");
            Label error = (Label) loader.getNamespace().get("errorLabel");
            client.setText("seller-b");
            assertFalse(controller.validate());
            assertTrue(error.isVisible());
            assertFalse(error.getText().isBlank());
            client.setText("seller-a");
            assertTrue(controller.validate());
            return null;
        });
        Platform.runLater(run);
        run.get(15, TimeUnit.SECONDS);
    }
}
