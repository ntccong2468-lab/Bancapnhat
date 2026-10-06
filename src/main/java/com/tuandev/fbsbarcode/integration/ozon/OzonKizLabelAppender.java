package com.tuandev.fbsbarcode.integration.ozon;

import com.itextpdf.barcodes.BarcodeDataMatrix;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.print.GenerateBarcode;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.models.Shop;
import java.io.IOException;
import java.util.List;

/** Appends one 58 x 40 mm physical DataMatrix label for each accepted Ozon exemplar. */
final class OzonKizLabelAppender {
    private static final float WIDTH = (float) PrintTemplateService.PAGE_WIDTH;
    private static final float HEIGHT = (float) PrintTemplateService.PAGE_HEIGHT;
    private static final PageSize PAGE_SIZE = new PageSize(WIDTH, HEIGHT);
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();

    int append(
            PdfDocument document,
            Shop shop,
            OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings) throws IOException {
        List<OzonProductDto> products = catalog.findAll(shop.getId());
        int appended = 0;
        for (OzonExemplarJobRepository.KizBinding binding : bindings) {
            OzonPostingItemDto item = item(posting, binding.itemIndex());
            OzonProductDto product = findProduct(products, item);
            String code = KizService.scannerSafeCode(binding.rawCode());
            if (code == null || code.isBlank()) {
                throw new IOException("An accepted Ozon exemplar has no printable KIZ.");
            }
            appendPage(document, item, product, code);
            appended++;
        }
        return appended;
    }

    private static void appendPage(
            PdfDocument document,
            OzonPostingItemDto item,
            OzonProductDto product,
            String code) throws IOException {
        PdfPage page = document.addNewPage(PAGE_SIZE);
        drawDataMatrix(page, code);

        try (Canvas canvas = new Canvas(page, PAGE_SIZE)) {
            canvas.setFont(GenerateBarcode.getArialFont());
            String name = first(product == null ? "" : product.name(), item.name());
            String article = first(product == null ? "" : product.article(), item.offerId());
            canvas.add(text(compact(name, 56), 72, 62, WIDTH - 78, 8, true));
            canvas.add(text("Article: " + compact(article, 30), 72, 38, WIDTH - 78, 7, true));
        }
    }

    private static OzonProductDto findProduct(List<OzonProductDto> products, OzonPostingItemDto item) {
        return products.stream().filter(product ->
                (!item.productId().isBlank() && item.productId().equals(product.productId()))
                        || (!item.sku().isBlank() && item.sku().equals(product.sku()))
                        || (!item.offerId().isBlank() && item.offerId().equals(product.offerId())))
                .findFirst().orElse(null);
    }

    private static Paragraph text(
            String value, float x, float y, float width, float size, boolean bold) {
        Paragraph paragraph = new Paragraph(value == null ? "" : value)
                .setMargin(0).setMultipliedLeading(0.9f).setFontSize(size)
                .setFixedPosition(x, y, width);
        return bold ? paragraph.setBold() : paragraph;
    }

    private static void drawDataMatrix(PdfPage page, String code) throws IOException {
        BarcodeDataMatrix matrix = new BarcodeDataMatrix();
        matrix.setOptions(BarcodeDataMatrix.DM_AUTO | BarcodeDataMatrix.DM_EXTENSION);
        matrix.setWs(1);
        // iText's documented `f.` extension prepends the FNC1 codeword required for GS1
        // DataMatrix while preserving ASCII GS separators already present in the KIZ payload.
        int status = matrix.setCode("f." + code);
        if (status != BarcodeDataMatrix.DM_NO_ERROR) {
            throw new IOException("An accepted Ozon KIZ cannot be encoded as GS1 DataMatrix.");
        }
        // PdfCanvas keeps every module printer-sharp; unlike createAwtImage(), it is not a
        // 36x36 bitmap stretched across the label. Source (iText 8.0.3):
        // https://api.itextpdf.com/iText/java/8.0.3/com/itextpdf/barcodes/BarcodeDataMatrix.html
        float moduleSide = 62f / (matrix.getWidth() + 2f * matrix.getWs());
        PdfCanvas canvas = new PdfCanvas(page);
        canvas.saveState().concatMatrix(1, 0, 0, 1, 7, 25);
        matrix.placeBarcode(canvas, ColorConstants.BLACK, moduleSide);
        canvas.restoreState();
    }

    private static OzonPostingItemDto item(OzonPostingDto posting, int itemIndex) throws IOException {
        return posting.items().stream()
                .filter(value -> value.itemIndex() == itemIndex)
                .findFirst()
                .orElseThrow(() -> new IOException("An accepted Ozon exemplar no longer matches a posting item."));
    }

    private static String compact(String value, int maximum) {
        String safe = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
        if (safe.length() <= maximum) return safe;
        return safe.substring(0, Math.max(0, maximum - 3)).stripTrailing() + "...";
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback) : preferred;
    }
}
