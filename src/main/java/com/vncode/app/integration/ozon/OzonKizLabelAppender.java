package com.vncode.app.integration.ozon;

import com.itextpdf.barcodes.BarcodeDataMatrix;
import com.itextpdf.barcodes.Barcode128;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.print.GenerateBarcode;
import com.vncode.app.features.print.PrintTemplateService;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Appends one combined 58 x 40 mm KIZ/product-barcode label per Ozon exemplar. */
final class OzonKizLabelAppender {
    private static final float WIDTH = (float) PrintTemplateService.PAGE_WIDTH;
    private static final float HEIGHT = (float) PrintTemplateService.PAGE_HEIGHT;
    private static final PageSize PAGE_SIZE = new PageSize(WIDTH, HEIGHT);
    private static final float MATRIX_SIDE = 50;
    private static final float MINIMUM_MODULE = 2 * 72f / 203; // Two dots on a 203 dpi printer.
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();

    int append(
            PdfDocument document,
            Shop shop,
            OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings) throws IOException {
        return append(document, shop, posting, bindings, catalog.findBySkusForPrinting(shop.getId(),
                posting.items().stream().map(OzonPostingItemDto::sku).distinct().toList()));
    }

    int append(
            PdfDocument document, Shop shop, OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings, Map<String, OzonProductDto> products)
            throws IOException {
        int appended = 0;
        var slots=new java.util.ArrayList<com.vncode.app.features.kiz.status.Kn3PrintProjection.Slot>();
        for (var binding : bindings) {
            OzonPostingItemDto selected = item(posting, binding.itemIndex());
            if (!selected.productId().equals(binding.productId())
                    || binding.exemplarIndex() < 0 || binding.exemplarIndex() >= selected.quantity()) {
                throw OzonProductLabelBarcode.failure("OZON_LABEL_BINDING_MISMATCH", "ozon.label.binding.mismatch",
                        "Mã KIZ đã lưu không khớp với sản phẩm trong đơn. Đồng bộ lại đơn trước khi in.", selected);
            }
            slots.add(new com.vncode.app.features.kiz.status.Kn3PrintProjection.Slot(
                    selected.sku(), binding.exemplarId(), binding.rawCode()));
        }
        var codes=com.vncode.app.features.kiz.status.Kn3PrintProjection.codes(shop,posting.postingNumber(),slots);
        int codeIndex=0;
        for (OzonExemplarJobRepository.KizBinding binding : bindings) {
            OzonPostingItemDto item = item(posting, binding.itemIndex());
            OzonProductDto product = products.get(item.sku());
            String code = KizService.scannerSafeCode(codes.get(codeIndex++));
            if (code == null || code.isBlank()) {
                throw new IOException("An accepted Ozon exemplar has no printable KIZ.");
            }
            OzonCombinedLabelRenderer.append(document, item, product, code);
            appended++;
        }
        return appended;
    }

    static void appendProductPage(
            PdfDocument document,
            OzonPostingItemDto item,
            OzonProductDto product,
            String code) throws IOException {
        String productBarcode = OzonProductLabelBarcode.resolve(item, product);
        Barcode128 barcode = productBarcode(document, item, productBarcode);
        BarcodeDataMatrix matrix = code == null || code.isBlank() ? null : dataMatrix(code);
        PdfPage page = document.addNewPage(PAGE_SIZE);
        if (matrix != null) drawDataMatrix(page, matrix);
        drawProductBarcode(page, barcode);

        try (Canvas canvas = new Canvas(page, PAGE_SIZE)) {
            PdfFont font = GenerateBarcode.getArialFont();
            canvas.setFont(font);
            float x = mm(22), width = mm(34);
            canvas.add(text(fitLines(first(product.name(), item.name()), font, width, 8, 3),
                    x, HEIGHT - mm(2.5) - 25, width, 8, true).setHeight(25));
            canvas.add(text(fitLines("Article: " + first(product.article(), item.offerId()), font, width, 7, 2),
                    x, HEIGHT - mm(12.5) - 14, width, 7, true).setHeight(14));
            canvas.add(text(fitLines("Màu: " + product.color(), font, width, 7, 1),
                    x, HEIGHT - mm(18.5) - 8, width, 7, true).setHeight(8));
            canvas.add(text(fitLines("Size: " + first(product.russianSize(), product.size()), font, width, 8, 1),
                    x, HEIGHT - mm(23.5) - 9, width, 8, true).setHeight(9));
            if (matrix != null) {
                canvas.add(text("KIZ", mm(2.5), HEIGHT - mm(24.5), MATRIX_SIDE, 6, false)
                        .setTextAlignment(TextAlignment.CENTER));
            }
            canvas.add(text(productBarcode, mm(2.5), HEIGHT - mm(36.5) - 8, mm(53), 7, false)
                    .setTextAlignment(TextAlignment.CENTER));
        }
        new PdfCanvas(page).saveState().setStrokeColor(ColorConstants.BLACK).setLineWidth(0.4f)
                .moveTo(mm(2.5), HEIGHT - mm(27)).lineTo(mm(55.5), HEIGHT - mm(27))
                .stroke().restoreState();
    }

    private static Paragraph text(
            String value, float x, float y, float width, float size, boolean bold) {
        Paragraph paragraph = new Paragraph(value == null ? "" : value)
                .setMargin(0).setMultipliedLeading(0.9f).setFontSize(size)
                .setFixedPosition(x, y, width);
        return bold ? paragraph.setBold() : paragraph;
    }

    private static BarcodeDataMatrix dataMatrix(String code) throws IOException {
        BarcodeDataMatrix matrix = new BarcodeDataMatrix();
        matrix.setOptions(BarcodeDataMatrix.DM_AUTO | BarcodeDataMatrix.DM_EXTENSION);
        matrix.setWs(1);
        // iText's documented `f.` extension prepends the FNC1 codeword required for GS1
        // DataMatrix while preserving ASCII GS separators already present in the KIZ payload.
        int status = matrix.setCode("f." + code);
        if (status != BarcodeDataMatrix.DM_NO_ERROR) {
            throw new IOException("An accepted Ozon KIZ cannot be encoded as GS1 DataMatrix.");
        }
        if (MATRIX_SIDE / (matrix.getWidth() + 2f * matrix.getWs()) < MINIMUM_MODULE) {
            throw new IOException("OZON_LABEL_KIZ_TOO_DENSE: Mã KIZ quá dài để in rõ trên nhãn 58 × 40 mm.");
        }
        return matrix;
    }

    private static void drawDataMatrix(PdfPage page, BarcodeDataMatrix matrix) {
        // PdfCanvas keeps every module printer-sharp; unlike createAwtImage(), it is not a
        // 36x36 bitmap stretched across the label. Source (iText 8.0.3):
        // https://api.itextpdf.com/iText/java/8.0.3/com/itextpdf/barcodes/BarcodeDataMatrix.html
        float moduleSide = MATRIX_SIDE / (matrix.getWidth() + 2f * matrix.getWs());
        PdfCanvas canvas = new PdfCanvas(page);
        canvas.saveState().concatMatrix(1, 0, 0, 1, mm(2.5), HEIGHT - mm(5) - MATRIX_SIDE);
        matrix.placeBarcode(canvas, ColorConstants.BLACK, moduleSide);
        canvas.restoreState();
    }

    private static Barcode128 productBarcode(PdfDocument document, OzonPostingItemDto item, String value)
            throws IOException {
        Barcode128 barcode = new Barcode128(document);
        barcode.setCode(value);
        barcode.setFont(null);
        barcode.setBarHeight(22);
        barcode.setX(1);
        // Code128's bounding box excludes quiet zones: reserve ten modules on each side.
        float module = mm(53) / (barcode.getBarcodeSize().getWidth() + 20);
        if (module < MINIMUM_MODULE) {
            throw OzonProductLabelBarcode.failure("OZON_LABEL_BARCODE_TOO_DENSE", "ozon.label.barcode.too_dense",
                    "Barcode sản phẩm quá dài để in rõ trên nhãn 58 × 40 mm. Kiểm tra barcode ngắn hơn đã đăng ký trên Ozon.", item);
        }
        barcode.setX(module);
        return barcode;
    }

    private static void drawProductBarcode(PdfPage page, Barcode128 barcode) {
        float x = (WIDTH - barcode.getBarcodeSize().getWidth()) / 2;
        PdfCanvas canvas = new PdfCanvas(page);
        canvas.saveState().concatMatrix(1, 0, 0, 1, x, HEIGHT - mm(28.2) - 22);
        barcode.placeBarcode(canvas, ColorConstants.BLACK, null);
        canvas.restoreState();
    }

    private static float mm(double value) { return (float) (value * 72 / 25.4); }

    private static OzonPostingItemDto item(OzonPostingDto posting, int itemIndex) throws IOException {
        return posting.items().stream()
                .filter(value -> value.itemIndex() == itemIndex)
                .findFirst()
                .orElseThrow(() -> new IOException("An accepted Ozon exemplar no longer matches a posting item."));
    }

    private static String fitLines(String value, PdfFont font, float width, float size, int maximumLines) {
        String safe = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
        var lines = new java.util.ArrayList<String>();
        while (!safe.isEmpty() && lines.size() < maximumLines) {
            int end = safe.length();
            while (end > 0 && font.getWidth(safe.substring(0, end), size) > width - 2) end--;
            if (end < safe.length() && lines.size() + 1 < maximumLines) {
                int space = safe.lastIndexOf(' ', end);
                if (space > 0) end = space;
            }
            if (end <= 0) break;
            String line = safe.substring(0, end).stripTrailing();
            safe = safe.substring(end).stripLeading();
            if (!safe.isEmpty() && lines.size() + 1 == maximumLines) {
                while (!line.isEmpty() && font.getWidth(line + "...", size) > width - 2)
                    line = line.substring(0, line.length() - 1).stripTrailing();
                line += "...";
            }
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback) : preferred;
    }
}
