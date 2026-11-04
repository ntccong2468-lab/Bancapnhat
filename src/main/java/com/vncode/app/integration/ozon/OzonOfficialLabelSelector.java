package com.vncode.app.integration.ozon;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Reader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.qrcode.QRCodeReader;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Keeps carrier pages, excluding positively identified standalone product labels. */
final class OzonOfficialLabelSelector {
    private static final int DPI = 203;
    private static final long MAXIMUM_PIXELS = 2_000_000;
    private static final Pattern TRANSPORT_TEXT = Pattern.compile(
            "отправлени|маршрут|курьер|сортиров|доставк|получател|пункт\\s+выдачи|"
                    + "заказ\\s*[:№]|shipping|shipment|routing|carrier|\\bposting\\b");
    private static final Map<DecodeHintType, Object> DECODE_HINTS = Map.of(
            DecodeHintType.TRY_HARDER, true,
            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.values()));

    private OzonOfficialLabelSelector() { }

    /**
     * One local pass over an already downloaded task, independent of KIZ availability.
     * Page order and task membership do not imply page type. Unknown pages stay intact.
     */
    static List<Integer> shippingPages(File official, PdfDocument source, List<Integer> pages,
            List<OzonPostingDto> postings, Map<String, OzonProductDto> products) throws IOException {
        List<Integer> all = List.copyOf(pages);
        Set<String> barcodes = new HashSet<>();
        Set<String> transportIds = new HashSet<>();
        for (var posting : postings) {
            for (String id : List.of(posting.postingNumber(), posting.orderId(), posting.orderNumber(),
                    posting.lowerBarcode(), posting.upperBarcode(), java.util.Objects.toString(posting.scanit(), ""))) {
                if (!id.isBlank()) transportIds.add(id.toLowerCase(Locale.ROOT));
            }
            for (var item : posting.items()) {
                // Ozon can print an EAN even when the card also has an OZN barcode.
                // Recognition accepts every barcode on this exact SKU, while combined
                // label generation still requires the single resolve() choice.
                try { OzonProductLabelBarcode.registeredForProduct(item, products.get(item.sku())).stream()
                        .filter(value -> value.matches("[\\x20-\\x7E]+"))
                        .forEach(barcodes::add); }
                catch (IOException unresolved) { /* Unknown product pages must not hide carrier labels. */ }
            }
        }
        barcodes.removeIf(value -> transportIds.contains(value.toLowerCase(Locale.ROOT)));
        if (all.isEmpty() || barcodes.isEmpty()) return all;

        // The usual shipping-only batch never needs PDF rasterisation. Text checks are cheap
        // and shared across the task; PDFBox opens the original file only once if necessary.
        List<Integer> candidates = new ArrayList<>();
        Set<Integer> excluded = new HashSet<>();
        var vectorProducts = new OzonProductBarcodePage(barcodes);
        for (int page : all) {
            requireNotInterrupted();
            try {
                String text = PdfTextExtractor.getTextFromPage(source.getPage(page)).toLowerCase(Locale.ROOT);
                if (!TRANSPORT_TEXT.matcher(text).find()
                        && transportIds.stream().noneMatch(text::contains)) {
                    if (vectorProducts.matches(source.getPage(page))) excluded.add(page);
                    else candidates.add(page);
                }
            } catch (RuntimeException unknownPage) { /* Keep unreadable pages unchanged. */ }
        }
        if (candidates.isEmpty()) return all.stream().filter(page -> !excluded.contains(page)).toList();

        // Recognition must not turn an unfamiliar official document into a failed print or
        // remove a shipping page. A failed/unsupported decode therefore preserves the page.
        try (PDDocument document = Loader.loadPDF(official)) {
            if (document.getNumberOfPages() != source.getNumberOfPages()) return all;
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            for (int page : candidates) {
                requireNotInterrupted();
                if (productPage(document, renderer, page, barcodes)) excluded.add(page);
            }
            requireNotInterrupted();
            return all.stream().filter(page -> !excluded.contains(page)).toList();
        } catch (IOException | RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Ozon label selection was interrupted.", exception);
            }
            return all;
        }
    }

    private static void requireNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Ozon label selection was interrupted.");
    }

    private static boolean productPage(PDDocument document,
            PDFRenderer renderer, int page, Set<String> barcodes) {
        java.awt.image.BufferedImage image = null;
        try {
            float dpi = recognitionDpi(document.getPage(page - 1));
            if (dpi == 0) return false;
            image = renderer.renderImageWithDPI(page - 1, dpi, ImageType.GRAY);
            if ((long) image.getWidth() * image.getHeight() > MAXIMUM_PIXELS) return false;
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            if (containsTransportMatrix(bitmap)) return false;
            Result[] decoded = new GenericMultipleBarcodeReader(new MultiFormatReader())
                    .decodeMultiple(bitmap, DECODE_HINTS);
            return decoded.length > 0 && Arrays.stream(decoded).allMatch(result ->
                    (result.getBarcodeFormat() == BarcodeFormat.CODE_128
                            || result.getBarcodeFormat() == BarcodeFormat.EAN_13)
                            && barcodes.contains(result.getText()));
        } catch (IOException | NotFoundException | RuntimeException exception) {
            return false;
        } finally {
            if (image != null) image.flush();
        }
    }

    private static boolean containsTransportMatrix(BinaryBitmap bitmap) {
        if (matrixIn(bitmap)) return true;
        // MultiFormatReader prefers 1D codes. Its multiple-code recursion also skips crops
        // below 100 pixels, so a small carrier DataMatrix can otherwise disappear beside
        // the larger product barcode. Dedicated readers inspect the whole page and fixed
        // overlapping bands, independently of whichever barcode is decoded first. The
        // staggered centers also separate a short rectangular matrix from the 1D barcode.
        int width = (bitmap.getWidth() * 2 + 2) / 3;
        int height = (bitmap.getHeight() + 1) / 2;
        for (int column = 0; column < 3; column++) {
            int x = (bitmap.getWidth() - width) * column / 2;
            for (int band = 0; band < 5; band++) {
                if (Thread.currentThread().isInterrupted()) return true;
                int y = (bitmap.getHeight() - height) * band / 4;
                if (matrixIn(bitmap.crop(x, y, width, height))) return true;
            }
        }
        return false;
    }

    private static boolean matrixIn(BinaryBitmap bitmap) {
        for (Reader reader : List.of(new QRCodeReader(), new DataMatrixReader())) {
            try {
                reader.decode(bitmap, DECODE_HINTS);
                return true;
            } catch (ReaderException exception) {
                // No independently decodable carrier matrix in this bounded region.
            } finally {
                reader.reset();
            }
        }
        return false;
    }

    private static float recognitionDpi(PDPage page) {
        double unit = page.getUserUnit();
        if (!Double.isFinite(unit) || unit <= 0) return 0;
        // PDFRenderer allocates from raw CropBox dimensions even when UserUnit < 1.
        double width = page.getCropBox().getWidth() * Math.max(1d, unit) / 72d;
        double height = page.getCropBox().getHeight() * Math.max(1d, unit) / 72d;
        if (!Double.isFinite(width * height) || width <= 0 || height <= 0) return 0;
        // Include large Ozon product labels (100x150 mm and A4), keeping bounded memory.
        double dpi = Math.min(DPI, Math.sqrt((MAXIMUM_PIXELS - 50_000d) / (width * height)));
        return dpi < 90 || !Double.isFinite(dpi) ? 0 : (float)dpi;
    }
}
