package com.vncode.app.integration.ozon;

import com.vncode.app.shared.I18nService;
import java.io.IOException;
import java.util.List;

/** Selects a barcode actually registered on the exact Ozon product, never an SKU substitute. */
final class OzonProductLabelBarcode {
    private OzonProductLabelBarcode() { }

    static String resolve(OzonPostingItemDto item, OzonProductDto product) throws IOException {
        if (product == null) {
            throw failure("OZON_LABEL_PRODUCT_MISSING", "ozon.label.product.missing",
                    "Chưa có dữ liệu sản phẩm Ozon để in nhãn. Đồng bộ lại Sản phẩm rồi in lại.", item);
        }
        // Posting/exemplar product_id can be the numeric SKU, whereas /product/info uses the
        // seller's product ID. Both API identities are valid only for this exact SKU/offer.
        if (item.sku().isBlank() || !item.sku().equals(product.sku())
                || (!item.productId().isBlank() && !item.productId().equals(product.productId())
                    && !item.productId().equals(item.sku()))
                || (!item.offerId().isBlank() && !product.offerId().isBlank()
                    && !item.offerId().equals(product.offerId()))) {
            throw failure("OZON_LABEL_PRODUCT_MISMATCH", "ozon.label.product.mismatch",
                    "Dữ liệu sản phẩm không khớp với đơn Ozon. Đồng bộ lại đơn và Sản phẩm rồi in lại.", item);
        }
        List<String> barcodes = product.barcodes();
        if (barcodes.isEmpty()) {
            throw failure("OZON_LABEL_BARCODE_MISSING", "ozon.label.barcode.missing",
                    "Sản phẩm chưa có barcode Ozon. Bổ sung barcode trên Ozon, đồng bộ lại Sản phẩm rồi in lại.", item);
        }
        // ADD GTIN preserves Ozon's existing OZN barcode. Repository order is lexical, so the
        // newly added numeric GTIN can sort first and must not silently replace that barcode.
        List<String> nativeBarcodes = barcodes.stream()
                .filter(value -> value.matches("(?i)OZN[0-9]+"))
                .toList();
        List<String> candidates = nativeBarcodes.isEmpty() ? barcodes : nativeBarcodes;
        if (candidates.size() != 1) {
            throw failure("OZON_LABEL_BARCODE_AMBIGUOUS", "ozon.label.barcode.ambiguous",
                    "Có nhiều barcode sản phẩm Ozon nên chưa thể xác định mã để in. Kiểm tra barcode của SKU trên Ozon rồi đồng bộ lại Sản phẩm.", item);
        }
        String barcode = candidates.getFirst();
        if (!barcode.matches("[\\x20-\\x7E]+")) {
            throw failure("OZON_LABEL_BARCODE_UNSUPPORTED", "ozon.label.barcode.unsupported",
                    "Barcode sản phẩm chứa ký tự không in được bằng Code 128. Kiểm tra barcode trên Ozon rồi đồng bộ lại Sản phẩm.", item);
        }
        return barcode;
    }

    static IOException failure(String code, String key, String fallback, OzonPostingItemDto item) {
        return new IOException(code + ": " + I18nService.getInstance().tr(key, fallback)
                + " [SKU: " + item.sku() + "]");
    }
}
