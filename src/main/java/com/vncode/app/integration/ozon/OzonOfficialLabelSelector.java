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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Removes only positively identified product pages already replaced by combined labels. */
final class OzonOfficialLabelSelector {
    private static final int DPI = 203;
    private static final double MAXIMUM_POINTS = 100 * 72d / 25.4;
    private static final long MAXIMUM_PIXELS = 1_000_000;
    private static final Pattern TRANSPORT_TEXT = Pattern.compile(
            "отправлени|маршрут|курьер|сортиров|доставк|получател|пункт\\s+выдачи|"
                    + "заказ\\s*[:№]|shipping|shipment|routing|carrier|\\bposting\\b");
    private static final Map<DecodeHintType, Object> DECODE_HINTS = Map.of(
            DecodeHintType.TRY_HARDER, true,
            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.values()));

    private OzonOfficialLabelSelector() { }

    /** Page numbers are one based. Unknown pages and the first carrier page are always kept. */
    static List<Integer> retainedPages(File official, PdfDocument source, OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings, Map<String, OzonProductDto> products)
            throws IOException {
        List<Integer> all = IntStream.rangeClosed(1, source.getNumberOfPages()).boxed().toList();
        if (all.size() < 2 || bindings.isEmpty()) return all;
        Set<String> covered = completelyCoveredBarcodes(posting, bindings, products);
        if (covered.isEmpty()) return all;

        // Recognition must not turn an unfamiliar official document into a failed print or
        // remove a shipping page. A failed/unsupported decode therefore preserves the page.
        try (PDDocument document = Loader.loadPDF(official)) {
            if (document.getNumberOfPages() != all.size()) return all;
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            List<Integer> retained = new ArrayList<>();
            retained.add(1);
            for (int page = 2; page <= all.size(); page++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Ozon label selection was interrupted.");
                }
                if (!replaceableProductPage(source, document, renderer, page, posting, covered)) {
                    retained.add(page);
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Ozon label selection was interrupted.");
            }
            return List.copyOf(retained);
        } catch (IOException | RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Ozon label selection was interrupted.", exception);
            }
            return all;
        }
    }

    private static Set<String> completelyCoveredBarcodes(OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings, Map<String, OzonProductDto> products) {
        Map<String, Boolean> coverage = new HashMap<>();
        Set<Integer> itemIndexes = new HashSet<>();
        for (OzonPostingItemDto item : posting.items()) {
            if (!itemIndexes.add(item.itemIndex())) return Set.of();
            final String barcode;
            try {
                barcode = OzonProductLabelBarcode.resolve(item, products.get(item.sku()));
            } catch (IOException exception) {
                // Unresolved items might use the same barcode; full coverage is unproven.
                return Set.of();
            }
            Set<Integer> units = new HashSet<>();
            Set<Long> codes = new HashSet<>();
            for (var binding : bindings) {
                if (binding.itemIndex() == item.itemIndex()
                        && item.productId().equals(binding.productId())
                        && binding.exemplarIndex() >= 0 && binding.exemplarIndex() < item.quantity()
                        && binding.kizId() > 0 && binding.rawCode() != null && !binding.rawCode().isBlank()
                        && codes.add(binding.kizId())) {
                    units.add(binding.exemplarIndex());
                }
            }
            coverage.merge(barcode, units.size() == item.quantity(), Boolean::logicalAnd);
        }
        Set<String> complete = new HashSet<>();
        coverage.forEach((barcode, value) -> { if (value) complete.add(barcode); });
        return Set.copyOf(complete);
    }

    private static boolean replaceableProductPage(PdfDocument source, PDDocument document,
            PDFRenderer renderer, int page, OzonPostingDto posting, Set<String> covered) {
        java.awt.image.BufferedImage image = null;
        try {
            if (!compact(document.getPage(page - 1))) return false;
            String text = PdfTextExtractor.getTextFromPage(source.getPage(page)).toLowerCase(Locale.ROOT);
            if (TRANSPORT_TEXT.matcher(text).find()) return false;
            for (String identifier : List.of(posting.postingNumber(), posting.orderId(), posting.orderNumber(),
                    posting.lowerBarcode(), posting.upperBarcode())) {
                if (!identifier.isBlank() && text.contains(identifier.toLowerCase(Locale.ROOT))) return false;
            }
            image = renderer.renderImageWithDPI(page - 1, DPI, ImageType.GRAY);
            if ((long) image.getWidth() * image.getHeight() > MAXIMUM_PIXELS) return false;
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            if (containsTransportMatrix(bitmap)) return false;
            Result[] decoded = new GenericMultipleBarcodeReader(new MultiFormatReader())
                    .decodeMultiple(bitmap, DECODE_HINTS);
            return decoded.length > 0 && Arrays.stream(decoded).allMatch(result ->
                    result.getBarcodeFormat() == BarcodeFormat.CODE_128 && covered.contains(result.getText()));
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

    private static boolean compact(PDPage page) {
        double unit = page.getUserUnit();
        if (!Double.isFinite(unit) || unit <= 0) return false;
        // PDFRenderer allocates from raw CropBox dimensions even when UserUnit < 1.
        double width = page.getCropBox().getWidth() * Math.max(1d, unit);
        double height = page.getCropBox().getHeight() * Math.max(1d, unit);
        return Double.isFinite(width) && Double.isFinite(height) && width > 0 && height > 0
                && width <= MAXIMUM_POINTS && height <= MAXIMUM_POINTS
                && Math.ceil(width * DPI / 72d) * Math.ceil(height * DPI / 72d) <= MAXIMUM_PIXELS;
    }
}
