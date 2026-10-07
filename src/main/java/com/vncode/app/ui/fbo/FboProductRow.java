package com.vncode.app.ui.fbo;

import com.vncode.app.features.fbo.FboBarcodePrintItem;
import com.vncode.app.features.fbo.FboProductSku;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

public class FboProductRow {
    private final FboProductSku product;
    private final IntegerProperty quantity = new SimpleIntegerProperty(0);

    public FboProductRow(FboProductSku product) {
        this.product = product;
    }

    public FboProductSku product() {
        return product;
    }

    public IntegerProperty quantityProperty() {
        return quantity;
    }

    public int getQuantity() {
        return quantity.get();
    }

    public void setQuantity(int value) {
        quantity.set(Math.max(0, value));
    }

    public FboBarcodePrintItem toPrintItem() {
        return new FboBarcodePrintItem(product, getQuantity());
    }
}
