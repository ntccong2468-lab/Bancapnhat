package com.vncode.app.integration.ozon;

import com.itextpdf.barcodes.Barcode128;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonProductBarcodePageTest {
    @TempDir Path temporary;
    @Test void largeVectorBarcodeIsRecognizedWithoutRasterizingTheBatch() throws Exception {
        Path file=temporary.resolve("vector.pdf");
        try(var pdf=new PdfDocument(new PdfWriter(file.toFile()))){
            var page=pdf.addNewPage(new PageSize(283.46f,425.2f));
            var barcode=new Barcode128(pdf);barcode.setCode("OZN1234567890");barcode.setX(1.5f);barcode.setBarHeight(60);
            var canvas=new PdfCanvas(page).saveState().concatMatrix(1,0,0,1,20,100);
            barcode.placeBarcode(canvas,ColorConstants.BLACK,ColorConstants.BLACK);canvas.restoreState();
        }
        try(var pdf=new PdfDocument(new PdfReader(file.toFile()))){
            assertTrue(new OzonProductBarcodePage(Set.of("OZN1234567890")).matches(pdf.getFirstPage()));
            assertFalse(new OzonProductBarcodePage(Set.of("OZN9876543210")).matches(pdf.getFirstPage()));
        }
    }
}
