package com.tuandev.fbsbarcode.features.print;

import com.itextpdf.barcodes.BarcodeDataMatrix;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackKizLabelMetadata;
import com.tuandev.fbsbarcode.models.Kiz;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class ZnackKizLabelPdfExporter {
    private static final float WIDTH = (float) PrintTemplateService.PAGE_WIDTH;
    private static final float HEIGHT = (float) PrintTemplateService.PAGE_HEIGHT;
    private static final PageSize PAGE_SIZE = new PageSize(WIDTH, HEIGHT);
    private static final float MATRIX_SIDE = 78f;
    private static final String CHESTNY_ZNAK_LOGO =
            "/com/tuandev/fbsbarcode/assets/images/chestniy-znak.png";

    public void write(List<Kiz> codes, ZnackKizLabelMetadata metadata, File target) throws IOException {
        if (codes == null || codes.isEmpty()) throw new IllegalArgumentException("KIZ list must not be empty.");
        if (target == null) throw new IllegalArgumentException("PDF target is required.");
        ZnackKizLabelMetadata safeMetadata = metadata == null
                ? new ZnackKizLabelMetadata("", "", "") : metadata;
        try (PdfDocument document = new PdfDocument(new PdfWriter(target))) {
            for (Kiz code : codes) appendPage(document, code, safeMetadata);
        }
    }

    private static void appendPage(PdfDocument document, Kiz kiz, ZnackKizLabelMetadata metadata)
            throws IOException {
        String code = KizService.scannerSafeCode(kiz == null ? null : kiz.getCode());
        if (code == null || code.isBlank()) throw new IOException("KIZ cannot be encoded as DataMatrix.");
        PdfPage page = document.addNewPage(PAGE_SIZE);
        drawDataMatrix(page, code);
        try (Canvas canvas = new Canvas(page, PAGE_SIZE)) {
            canvas.setFont(GenerateBarcode.getArialFont());
            float textX = 87f;
            float textWidth = WIDTH - textX - 5f;
            canvas.add(chestnyZnakLogo());
            canvas.add(text(compact(metadata.productName(), 52), textX, 50, textWidth, 8.8f, true));
            canvas.add(text(compact(metadata.gender(), 28), textX, 27, textWidth, 8.2f, true));
            canvas.add(text(compact(metadata.size(), 28), textX, 9, textWidth, 9f, true));
        }
    }

    private static void drawDataMatrix(PdfPage page, String code) throws IOException {
        BarcodeDataMatrix matrix = new BarcodeDataMatrix();
        matrix.setOptions(BarcodeDataMatrix.DM_AUTO | BarcodeDataMatrix.DM_EXTENSION);
        matrix.setWs(1);
        int status = matrix.setCode("f." + code);
        if (status != BarcodeDataMatrix.DM_NO_ERROR) {
            throw new IOException("KIZ cannot be encoded as GS1 DataMatrix.");
        }
        float moduleSide = MATRIX_SIDE / (matrix.getWidth() + 2f * matrix.getWs());
        float renderedHeight = moduleSide * (matrix.getHeight() + 2f * matrix.getWs());
        PdfCanvas canvas = new PdfCanvas(page);
        canvas.saveState().concatMatrix(1, 0, 0, 1, 5f, (HEIGHT - renderedHeight) / 2f);
        matrix.placeBarcode(canvas, ColorConstants.BLACK, moduleSide);
        canvas.restoreState();
    }

    private static Image chestnyZnakLogo() throws IOException {
        try (InputStream stream = ZnackKizLabelPdfExporter.class.getResourceAsStream(CHESTNY_ZNAK_LOGO)) {
            if (stream == null) throw new IOException("Chestny ZNAK logo resource is missing.");
            return new Image(ImageDataFactory.create(stream.readAllBytes()))
                    .scaleToFit(70f, 20f)
                    .setFixedPosition(87f, 89f);
        }
    }

    private static Paragraph text(String value, float x, float y, float width, float size, boolean bold) {
        Paragraph paragraph = new Paragraph(value == null ? "" : value)
                .setMargin(0).setMultipliedLeading(0.9f).setFontSize(size).setFixedPosition(x, y, width);
        return bold ? paragraph.setBold() : paragraph;
    }

    private static String compact(String value, int maximum) {
        String safe = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
        if (safe.length() <= maximum) return safe;
        return safe.substring(0, Math.max(0, maximum - 3)).stripTrailing() + "...";
    }
}
