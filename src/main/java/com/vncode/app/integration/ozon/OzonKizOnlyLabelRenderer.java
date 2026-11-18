package com.vncode.app.integration.ozon;

import com.itextpdf.barcodes.BarcodeDataMatrix;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.print.GenerateBarcode;
import com.vncode.app.features.print.PrintTemplateService;
import java.io.IOException;

/** Independent fallback label; the official carrier/combined layout is not changed. */
final class OzonKizOnlyLabelRenderer {
    private static final float WIDTH = (float) PrintTemplateService.PAGE_WIDTH;
    private static final float HEIGHT = (float) PrintTemplateService.PAGE_HEIGHT;
    private static final float MATRIX_SIDE = 58;

    void append(PdfDocument document, OzonPostingItemDto item, OzonProductDto product,
            String rawCode, String postingNumber, int sequence, int slot, int slots) throws IOException {
        var matrix = matrix(rawCode);
        float dot = 72f / 203;
        float module = module(matrix);
        var page = document.addNewPage(new PageSize(WIDTH, HEIGHT));
        var graphics = new PdfCanvas(page);
        float x = Math.round(6 / dot) * dot;
        float y = Math.round((HEIGHT - 17 - module * (matrix.getHeight() + 2f)) / dot) * dot;
        graphics.saveState().concatMatrix(1, 0, 0, 1, x, y);
        matrix.placeBarcode(graphics, ColorConstants.BLACK, module);
        graphics.restoreState();
        try (var canvas = new Canvas(page, page.getPageSize())) {
            var font = GenerateBarcode.getArialFont(document);
            canvas.setFont(font);
            canvas.add(text(first(product.name(), item.name()), 70, HEIGHT - 39, WIDTH - 76, 7).setHeight(29));
            canvas.add(text("Art: " + first(product.article(), item.offerId()), 70, HEIGHT - 58, WIDTH - 76, 7).setHeight(18));
            canvas.add(text("Size: " + first(product.russianSize(), product.size()), 70, HEIGHT - 72, WIDTH - 76, 8).setHeight(12).setBold());
            canvas.add(text("Color: " + product.color(), 70, HEIGHT - 88, WIDTH - 76, 7).setHeight(15));
            String footer = "#" + sequence + " · " + postingNumber + (slots > 1 ? " · " + slot + "/" + slots : "");
            float size = 8;
            while (size > 5 && font.getWidth(footer, size) > WIDTH - 12) size -= .25f;
            if (font.getWidth(footer, size) > WIDTH - 12) throw new IOException("OZON_KIZ_ONLY_POSTING_TOO_LONG");
            canvas.add(text(footer, 6, 4, WIDTH - 12, size).setHeight(13).setBold()
                    .setTextAlignment(TextAlignment.CENTER));
        }
        graphics.saveState().setStrokeColor(ColorConstants.BLACK).setLineWidth(.4f)
                .rectangle(5, 3, WIDTH - 10, 15).stroke().restoreState();
    }

    void validate(String rawCode) throws IOException { matrix(rawCode); }

    private static BarcodeDataMatrix matrix(String rawCode) throws IOException {
        var matrix = new BarcodeDataMatrix();
        matrix.setOptions(BarcodeDataMatrix.DM_AUTO | BarcodeDataMatrix.DM_EXTENSION);
        matrix.setWs(1);
        String code = KizService.scannerSafeCode(rawCode);
        if (code == null || code.isBlank() || matrix.setCode("f." + code) != BarcodeDataMatrix.DM_NO_ERROR)
            throw new IOException("OZON_KIZ_ONLY_INVALID_KIZ");
        if (OzonPurchasedKizAssignment.rawGtin(code).isBlank()) throw new IOException("OZON_KIZ_ONLY_INVALID_KIZ");
        float dot = 72f / 203;
        if (module(matrix) < 2 * dot) throw new IOException("OZON_KIZ_ONLY_INVALID_KIZ");
        return matrix;
    }

    private static float module(BarcodeDataMatrix matrix) {
        float dot = 72f / 203;
        return (float) Math.floor(MATRIX_SIDE / (matrix.getWidth() + 2f) / dot) * dot;
    }

    private static Paragraph text(String value, float x, float y, float width, float size) {
        return new Paragraph(value == null ? "" : value.replaceAll("\\p{Cntrl}", " "))
                .setMargin(0).setMultipliedLeading(.95f).setFontSize(size).setFixedPosition(x, y, width);
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback == null ? "" : fallback : preferred;
    }
}
