package com.tuandev.fbsbarcode.ui.ozon;

import com.tuandev.fbsbarcode.integration.ozon.OzonPostingDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingItemDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonRequirements;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OzonDashboardControllerTest {
    @Test
    void itemTextShowsEveryProductInAMultiProductPosting() {
        OzonPostingDto posting = posting(List.of(
                item(0, "SKU-1", "First product", 1),
                item(1, "SKU-2", "Second product", 1)));

        assertEquals("First product × 1\nSecond product × 1",
                OzonDashboardController.itemsText(posting));
    }

    @Test
    void itemTextKeepsQuantityForTwoUnitsOfOneProduct() {
        OzonPostingDto posting = posting(List.of(item(0, "SKU-1", "Product", 2)));

        assertEquals("Product × 2", OzonDashboardController.itemsText(posting));
        assertEquals(76.0, OzonDashboardController.orderRowHeight(posting));
    }

    @Test
    void multiProductPostingGetsEnoughHeightForEveryProduct() {
        OzonPostingDto posting = posting(List.of(
                item(0, "SKU-1", "First product", 1),
                item(1, "SKU-2", "Second product", 1)));

        assertEquals(140.0, OzonDashboardController.orderRowHeight(posting));
    }

    private static OzonPostingDto posting(List<OzonPostingItemDto> items) {
        return new OzonPostingDto("POST-1", "ORDER-1", "ORDER-1", "awaiting_packaging", "", "", "", "",
                "", "", new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false, items);
    }

    private static OzonPostingItemDto item(int index, String sku, String name, int quantity) {
        return new OzonPostingItemDto(index, sku, sku, sku, name, quantity, "RUB", "100");
    }
}
