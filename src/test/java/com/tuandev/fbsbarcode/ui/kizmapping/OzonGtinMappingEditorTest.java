package com.tuandev.fbsbarcode.ui.kizmapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tuandev.fbsbarcode.integration.ozon.OzonProductDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OzonGtinMappingEditorTest {
    @Test
    void searchCategoryAndGenderFiltersDefineWhatSelectAllWillSelect() {
        List<OzonProductDto> products = List.of(
                product("1", "SKU-1", "dress-red", "Dresses", "Women"),
                product("2", "SKU-2", "dress-blue", "Dresses", "Women"),
                product("3", "SKU-3", "shirt-black", "Shirts", "Men"));

        List<OzonProductDto> visible = OzonGtinMappingEditor.visibleProducts(
                products, "dress", "Dresses", "Women");

        assertEquals(List.of("dress-red", "dress-blue"),
                visible.stream().map(OzonProductDto::article).toList());
    }

    @Test
    void existingArticleSelectionIgnoresCaseAndStaleCatalogRules() {
        List<OzonProductDto> products = List.of(
                product("1", "SKU-1", "Dress-Red", "Dresses", "Women"));

        assertEquals(List.of("Dress-Red"), OzonGtinMappingEditor.selectedArticlesForGtin(
                products,
                Map.of("DRESS-RED", "04645588781154", "retired-article", "04645588781154"),
                "04645588781154"));
    }

    @Test
    void categoryFilterAcceptsMultipleSelectedCategories() {
        List<OzonProductDto> products = List.of(
                product("1", "SKU-1", "dress-red", "Dresses", "Women"),
                product("2", "SKU-2", "shirt-blue", "Shirts", "Men"),
                product("3", "SKU-3", "hat-black", "Hats", "Men"));

        assertEquals(List.of("dress-red", "shirt-blue"), OzonGtinMappingEditor.visibleProducts(
                        products, "", Set.of("Dresses", "Shirts"), "")
                .stream().map(OzonProductDto::article).toList());
    }

    private static OzonProductDto product(
            String id, String sku, String article, String category, String gender) {
        return new OzonProductDto(id, article, sku, "Product " + article, "", article,
                "", "", category, gender, false, "", List.of());
    }
}
