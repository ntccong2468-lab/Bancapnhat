package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.itextpdf.barcodes.Barcode128;
import com.itextpdf.barcodes.BarcodeDataMatrix;
import com.itextpdf.barcodes.BarcodeQRCode;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonOfficialLabelSelectorTest {
    private static final String BARCODE = "OZN1234567890";
    private static final PageSize COMPACT = new PageSize((float) (58 * 72d / 25.4), (float) (40 * 72d / 25.4));
    @TempDir Path temporaryDirectory;

    @Test void removesStandaloneProductPageEvenWithoutReadableCaption() throws Exception {
        Path official = write("product.pdf", (document, page) -> barcode(document, page, BARCODE));
        assertEquals(List.of(1), select(official, posting(1), List.of(binding(0, 100))));
    }

    @Test void keepsCarrierQrPageEvenWhenItAlsoContainsCoveredProductBarcode() throws Exception {
        Path official = write("carrier-qr.pdf", (document, page) -> {
            barcode(document, page, BARCODE);
            var qr = new BarcodeQRCode("OZON-TRANSPORT-ROUTE-42")
                    .createFormXObject(ColorConstants.BLACK, document);
            new PdfCanvas(page).addXObjectWithTransformationMatrix(qr, 2, 0, 0, 2, 10, 50);
        });
        assertEquals(List.of(1, 2), select(official, posting(1), List.of(binding(0, 100))));
    }

    @Test void keepsUnknownCarrierBarcodeInsteadOfAssumingSecondPageIsProduct() throws Exception {
        Path official = write("carrier-barcode.pdf", (document, page) -> barcode(document, page, "ROUTE1234567"));
        assertEquals(List.of(1, 2), select(official, posting(1), List.of(binding(0, 100))));
    }

    @Test void keepsCarrierDataMatrixAlongsideCoveredProductBarcode() throws Exception {
        Path official = write("carrier-matrix.pdf", (document, page) -> {
            barcode(document, page, BARCODE);
            BarcodeDataMatrix matrix = new BarcodeDataMatrix();
            assertEquals(BarcodeDataMatrix.DM_NO_ERROR, matrix.setCode("TRANSPORT42"));
            PdfCanvas canvas = new PdfCanvas(page);
            canvas.saveState().concatMatrix(1, 0, 0, 1, 10, 60);
            matrix.placeBarcode(canvas, ColorConstants.BLACK, 2);
            canvas.restoreState();
        });
        assertEquals(List.of(1, 2), select(official, posting(1), List.of(binding(0, 100))));
    }

    @Test void keepsPageContainingBothProductAndAnotherBarcode() throws Exception {
        Path official = write("two-barcodes.pdf", (document, page) -> {
            barcode(document, page, BARCODE);
            drawBarcode(document, page, "ROUTE1234567", 65);
        });
        assertEquals(List.of(1, 2), select(official, posting(1), List.of(binding(0, 100))));
    }

    @Test void readableBarcodeCaptionAloneIsNotProofOfProductLabel() throws Exception {
        Path official = write("caption-only.pdf", (document, page) -> text(page, BARCODE));
        assertEquals(List.of(1, 2), select(official, posting(1), List.of(binding(0, 100))));
    }

    @Test void keepsProductPageWhenPostingIdentityIsPrintedOnIt() throws Exception {
        Path official = write("posting-id.pdf", (document, page) -> {
            barcode(document, page, BARCODE);
            text(page, "POST-1");
        });
        assertEquals(List.of(1, 2), select(official, posting(1), List.of(binding(0, 100))));
    }

    @Test void removesProductPagesRegardlessOfKizCoverage() throws Exception {
        Path official = write("no-kiz.pdf", (document, page) -> barcode(document, page, BARCODE));
        assertEquals(List.of(1), select(official, posting(1), List.of()));
        assertEquals(List.of(1), select(official, posting(2), List.of(binding(0, 100))));
    }

    @Test void productFirstPageIsExcludedWithoutAssumingOddOrEvenPages() throws Exception {
        Path official = temporaryDirectory.resolve("product-first.pdf");
        try (PdfDocument document = new PdfDocument(new PdfWriter(official.toFile()))) {
            barcode(document, document.addNewPage(COMPACT), BARCODE);
            text(document.addNewPage(COMPACT), "Shipping POST-1");
            barcode(document, document.addNewPage(COMPACT), BARCODE);
            text(document.addNewPage(COMPACT), "Carrier continuation");
        }
        assertEquals(List.of(2, 4), select(official, posting(1), List.of()));
    }

    @Test void missingProductDataNeverRemovesUnknownCarrierPage() throws Exception {
        Path official = write("unknown.pdf", (document, page) -> barcode(document, page, BARCODE));
        try (PdfDocument source = new PdfDocument(new PdfReader(official.toFile()))) {
            assertEquals(List.of(1, 2), OzonOfficialLabelSelector.shippingPages(official.toFile(), source,
                    List.of(1, 2), List.of(posting(1)), Map.of()));
        }
    }

    @Test void removesLargeStandaloneProductBarcodePage() throws Exception {
        Path official = temporaryDirectory.resolve("large.pdf");
        try (PdfDocument document = new PdfDocument(new PdfWriter(official.toFile()))) {
            document.addNewPage(COMPACT);
            barcode(document, document.addNewPage(PageSize.A4), BARCODE);
        }
        assertEquals(List.of(1), select(official, posting(1), List.of(binding(0, 100))));
    }

    @Test void sameProductBarcodeOnSeveralItemsDoesNotRequireCombinedKizCoverage() throws Exception {
        Path official = write("shared-barcode.pdf", (document, page) -> barcode(document, page, BARCODE));
        OzonPostingDto original = posting(1);
        OzonPostingDto posting = new OzonPostingDto(original.postingNumber(), original.orderId(),
                original.orderNumber(), original.status(), "", "", "", "", "", "", null, List.of(), false,
                List.of(original.items().getFirst(),
                        new OzonPostingItemDto(1, "9002", "102", "offer-54", "Suit", 1, "RUB", "1")));
        Map<String, OzonProductDto> products = Map.of(
                "101", new OzonProductDto("9001", "offer-52", "101", "Suit", "", false, "", List.of(BARCODE)),
                "102", new OzonProductDto("9002", "offer-54", "102", "Suit", "", false, "", List.of(BARCODE)));
        try (PdfDocument source = new PdfDocument(new PdfReader(official.toFile()))) {
            assertEquals(List.of(1), OzonOfficialLabelSelector.shippingPages(official.toFile(), source,
                    List.of(1, 2), List.of(posting), products));
        }
    }

    @Test void removesTheRegisteredEanProductLabelWhenTheCardAlsoHasAnOznBarcode() throws Exception {
        String ean="4006381333931";
        Path official=write("ean-product.pdf",(document,page)->{
            var barcode=new com.itextpdf.barcodes.BarcodeEAN(document);barcode.setCodeType(com.itextpdf.barcodes.BarcodeEAN.EAN13);
            barcode.setCode(ean);barcode.setBarHeight(40);barcode.setX(1);
            new PdfCanvas(page).addXObjectAt(barcode.createFormXObject(document),10,20);
            text(page,"Product article");
        });
        var product=new OzonProductDto("9001","offer-52","101","Suit","",false,"",List.of(BARCODE,ean));
        try(var source=new PdfDocument(new PdfReader(official.toFile()))){
            assertEquals(List.of(1),OzonOfficialLabelSelector.shippingPages(official.toFile(),source,List.of(1,2),List.of(posting(1)),Map.of("101",product)));
        }
        // A positive decoded barcode needs exact catalog ownership, not merely an EAN shape.
        assertEquals(List.of(1,2),select(official,posting(1),List.of()));
    }

    @Test void nativeCarrierScanitWinsOverAnOverlappingProductBarcode() throws Exception {
        Path official=write("carrier-scanit.pdf",(document,page)->{barcode(document,page,BARCODE);text(page,BARCODE);});
        var p=posting(1);var carrier=new OzonPostingDto(p.postingNumber(),p.orderId(),p.orderNumber(),p.status(),p.substatus(),p.warehouseId(),p.shipmentAt(),p.inProcessAt(),p.lowerBarcode(),p.upperBarcode(),p.requirements(),p.availableActions(),p.shipAvailable(),p.items(),null,null,BARCODE);
        assertEquals(List.of(1,2),select(official,carrier,List.of()));
    }

    private List<Integer> select(Path official, OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings) throws IOException {
        try (PdfDocument source = new PdfDocument(new PdfReader(official.toFile()))) {
            return OzonOfficialLabelSelector.shippingPages(official.toFile(), source,
                    java.util.stream.IntStream.rangeClosed(1, source.getNumberOfPages()).boxed().toList(), List.of(posting),
                    Map.of("101", new OzonProductDto("9001", "offer-52", "101", "Suit", "", false,
                            "", List.of(BARCODE))));
        }
    }

    private static OzonPostingDto posting(int quantity) {
        return new OzonPostingDto("POST-1", "ORDER-1", "100001", "awaiting_deliver", "", "",
                "", "", "LOWER", "UPPER", null, List.of(), false,
                List.of(new OzonPostingItemDto(0, "9001", "101", "offer-52", "Suit", quantity, "RUB", "1")));
    }

    private static OzonExemplarJobRepository.KizBinding binding(int unit, long kizId) {
        return new OzonExemplarJobRepository.KizBinding(0, "9001", "unit-" + unit, unit, kizId, "test-kiz-" + kizId);
    }

    @FunctionalInterface private interface PageWriter { void write(PdfDocument document, PdfPage page) throws IOException; }

    private Path write(String name, PageWriter writer) throws IOException {
        Path target = temporaryDirectory.resolve(name);
        try (PdfDocument document = new PdfDocument(new PdfWriter(target.toFile()))) {
            PdfPage first = document.addNewPage(COMPACT);
            // Shipping identity wins even when a carrier label contains the product barcode.
            text(first, "Shipping POST-1");
            barcode(document, first, BARCODE);
            writer.write(document, document.addNewPage(COMPACT));
        }
        return target;
    }

    private static void barcode(PdfDocument document, PdfPage page, String value) {
        drawBarcode(document, page, value, 16);
    }

    private static void drawBarcode(PdfDocument document, PdfPage page, String value, float y) {
        Barcode128 barcode = new Barcode128(document);
        barcode.setCode(value);
        barcode.setFont(null);
        barcode.setBarHeight(24);
        barcode.setX(0.8f);
        PdfCanvas canvas = new PdfCanvas(page);
        canvas.saveState().concatMatrix(1, 0, 0, 1, 12, y);
        barcode.placeBarcode(canvas, ColorConstants.BLACK, null);
        canvas.restoreState();
    }

    private static void text(PdfPage page, String value) throws IOException {
        new PdfCanvas(page).beginText().setFontAndSize(PdfFontFactory.createFont(), 8)
                .moveText(10, 90).showText(value).endText();
    }
}
