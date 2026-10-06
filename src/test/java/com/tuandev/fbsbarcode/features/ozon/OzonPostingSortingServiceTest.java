package com.tuandev.fbsbarcode.features.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tuandev.fbsbarcode.features.supply.OrderSortOptions;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonPostingItemDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonRequirements;
import java.util.List;
import org.junit.jupiter.api.Test;

class OzonPostingSortingServiceTest {
    @Test
    void usesArticleColorAndSizeFromSynchronizedProductCard() {
        OzonPostingItemDto item = item("misleading-red-999", "Pants");
        OzonProductVariant variant = OzonProductVariant.from(item, product(
                item, "seller-article", "card-black", "62"));

        assertEquals("Pants", variant.product());
        assertEquals("seller-article", variant.article());
        assertEquals("card-black", variant.color());
        assertEquals("62", variant.size());
    }

    @Test
    void groupsPackingOrdersByProductArticleColorAndNaturalSizeLikeWb() {
        OzonPostingDto blue62 = posting("P-3", "offer-3");
        OzonPostingDto black68 = posting("P-1", "offer-1");
        OzonPostingDto black64 = posting("P-2", "offer-2");
        List<OzonProductDto> products = List.of(
                product(blue62.items().getFirst(), "pants", "blue", "62"),
                product(black68.items().getFirst(), "pants", "black", "68"),
                product(black64.items().getFirst(), "pants", "black", "64"));

        List<String> sorted = new OzonPostingSortingService()
                .sort(List.of(blue62, black68, black64), OrderSortOptions.defaultOptions(), products)
                .stream().map(OzonPostingDto::postingNumber).toList();

        assertEquals(List.of("P-2", "P-1", "P-3"), sorted);
    }

    private static OzonPostingDto posting(String postingNumber, String offerId) {
        return new OzonPostingDto(postingNumber, postingNumber, postingNumber, "awaiting_deliver", "", "", "", "",
                "", "", new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false,
                List.of(item(offerId, "Pants")));
    }

    private static OzonPostingItemDto item(String offerId, String name) {
        return new OzonPostingItemDto(0, offerId, offerId, offerId, name, 1, "RUB", "1");
    }

    private static OzonProductDto product(
            OzonPostingItemDto item, String article, String color, String size) {
        return new OzonProductDto(
                item.productId(), item.offerId(), item.sku(), item.name(), "",
                article, color, size, false, "", List.of());
    }
}
