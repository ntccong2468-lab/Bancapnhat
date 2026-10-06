package com.tuandev.fbsbarcode.features.order;

import com.tuandev.fbsbarcode.models.Order;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class ExcelOrderImportService {
    private static final int FIRST_ORDER_ROW = 5;
    private static final int MAX_SHEET_ROW = 100_000;
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final ImportLimits DEFAULT_LIMITS = new ImportLimits(
            50L * 1024 * 1024,
            128L * 1024 * 1024,
            32L * 1024 * 1024,
            10_000,
            5_000,
            5L * 1024 * 1024,
            32L * 1024 * 1024);

    private final ImportLimits limits;

    public ExcelOrderImportService() {
        this(DEFAULT_LIMITS);
    }

    ExcelOrderImportService(ImportLimits limits) {
        this.limits = limits;
    }

    public List<Order> getOrdersFromExcel(File file) throws IOException {
        Path path = validateSelectedFile(file);
        validateXlsxContainer(path);
        try (InputStream input = Files.newInputStream(path);
                XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw invalid("The XLSX workbook does not contain an order sheet.");
            }
            XSSFSheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > MAX_SHEET_ROW) {
                throw invalid("The XLSX workbook contains too many rows.");
            }
            Map<Integer, byte[]> images = getImages(sheet);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Set<Long> orderIds = new HashSet<>();
            List<Order> orders = new ArrayList<>();
            for (int index = FIRST_ORDER_ROW; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) {
                    continue;
                }
                String idValue = readCell(row, 0, formatter);
                if (idValue.isBlank()) {
                    continue;
                }
                if (orders.size() >= limits.maxOrders()) {
                    throw invalid("The XLSX workbook contains too many orders.");
                }
                long orderId = parseOrderId(idValue);
                if (!orderIds.add(orderId)) {
                    throw invalid("The workbook contains duplicate order ids.");
                }
                orders.add(new Order(
                        orderId,
                        images.get(index),
                        readCell(row, 2, formatter),
                        readCell(row, 3, formatter),
                        readCell(row, 4, formatter),
                        readCell(row, 5, formatter),
                        readCell(row, 6, formatter),
                        readCell(row, 7, formatter),
                        readCell(row, 8, formatter)));
            }
            return List.copyOf(orders);
        } catch (InvalidExcelFileException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("The selected file is not a valid XLSX workbook.", exception);
        }
    }

    private Path validateSelectedFile(File file) throws IOException {
        if (file == null) {
            throw invalid("No XLSX workbook was selected.");
        }
        Path path = file.toPath();
        String fileName = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(path) || !fileName.endsWith(".xlsx")) {
            throw invalid("The selected file is not a valid XLSX workbook.");
        }
        long size = Files.size(path);
        if (size <= 0 || size > limits.maxCompressedBytes()) {
            throw invalid("The selected XLSX workbook exceeds the safe import size.");
        }
        return path;
    }

    private void validateXlsxContainer(Path path) throws IOException {
        int entries = 0;
        long expandedBytes = 0;
        boolean hasContentTypes = false;
        boolean hasWorkbook = false;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > limits.maxEntries()) {
                    throw invalid("The XLSX workbook contains too many ZIP entries.");
                }
                String name = entry.getName();
                hasContentTypes |= "[Content_Types].xml".equals(name);
                hasWorkbook |= "xl/workbook.xml".equals(name);
                if ("xl/vbaProject.bin".equalsIgnoreCase(name)) {
                    throw invalid("Macro-enabled workbooks are not supported.");
                }
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes = checkedAdd(entryBytes, read, limits.maxEntryBytes());
                    expandedBytes = checkedAdd(expandedBytes, read, limits.maxExpandedBytes());
                }
                zip.closeEntry();
            }
        } catch (InvalidExcelFileException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid("The selected file is not a valid XLSX workbook.", exception);
        }
        if (!hasContentTypes || !hasWorkbook) {
            throw invalid("The selected file is not a valid XLSX workbook.");
        }
    }

    private long checkedAdd(long current, int increment, long maximum) throws InvalidExcelFileException {
        if (current > maximum - increment) {
            throw invalid("The XLSX workbook expands beyond the safe import limit.");
        }
        return current + increment;
    }

    private long parseOrderId(String value) throws InvalidExcelFileException {
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw invalid("The workbook contains an invalid order id.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid("The workbook contains an invalid order id.", exception);
        }
    }

    private String readCell(Row row, int cellIndex, DataFormatter formatter) {
        if (row.getCell(cellIndex) == null) {
            return "";
        }
        return formatter.formatCellValue(row.getCell(cellIndex)).strip();
    }

    private Map<Integer, byte[]> getImages(XSSFSheet sheet) throws InvalidExcelFileException {
        Map<Integer, byte[]> images = new HashMap<>();
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing == null) {
            return images;
        }
        long totalImageBytes = 0;
        for (XSSFShape shape : drawing.getShapes()) {
            if (!(shape instanceof XSSFPicture picture)) {
                continue;
            }
            XSSFPictureData pictureData = picture.getPictureData();
            byte[] data = pictureData.getData();
            if (data.length > limits.maxImageBytes()) {
                throw invalid("The XLSX workbook contains an oversized product image.");
            }
            totalImageBytes = checkedAdd(totalImageBytes, data.length, limits.maxTotalImageBytes());
            XSSFClientAnchor anchor = picture.getClientAnchor();
            if (anchor != null && anchor.getRow1() >= FIRST_ORDER_ROW) {
                images.putIfAbsent(anchor.getRow1(), data);
            }
        }
        return images;
    }

    private InvalidExcelFileException invalid(String message) {
        return new InvalidExcelFileException(message);
    }

    private InvalidExcelFileException invalid(String message, Throwable cause) {
        return new InvalidExcelFileException(message, cause);
    }

    record ImportLimits(
            long maxCompressedBytes,
            long maxExpandedBytes,
            long maxEntryBytes,
            int maxEntries,
            int maxOrders,
            long maxImageBytes,
            long maxTotalImageBytes) {
        ImportLimits {
            if (maxCompressedBytes <= 0
                    || maxExpandedBytes <= 0
                    || maxEntryBytes <= 0
                    || maxEntries <= 0
                    || maxOrders <= 0
                    || maxImageBytes <= 0
                    || maxTotalImageBytes <= 0) {
                throw new IllegalArgumentException("Excel import limits must be positive.");
            }
        }
    }

    public static final class InvalidExcelFileException extends IOException {
        public InvalidExcelFileException(String message) {
            super(message);
        }

        public InvalidExcelFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
