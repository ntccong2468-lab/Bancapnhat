package com.vncode.app.integration.ozon;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonCarrierLabelNumbersTest {
    @TempDir Path temporary;

    @Test void reversedBatchReadsFullPrintedCarrierNumbersIncludingBlackBoxSuffix() throws Exception {
        Path file=temporary.resolve("reversed.pdf");
        try(var pdf=new PdfDocument(new PdfWriter(file.toFile()))){
            label(pdf,"ii2222222","8099");label(pdf,"ii1111111","4801");
        }
        try(var pdf=new PdfDocument(new PdfReader(file.toFile()))){
            var numbers=OzonCarrierLabelNumbers.read(file.toFile(),pdf,List.of(1,2),Map.of(
                    "70599234-0480-1",posting("70599234-0480-1","123456789012345").withScanit("ii11111114801"),
                    "70599234-0809-9",posting("70599234-0809-9","123456789098765").withScanit("ii22222228099")));
            assertEquals(Map.of("70599234-0480-1","ii11111114801","70599234-0809-9","ii22222228099"),numbers);
        }
    }

    @Test void exactScanitTakesPriorityOverOtherLargePrintedNumbers() throws Exception {
        Path file=temporary.resolve("multiple-numbers.pdf");
        try(var pdf=new PdfDocument(new PdfWriter(file.toFile()))){
            label(pdf,"ii1111111","4801");
            new PdfCanvas(pdf.getFirstPage()).beginText().setFontAndSize(PdfFontFactory.createFont(),20).moveText(3,80).showText("ART123456789").endText();
        }
        try(var pdf=new PdfDocument(new PdfReader(file.toFile()))){
            assertEquals(Map.of("POST-A","ii11111114801"),OzonCarrierLabelNumbers.read(file.toFile(),pdf,List.of(1),
                    Map.of("POST-A",posting("POST-A","").withScanit("ii11111114801"))));
        }
    }

    @Test void opaqueBatchNeverAssignsNumbersByPageOrder() throws Exception {
        Path file=temporary.resolve("opaque.pdf");
        try(var pdf=new PdfDocument(new PdfWriter(file.toFile()))){label(pdf,"ii1111111","4801");}
        try(var pdf=new PdfDocument(new PdfReader(file.toFile()))){
            assertTrue(OzonCarrierLabelNumbers.read(file.toFile(),pdf,List.of(1),Map.of(
                    "POST-A",posting("POST-A",""),"POST-B",posting("POST-B",""))).isEmpty());
        }
    }

    @Test void singleConfirmedPostingCanReadNativeCaptionButCannotSubstituteProductBarcodeOrGtin() throws Exception {
        Path file=temporary.resolve("single.pdf");
        try(var pdf=new PdfDocument(new PdfWriter(file.toFile()))){label(pdf,"ii1111111","4801");label(pdf,"OZN1234567","9999");label(pdf,"0461000000","8888");}
        try(var pdf=new PdfDocument(new PdfReader(file.toFile()))){
            assertEquals(Map.of("POST-A","ii11111114801"),OzonCarrierLabelNumbers.read(file.toFile(),pdf,List.of(1,2,3),Map.of("POST-A",posting("POST-A",""))));
        }
    }

    @Test void twoDifferentCarrierNumbersForOnePostingRequireReview() throws Exception {
        Path file=temporary.resolve("conflict.pdf");
        try(var pdf=new PdfDocument(new PdfWriter(file.toFile()))){label(pdf,"ii1111111","4801");label(pdf,"ii2222222","8099");}
        try(var pdf=new PdfDocument(new PdfReader(file.toFile()))){
            assertTrue(OzonCarrierLabelNumbers.read(file.toFile(),pdf,List.of(1,2),Map.of("POST-A",posting("POST-A",""))).isEmpty());
        }
    }

    private static OzonPostingDto posting(String number,String carrier) {
        return new OzonPostingDto(number,number,number,"awaiting_deliver","","","","",carrier,"",null,List.of(),false,List.of());
    }
    private static void label(PdfDocument pdf,String prefix,String suffix) throws Exception {
        var page=pdf.addNewPage(new PageSize(164.4f,113.4f));
        var font=PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD);
        new PdfCanvas(page).beginText().setFontAndSize(font,14).moveText(8,24).showText(prefix).endText()
                .saveState().setFillColor(ColorConstants.BLACK).rectangle(101,20,58,21).fill()
                .setFillColor(ColorConstants.WHITE).beginText().setFontAndSize(font,14).moveText(105,24).showText(" "+suffix).endText().restoreState();
    }
}
