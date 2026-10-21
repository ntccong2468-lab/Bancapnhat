package com.vncode.app.integration.ozon;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.vncode.app.features.kiz.KizService;
import java.io.IOException;
import java.util.Objects;

/** Shared physical Ozon label layout for FBS, FBO and frozen print-history snapshots. */
public final class OzonCombinedLabelRenderer {
    private OzonCombinedLabelRenderer() { }

    /**
     * Appends one 58 x 40 mm page. The product barcode must come from the canonical product
     * metadata (or its previously verified frozen print snapshot), never from an SKU/offer ID.
     * Empty KIZ is permitted only for callers rendering an unmarked product's barcode page.
     * Callers retain responsibility for owner, license and KIZ reservation/publication guards.
     */
    public static void append(PdfDocument document, OzonPostingItemDto expectedItem,
            OzonProductDto canonicalProduct, String rawKiz) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(expectedItem, "expectedItem");
        OzonKizLabelAppender.appendProductPage(document, expectedItem, canonicalProduct,
                KizService.scannerSafeCode(rawKiz));
    }

    /** Resolves the exact registered product barcode before callers reserve or buy KIZ. */
    public static String barcodeFor(OzonPostingItemDto expectedItem, OzonProductDto canonicalProduct)
            throws IOException {
        return OzonProductLabelBarcode.resolve(Objects.requireNonNull(expectedItem, "expectedItem"),
                canonicalProduct);
    }
}
