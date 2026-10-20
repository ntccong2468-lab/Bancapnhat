package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vncode.app.config.Database;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonProductLabelBarcodeTest {
    @TempDir Path temporaryDirectory;

    @BeforeEach void setUp() {
        System.setProperty("vncode.appdata.dir", temporaryDirectory.toString());
        Database.initDatabase();
    }

    @AfterEach void tearDown() { System.clearProperty("vncode.appdata.dir"); }

    @Test void actualOzonBarcodeWinsOverNewGtinRegardlessOfSortedOrder() throws Exception {
        assertEquals("OZN1234567890", OzonProductLabelBarcode.resolve(item("9001", "101", "offer-52"),
                product("9001", "101", "offer-52", List.of("04645588781154", "OZN1234567890"))));
        assertEquals("OZN1234567890", OzonProductLabelBarcode.resolve(item("9001", "101", "offer-52"),
                product("9001", "101", "offer-52", List.of("OZN1234567890", "04645588781154"))));
    }

    @Test void numericPostingProductIdMayBeSkuInsteadOfSellerProductId() throws Exception {
        assertEquals("OZN1234567890", OzonProductLabelBarcode.resolve(item("101", "101", "offer-52"),
                product("9001", "101", "offer-52", List.of("OZN1234567890"))));
    }

    @Test void singleRegisteredVendorBarcodeIsPrintedExactly() throws Exception {
        assertEquals("Vendor-00152-52", OzonProductLabelBarcode.resolve(item("9001", "101", "offer-52"),
                product("9001", "101", "offer-52", List.of("Vendor-00152-52"))));
    }

    @Test void aNativeBarcodeIsNotInferredFromVendorTextStartingWithOzn() {
        assertFailure("OZON_LABEL_BARCODE_AMBIGUOUS", item("9001", "101", "offer-52"),
                product("9001", "101", "offer-52", List.of("OZN-my-vendor-code", "04645588781154")));
    }

    @Test void missingMetadataAndEmptyBarcodesDoNotUseSkuOrOfferId() {
        assertFailure("OZON_LABEL_PRODUCT_MISSING", item("9001", "101", "offer-52"), null);
        assertFailure("OZON_LABEL_BARCODE_MISSING", item("9001", "101", "offer-52"),
                product("9001", "101", "offer-52", List.of()));
    }

    @Test void multipleRegisteredBarcodesAreNeverChosenArbitrarily() {
        assertFailure("OZON_LABEL_BARCODE_AMBIGUOUS", item("9001", "101", "offer-52"),
                product("9001", "101", "offer-52", List.of("OZN1111111111", "OZN2222222222")));
        assertFailure("OZON_LABEL_BARCODE_AMBIGUOUS", item("9001", "101", "offer-52"),
                product("9001", "101", "offer-52", List.of("vendor-A", "vendor-B")));
    }

    @Test void mismatchedSkuOfferOrProductCannotPrintAnotherVariantsBarcode() {
        assertFailure("OZON_LABEL_PRODUCT_MISMATCH", item("9001", "101", "offer-52"),
                product("9001", "102", "offer-52", List.of("OZN1111111111")));
        assertFailure("OZON_LABEL_PRODUCT_MISMATCH", item("9001", "101", "offer-52"),
                product("9001", "101", "offer-54", List.of("OZN1111111111")));
        assertFailure("OZON_LABEL_PRODUCT_MISMATCH", item("different-product", "101", "offer-52"),
                product("9001", "101", "offer-52", List.of("OZN1111111111")));
    }

    @Test void nonAsciiBarcodeCannotBeSilentlyAlteredForCode128() {
        assertFailure("OZON_LABEL_BARCODE_UNSUPPORTED", item("9001", "101", "offer-52"),
                product("9001", "101", "offer-52", List.of("товар-52")));
    }

    private static void assertFailure(String code, OzonPostingItemDto item, OzonProductDto product) {
        IOException failure = assertThrows(IOException.class, () -> OzonProductLabelBarcode.resolve(item, product));
        assertTrue(failure.getMessage().startsWith(code + ":"), failure.getMessage());
        assertTrue(failure.getMessage().contains("SKU: 101"));
    }

    private static OzonPostingItemDto item(String productId, String sku, String offerId) {
        return new OzonPostingItemDto(0, productId, sku, offerId, "Test product", 1, "RUB", "100");
    }

    private static OzonProductDto product(String productId, String sku, String offerId, List<String> barcodes) {
        return new OzonProductDto(productId, offerId, sku, "Test product", "", false, "", barcodes);
    }
}
