package com.tuandev.fbsbarcode.features.order;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tuandev.fbsbarcode.models.Order;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExcelOrderImportServiceTest {
    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
    };

    @TempDir
    Path tempDir;

    @Test
    void parsesTheWildberriesWorkbookShapeAndAnchoredImage() throws Exception {
        Path file = workbook(List.of(
                new RowData("9007199254740993", "Brand", "Product", "M", "Black", "ART-1", "ST 12", "BAR-1")));

        List<Order> orders = new ExcelOrderImportService().getOrdersFromExcel(file.toFile());

        assertEquals(1, orders.size());
        Order order = orders.getFirst();
        assertEquals(9_007_199_254_740_993L, order.getId());
        assertEquals("Brand", order.getBrand());
        assertEquals("Product", order.getName());
        assertEquals("M", order.getSize());
        assertEquals("Black", order.getColor());
        assertEquals("ART-1", order.getArticle());
        assertEquals("ST 12", order.getSticker());
        assertEquals("BAR-1", order.getBarcode());
        assertArrayEquals(PNG, order.getImage());
    }

    @Test
    void rejectsFilesThatAreNotXlsxContainersBeforePoiParsesThem() throws Exception {
        Path file = tempDir.resolve("orders.xlsx");
        Files.writeString(file, "not a zip workbook");

        ExcelOrderImportService.InvalidExcelFileException error = assertThrows(
                ExcelOrderImportService.InvalidExcelFileException.class,
                () -> new ExcelOrderImportService().getOrdersFromExcel(file.toFile()));

        assertEquals("The selected file is not a valid XLSX workbook.", error.getMessage());
    }

    @Test
    void rejectsDuplicateOrderIdsInsteadOfPrintingAnOrderTwice() throws Exception {
        Path file = workbook(List.of(
                new RowData("101", "One", "First", "S", "Red", "ART-1", "", "BAR-1"),
                new RowData("101", "Two", "Second", "M", "Blue", "ART-2", "", "BAR-2")));

        ExcelOrderImportService.InvalidExcelFileException error = assertThrows(
                ExcelOrderImportService.InvalidExcelFileException.class,
                () -> new ExcelOrderImportService().getOrdersFromExcel(file.toFile()));

        assertEquals("The workbook contains duplicate order ids.", error.getMessage());
    }

    @Test
    void rejectsWorkbookExpansionBeyondTheConfiguredBudget() throws Exception {
        Path file = workbook(List.of(
                new RowData("101", "Brand", "Product", "S", "Red", "ART-1", "", "BAR-1")));
        ExcelOrderImportService service = new ExcelOrderImportService(
                new ExcelOrderImportService.ImportLimits(
                        Files.size(file) + 1,
                        64,
                        64,
                        100,
                        100,
                        1024,
                        2048));

        ExcelOrderImportService.InvalidExcelFileException error = assertThrows(
                ExcelOrderImportService.InvalidExcelFileException.class,
                () -> service.getOrdersFromExcel(file.toFile()));

        assertEquals("The XLSX workbook expands beyond the safe import limit.", error.getMessage());
    }

    private Path workbook(List<RowData> rows) throws IOException {
        Path file = tempDir.resolve("orders-" + UUID.randomUUID() + ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Orders");
            for (int index = 0; index < rows.size(); index++) {
                Row row = sheet.createRow(index + 5);
                RowData data = rows.get(index);
                row.createCell(0).setCellValue(data.id());
                row.createCell(2).setCellValue(data.brand());
                row.createCell(3).setCellValue(data.name());
                row.createCell(4).setCellValue(data.size());
                row.createCell(5).setCellValue(data.color());
                row.createCell(6).setCellValue(data.article());
                row.createCell(7).setCellValue(data.sticker());
                row.createCell(8).setCellValue(data.barcode());
            }
            int imageIndex = workbook.addPicture(PNG, XSSFWorkbook.PICTURE_TYPE_PNG);
            CreationHelper helper = workbook.getCreationHelper();
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setRow1(5);
            anchor.setCol1(1);
            drawing.createPicture(anchor, imageIndex);
            try (var output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }
        return file;
    }

    private record RowData(
            String id,
            String brand,
            String name,
            String size,
            String color,
            String article,
            String sticker,
            String barcode) {
    }
}
