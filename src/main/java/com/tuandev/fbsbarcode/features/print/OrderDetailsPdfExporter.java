package com.tuandev.fbsbarcode.features.print;

import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.tuandev.fbsbarcode.models.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class OrderDetailsPdfExporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderDetailsPdfExporter.class);
    private static final DateTimeFormatter HEADER_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public void export(File file, List<Order> orders) throws IOException {
        export(file, orders, null);
    }

    public void export(File file, List<Order> orders, PrintDetailsMetadata metadata) throws IOException {
        try (PdfWriter pdfWriter = new PdfWriter(file);
             PdfDocument pdfDocument = new PdfDocument(pdfWriter);
             Document document = new Document(pdfDocument, PageSize.A4)) {
            document.setMargins(20, 20, 20, 20);
            document.setFont(GenerateBarcode.getArialFont());

            addMetadataHeader(document, orders, metadata);

            float[] widths = {32, 76, 58, 55, 78, 114, 140};
            Table table = new Table(widths);
            table.setWidth(UnitValue.createPercentValue(100));

            addHeader(table, "№");
            addHeader(table, "№ задания");
            addHeader(table, "Фото");
            addHeader(table, "Размер");
            addHeader(table, "Цвет");
            addHeader(table, "Артикул продавца");
            addHeader(table, "Стикер");

            for (int index = 0; index < orders.size(); index++) {
                Order order = orders.get(index);
                table.addCell(cell(String.valueOf(index + 1)));
                table.addCell(cell(String.valueOf(order.getId())));
                table.addCell(imageCell(order));
                table.addCell(cell(order.getSize()));
                table.addCell(cell(order.getColor()));
                table.addCell(cell(order.getArticle()));
                table.addCell(stickerCell(order.getSticker()));
            }

            document.add(table);
        }
    }

    private void addMetadataHeader(Document document, List<Order> orders, PrintDetailsMetadata metadata) {
        if (metadata == null) {
            return;
        }

        String supplyId = safe(metadata.supplyId());
        String supplyName = safe(metadata.supplyName());
        String shopName = safe(metadata.shopName());
        int itemCount = metadata.itemCount() > 0 ? metadata.itemCount() : orders.size();
        String printedAt = formatPrintedAt(metadata.printedAt());

        String supplyLine = "Поставка " + supplyId;
        if (!supplyName.isBlank()) {
            supplyLine += " (" + supplyName + ")";
        }

        document.add(new Paragraph(supplyLine).setBold().setFontSize(13).setMarginBottom(2));
        document.add(new Paragraph("Магазин: " + shopName).setFontSize(11).setMarginBottom(1));
        document.add(new Paragraph("Дата: " + printedAt).setFontSize(11).setMarginBottom(1));
        document.add(new Paragraph("Количество товаров: " + itemCount).setFontSize(11).setMarginBottom(12));
    }

    private Cell imageCell(Order order) {
        if (order.getImage() == null) {
            return cell("");
        }

        try {
            ImageData imageData = ImageDataFactory.create(order.getImage());
            Image img = new Image(imageData);
            img.scaleToFit(42, 56);

            return new Cell()
                    .add(img)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                    .setPadding(5);
        } catch (RuntimeException ex) {
            LOGGER.warn("Bỏ qua ảnh sản phẩm không hợp lệ khi xuất PDF cho order {}: {}",
                    order.getId(), ex.getMessage());
            return cell("");
        }
    }

    private Cell cell(String text) {
        return new Cell()
                .add(new Paragraph(text == null ? "" : text).setFontSize(10))
                .setTextAlignment(TextAlignment.LEFT)
                .setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private Cell stickerCell(String sticker) {
        String safeSticker = normalizeStickerForPdf(StickerText.safe(sticker));
        Paragraph paragraph = new Paragraph().setFontSize(12).setBold();

        if (safeSticker.length() <= 4) {
            paragraph.add(new Text(safeSticker)
                    .setFontColor(ColorConstants.WHITE)
                    .setBackgroundColor(ColorConstants.BLACK));
        } else {
            int highlightStart = safeSticker.length() - 4;
            paragraph.add(new Text(safeSticker.substring(0, highlightStart)));
            paragraph.add(new Text(safeSticker.substring(highlightStart))
                    .setFontColor(ColorConstants.WHITE)
                    .setBackgroundColor(ColorConstants.BLACK));
        }

        return new Cell()
                .add(paragraph)
                .setTextAlignment(TextAlignment.LEFT)
                .setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private void addHeader(Table table, String text) {
        table.addHeaderCell(
                new Cell()
                        .add(new Paragraph(text).setBold().setFontSize(10))
                        .setVerticalAlignment(VerticalAlignment.MIDDLE)
                        .setTextAlignment(TextAlignment.CENTER)
                        .setBackgroundColor(ColorConstants.LIGHT_GRAY)
        );
    }

    private String normalizeStickerForPdf(String sticker) {
        if (sticker == null || sticker.isBlank()) {
            return "";
        }
        String[] parts = sticker.trim().split("\\s+");
        if (parts.length < 2) {
            return sticker;
        }
        String secondPart = parts[1];
        if (secondPart.matches("\\d{1,4}")) {
            secondPart = String.format("%04d", Integer.parseInt(secondPart));
        }
        return parts[0] + " " + secondPart;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String formatPrintedAt(String printedAt) {
        if (printedAt == null || printedAt.isBlank()) {
            return "";
        }
        try {
            return HEADER_DATE_FORMAT.format(Instant.parse(printedAt).atZone(ZoneId.systemDefault()));
        } catch (Exception ignored) {
            return printedAt;
        }
    }

    public record PrintDetailsMetadata(
            String supplyId,
            String supplyName,
            String shopName,
            String printedAt,
            int itemCount
    ) {
    }
}
