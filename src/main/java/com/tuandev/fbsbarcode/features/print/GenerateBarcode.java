package com.tuandev.fbsbarcode.features.print;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.AreaBreakType;
import com.itextpdf.layout.properties.TextAlignment;
import com.tuandev.fbsbarcode.models.Order;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GenerateBarcode {
    private static final float WIDTH = (float) PrintTemplateService.PAGE_WIDTH;
    private static final float HEIGHT = (float) PrintTemplateService.PAGE_HEIGHT;
    private static volatile byte[] arialFontBytes;

    public static PdfFont getArialFont() {
        if (arialFontBytes == null) {
            synchronized (GenerateBarcode.class) {
                if (arialFontBytes == null) {
                    try {
                        try (InputStream is = GenerateBarcode.class.getResourceAsStream("/com/tuandev/fbsbarcode/assets/fonts/ARIAL.TTF")) {
                            if (is == null) {
                                throw new RuntimeException("Không tìm thấy file font trong resources!");
                            }
                            arialFontBytes = is.readAllBytes();
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        try {
            return PdfFontFactory.createFont(arialFontBytes, PdfEncodings.IDENTITY_H);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void exportTemplateAndSticker(PrintTemplate template, List<Order> orders, File file) throws IOException, WriterException {
        exportTemplateAndSticker(template, orders, file, PrintJobOptions.defaults());
    }

    public static void exportTemplateAndSticker(PrintTemplate template, List<Order> orders, File file, PrintJobOptions options) throws IOException, WriterException {
        PdfWriter pdfWriter = new PdfWriter(file);
        PdfDocument pdfDocument = new PdfDocument(pdfWriter);

        PageSize pageSize = new PageSize(WIDTH, HEIGHT);
        Document document = new Document(pdfDocument, pageSize);
        document.setMargins(0, 0, 0, 0);
        document.setFont(getArialFont());
        RenderContext renderContext = new RenderContext();
        PrintJobOptions safeOptions = options == null ? PrintJobOptions.defaults() : options.normalized();
        boolean firstPage = true;

        for (Order order : orders) {
            if (safeOptions.pageOrder() == PrintPageOrder.STICKER_THEN_BARCODE) {
                firstPage = appendStickerPage(document, pdfDocument, renderContext, firstPage, order);
                firstPage = appendTemplatePages(document, pdfDocument, renderContext, firstPage, order, template, safeOptions.barcodeCopies());
            } else {
                firstPage = appendTemplatePages(document, pdfDocument, renderContext, firstPage, order, template, safeOptions.barcodeCopies());
                firstPage = appendStickerPage(document, pdfDocument, renderContext, firstPage, order);
            }
        }

        document.close();
    }

    public static void exportTemplatePages(PrintTemplate template, List<Order> orders, File file) throws IOException {
        PdfWriter pdfWriter = new PdfWriter(file);
        PdfDocument pdfDocument = new PdfDocument(pdfWriter);

        PageSize pageSize = new PageSize(WIDTH, HEIGHT);
        Document document = new Document(pdfDocument, pageSize);
        document.setMargins(0, 0, 0, 0);
        document.setFont(getArialFont());
        RenderContext renderContext = new RenderContext();
        boolean firstPage = true;

        for (Order order : orders == null ? List.<Order>of() : orders) {
            if (!firstPage) {
                document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
            }
            addTemplatePage(order, template, document, pdfDocument, renderContext);
            firstPage = false;
        }

        document.close();
    }

    private static boolean appendTemplatePages(Document document,
                                               PdfDocument pdfDocument,
                                               RenderContext renderContext,
                                               boolean firstPage,
                                               Order order,
                                               PrintTemplate template,
                                               int copies) throws IOException {
        boolean currentFirstPage = firstPage;
        for (int copyIndex = 0; copyIndex < Math.max(1, copies); copyIndex++) {
            if (!currentFirstPage) {
                document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
            }
            addTemplatePage(order, template, document, pdfDocument, renderContext);
            currentFirstPage = false;
        }
        return currentFirstPage;
    }

    private static boolean appendStickerPage(Document document,
                                             PdfDocument pdfDocument,
                                             RenderContext renderContext,
                                             boolean firstPage,
                                             Order order) throws IOException, WriterException {
        if (!firstPage) {
            document.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
        }
        addPageSticker(order, document, pdfDocument, renderContext);
        return false;
    }

    private static void addTemplatePage(
            Order order,
            PrintTemplate template,
            Document document,
            PdfDocument pdfDocument,
            RenderContext renderContext
    ) throws IOException {
        List<PrintTemplateElement> elements = template.getElements().stream()
                .filter(PrintTemplateElement::isVisible)
                .sorted((left, right) -> Integer.compare(left.getZIndex(), right.getZIndex()))
                .toList();

        for (PrintTemplateElement element : elements) {
            switch (element.getType()) {
                case KIZ_DATAMATRIX -> renderKiz(order, element, document, renderContext);
                case BARCODE_CODE128 -> renderBarcode(order, element, document, renderContext);
                case TEXT_FIELD -> renderTextField(order, element, document);
                case STATIC_TEXT -> renderStaticText(element, document);
                case STICKER_TAIL -> renderStickerTail(order, element, document);
                case SEPARATOR_LINE -> renderSeparatorLine(element, pdfDocument);
            }
        }
    }

    private static void renderKiz(Order order, PrintTemplateElement element, Document document, RenderContext renderContext) throws IOException {
        String kiz = safeValue(order.getKiz());
        if (kiz.isBlank() || element.getWidth() <= 0 || element.getHeight() <= 0) {
            return;
        }

        Image image = new Image(ImageDataFactory.create(renderContext.getDataMatrix(kiz, 600)));
        image.scaleToFit((float) element.getWidth(), (float) element.getHeight());
        image.setFixedPosition((float) element.getX(), toBottomY(element));
        document.add(image);
    }

    private static void renderBarcode(Order order, PrintTemplateElement element, Document document, RenderContext renderContext) throws IOException {
        String barcode = safeValue(order.getBarcode());
        if (barcode.isBlank() || element.getWidth() <= 0 || element.getHeight() <= 0) {
            return;
        }

        float imageHeight = (float) element.getHeight();
        if (element.isShowHumanReadable()) {
            imageHeight = Math.max(16f, (float) element.getHeight() - 10f);
        }

        Image image = new Image(ImageDataFactory.create(
                renderContext.getCode128(barcode, Math.max(120, (int) Math.round(element.getWidth() * 3)), Math.max(45, Math.round(imageHeight * 3)))
        ));
        image.scaleToFit((float) element.getWidth(), imageHeight);
        image.setFixedPosition((float) element.getX(), toBottomY(element) + (float) (element.getHeight() - imageHeight));
        document.add(image);

        if (element.isShowHumanReadable()) {
            Paragraph paragraph = baseParagraph(barcode, element)
                    .setFontSize(Math.max(6f, element.getFontSize() - 1))
                    .setFixedPosition((float) element.getX(), toBottomY(element), (float) element.getWidth());
            document.add(paragraph);
        }
    }

    private static void renderTextField(Order order, PrintTemplateElement element, Document document) {
        String value = resolveFieldValue(order, element.getFieldKey());
        String output = withPrefix(element.getPrefix(), value);
        if (output.isBlank() || element.getWidth() <= 0 || element.getHeight() <= 0 || element.getFontSize() <= 0f) {
            return;
        }

        renderTextBlock(output, element, document);
    }

    private static void renderStaticText(PrintTemplateElement element, Document document) {
        String output = withPrefix(element.getPrefix(), safeValue(element.getContent()));
        if (output.isBlank() || element.getWidth() <= 0 || element.getHeight() <= 0 || element.getFontSize() <= 0f) {
            return;
        }

        renderTextBlock(output, element, document);
    }

    private static void renderStickerTail(Order order, PrintTemplateElement element, Document document) {
        String value = safeValue(order.getStickerTail()).isBlank()
                ? normalizeStickerTail(StickerText.secondPartOrFirst(order.getSticker()))
                : safeValue(order.getStickerTail());
        String output = withPrefix(element.getPrefix(), value);
        if (output.isBlank() || element.getWidth() <= 0 || element.getHeight() <= 0 || element.getFontSize() <= 0f) {
            return;
        }

        renderTextBlock(output, element, document);
    }

    private static void renderSeparatorLine(PrintTemplateElement element, PdfDocument pdfDocument) {
        if (element.getWidth() <= 0 || element.getHeight() <= 0) {
            return;
        }
        PdfPage currentPage = pdfDocument.getPage(pdfDocument.getNumberOfPages());
        PdfCanvas canvas = new PdfCanvas(currentPage);
        float y = (float) (HEIGHT - element.getY() - (element.getHeight() / 2f));
        canvas.setLineWidth(Math.max(0.5f, (float) element.getHeight()));
        canvas.moveTo((float) element.getX(), y);
        canvas.lineTo((float) (element.getX() + element.getWidth()), y);
        canvas.stroke();
    }

    private static Paragraph baseParagraph(String text, PrintTemplateElement element) {
        Paragraph paragraph = new Paragraph(text)
                .setMargin(0)
                .setMultipliedLeading(0.9f)
                .setFontSize(element.getFontSize())
                .setTextAlignment(toTextAlignment(element.getAlign()));
        if (element.isBold()) {
            paragraph.setBold();
        }
        return paragraph;
    }

    private static void renderTextBlock(String text, PrintTemplateElement element, Document document) {
        float lineHeight = Math.max(6f, element.getFontSize() * 0.98f);
        int maxLines = Math.max(1, (int) Math.floor(element.getHeight() / lineHeight));
        int maxCharsPerLine = Math.max(3, (int) Math.floor(element.getWidth() / Math.max(3.5f, element.getFontSize() * 0.55f)));
        List<String> lines = splitIntoLines(text, maxCharsPerLine, maxLines);
        int emptyTopLines = bottomAlignedTwoLineField(element) ? Math.max(0, maxLines - lines.size()) : 0;
        float firstLineBottomY = (float) (HEIGHT - element.getY() - ((emptyTopLines + 1) * lineHeight));
        for (int i = 0; i < lines.size(); i++) {
            Paragraph paragraph = baseParagraph(lines.get(i), element)
                    .setFixedPosition((float) element.getX(), firstLineBottomY - (i * lineHeight), (float) element.getWidth());
            document.add(paragraph);
        }
    }

    private static boolean bottomAlignedTwoLineField(PrintTemplateElement element) {
        return element != null
                && element.getType() == PrintElementType.TEXT_FIELD
                && (element.getFieldKey() == PrintFieldKey.ARTICLE || element.getFieldKey() == PrintFieldKey.COLOR);
    }

    private static List<String> splitIntoLines(String value, int maxCharsPerLine, int maxLines) {
        String safeValue = safeValue(value);
        if (safeValue.isBlank() || maxLines <= 0) {
            return List.of("");
        }
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        String remaining = safeValue;
        while (!remaining.isBlank() && lines.size() < maxLines) {
            if (remaining.length() <= maxCharsPerLine) {
                lines.add(remaining);
                break;
            }
            int breakAt = remaining.lastIndexOf(' ', maxCharsPerLine);
            if (breakAt < Math.max(4, maxCharsPerLine / 2)) {
                breakAt = maxCharsPerLine;
            }
            String line = remaining.substring(0, breakAt).trim();
            remaining = remaining.substring(Math.min(breakAt, remaining.length())).trim();
            if (lines.size() == maxLines - 1 && !remaining.isBlank()) {
                line = compact(line + " " + remaining, maxCharsPerLine);
                remaining = "";
            }
            lines.add(line);
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private static String resolveFieldValue(Order order, PrintFieldKey fieldKey) {
        if (fieldKey == null) {
            return "";
        }
        return switch (fieldKey) {
            case BRAND -> safeValue(order.getBrand());
            case NAME -> safeValue(order.getName());
            case SUBJECT_NAME -> safeValue(order.getSubjectName());
            case COLOR -> safeValue(order.getColor());
            case ARTICLE -> safeValue(order.getArticle());
            case SIZE -> safeValue(order.getSize());
            case RU_SIZE -> safeValue(order.getRuSize());
            case BARCODE -> safeValue(order.getBarcode());
            case STICKER_TAIL -> safeValue(order.getStickerTail()).isBlank()
                    ? normalizeStickerTail(StickerText.secondPartOrFirst(order.getSticker()))
                    : safeValue(order.getStickerTail());
        };
    }

    private static String normalizeStickerTail(String value) {
        String safe = safeValue(value);
        if (safe.matches("\\d{1,4}")) {
            return String.format("%04d", Integer.parseInt(safe));
        }
        return safe;
    }

    private static String withPrefix(String prefix, String value) {
        String safeValue = safeValue(value);
        if (safeValue.isBlank()) {
            return "";
        }
        String safePrefix = safeValue(prefix);
        return safePrefix.isBlank() ? safeValue : safePrefix + ": " + safeValue;
    }

    private static float toBottomY(PrintTemplateElement element) {
        return (float) (HEIGHT - element.getY() - element.getHeight());
    }

    private static TextAlignment toTextAlignment(PrintTextAlign align) {
        if (align == null) {
            return TextAlignment.LEFT;
        }
        return switch (align) {
            case LEFT -> TextAlignment.LEFT;
            case CENTER -> TextAlignment.CENTER;
            case RIGHT -> TextAlignment.RIGHT;
        };
    }

    private static void addPageSticker(Order order, Document document, PdfDocument pdfDoc, RenderContext renderContext) throws IOException, WriterException {
        String stickerCode = requiredValue(order.getStickerCode(), "Sticker code", order);

        Image qrCenter = new Image(ImageDataFactory.create(renderContext.getQr(stickerCode, 1000)));
        qrCenter.scaleToFit(80, 80);
        qrCenter.setFixedPosition(WIDTH / 2 - 40, HEIGHT / 2 - 40);
        document.add(qrCenter);

        byte[] smallQr = renderContext.getQr(stickerCode, 30);

        Image qrTL = new Image(ImageDataFactory.create(smallQr));
        qrTL.scaleToFit(30, 30);
        qrTL.setFixedPosition(8, HEIGHT - 38);
        document.add(qrTL);

        Image qrTR = new Image(ImageDataFactory.create(smallQr));
        qrTR.scaleToFit(30, 30);
        qrTR.setFixedPosition(WIDTH - 38, HEIGHT - 38);
        document.add(qrTR);

        Image qrBL = new Image(ImageDataFactory.create(smallQr));
        qrBL.scaleToFit(30, 30);
        qrBL.setFixedPosition(8, 8);
        document.add(qrBL);

        Image qrBR = new Image(ImageDataFactory.create(smallQr));
        qrBR.scaleToFit(30, 30);
        qrBR.setFixedPosition(WIDTH - 38, 8);
        document.add(qrBR);

        Paragraph wb = new Paragraph("WB")
                .setFontSize(18)
                .setBold()
                .setFontColor(ColorConstants.MAGENTA)
                .setRotationAngle(Math.toRadians(90))
                .setTextAlignment(TextAlignment.CENTER);
        wb.setFixedPosition(40, 41, 40);
        document.add(wb);

        Paragraph stickerPathA = new Paragraph(StickerText.firstPart(order.getSticker()))
                .setFontSize(10)
                .setBold()
                .setRotationAngle(Math.toRadians(90))
                .setTextAlignment(TextAlignment.CENTER);
        stickerPathA.setFixedPosition(WIDTH - 16, 36, 51);
        document.add(stickerPathA);

        Paragraph stickerPathB = new Paragraph(normalizeStickerTail(StickerText.secondPartOrFirst(order.getSticker())))
                .setFontSize(14)
                .setBold()
                .setRotationAngle(Math.toRadians(90))
                .setTextAlignment(TextAlignment.CENTER);
        stickerPathB.setFixedPosition(WIDTH - 2, 36, 51);
        document.add(stickerPathB);
    }

    private static String compact(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "\u2026";
    }

    private static String safeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requiredValue(String value, String fieldName, Order order) {
        String safeValue = safeValue(value);
        if (safeValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " bị thiếu cho order " + order.getId());
        }
        return safeValue;
    }

    private static byte[] generateQR(String text, int size) throws WriterException, IOException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();

        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 1);

        BitMatrix bitMatrix = qrCodeWriter.encode(
                text,
                BarcodeFormat.QR_CODE,
                size,
                size,
                hints
        );

        BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", byteArrayOutputStream);

        return byteArrayOutputStream.toByteArray();
    }

    private static byte[] generateDataMatrix(String text, int size) throws IOException {
        DataMatrixWriter dataMatrixWriter = new DataMatrixWriter();

        BitMatrix bitMatrix = dataMatrixWriter.encode(text, BarcodeFormat.DATA_MATRIX, size, size);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", byteArrayOutputStream);

        return byteArrayOutputStream.toByteArray();
    }

    private static byte[] generateCode128(String text, int width, int height) throws IOException {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 1);

        Code128Writer code128Writer = new Code128Writer();

        BitMatrix bitMatrix = code128Writer.encode(text, BarcodeFormat.CODE_128, width, height, hints);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", byteArrayOutputStream);

        return byteArrayOutputStream.toByteArray();
    }

    private static final class RenderContext {
        private final Map<String, byte[]> qrCache = new ConcurrentHashMap<>();
        private final Map<String, byte[]> dataMatrixCache = new ConcurrentHashMap<>();
        private final Map<String, byte[]> code128Cache = new ConcurrentHashMap<>();

        byte[] getQr(String text, int size) {
            return qrCache.computeIfAbsent(text + "|" + size, key -> {
                try {
                    return generateQR(text, size);
                } catch (WriterException | IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        byte[] getDataMatrix(String text, int size) {
            return dataMatrixCache.computeIfAbsent(text + "|" + size, key -> {
                try {
                    return generateDataMatrix(text, size);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        byte[] getCode128(String text, int width, int height) {
            return code128Cache.computeIfAbsent(text + "|" + width + "|" + height, key -> {
                try {
                    return generateCode128(text, width, height);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

}
