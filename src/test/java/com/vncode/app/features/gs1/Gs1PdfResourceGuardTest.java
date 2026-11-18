package com.vncode.app.features.gs1;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.*;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic, tiny input dictionaries; oversized pixel buffers are never allocated by these tests. */
class Gs1PdfResourceGuardTest {
    @Test void tinyPdfWithOversizedDrawnImageIsRejectedBeforePixelAllocation() throws Exception {
        try (var document = new PDDocument()) {
            var page = page(document);
            var image = image(document, 12_000, 12_000);
            try (var content = new PDPageContentStream(document, page)) { content.drawImage(image, 20, 20, 100, 100); }
            byte[] pdf = save(document); assertTrue(pdf.length < 2000);
            try (var loaded = Loader.loadPDF(pdf)) { rejected(loaded); }
        }
    }
    @Test void pixelBudgetAlsoAppliesBelowTheMaximumImageSide() throws Exception {
        try (var document = new PDDocument()) {
            var page = page(document); page.getResources().put(COSName.getPDFName("Scan"), image(document, 5001, 5000));
            rejected(document);
        }
    }
    @Test void oversizedNestedFormImagesAreCheckedEvenBeforeTheyAreDrawn() throws Exception {
        try (var document = new PDDocument()) {
            var resources = page(document).getResources();
            for (int depth = 0; depth < 2; depth++) {
                var form = form(document); resources.put(COSName.getPDFName("Nested"), form); resources = form.getResources();
            }
            resources.put(COSName.getPDFName("Scan"), image(document, 5001, 5000)); rejected(document);
        }
    }
    @Test void softMaskWithoutSubtypeStillHasItsImageDimensionsChecked() throws Exception {
        try (var document = new PDDocument()) {
            var page = page(document); var visible = image(document, 1, 1); var mask = image(document, 12_000, 12_000);
            mask.getCOSObject().removeItem(COSName.SUBTYPE); visible.getCOSObject().setItem(COSName.SMASK, mask);
            page.getResources().put(COSName.getPDFName("Visible"), visible); rejected(document);
        }
    }
    @Test void smallMaskWithoutSubtypeRemainsReadableAndTheSourceIsUnchanged() throws Exception {
        try (var document = new PDDocument()) {
            var page = page(document); var visible = image(document, 1, 1);
            var mask = new PDImageXObject(document, new ByteArrayInputStream(new byte[]{(byte)255}), null, 1, 1, 8, PDDeviceGray.INSTANCE);
            mask.getCOSObject().removeItem(COSName.SUBTYPE); visible.getCOSObject().setItem(COSName.SMASK, mask);
            try (var content = new PDPageContentStream(document, page)) { content.drawImage(visible, 10, 10, 30, 30); }
            byte[] pdf = save(document), before = pdf.clone();
            try (var loaded = Loader.loadPDF(pdf)) { Gs1PdfResourceGuard.requireSafe(loaded, 0); assertNotNull(render(loaded)); }
            assertArrayEquals(before, pdf);
        }
    }
    @Test void streamValuedHardMaskCannotBypassTheDimensionLimit() throws Exception {
        try (var document = new PDDocument()) {
            var page = page(document); var visible = image(document, 1, 1); var mask = image(document, 10001, 1);
            mask.getCOSObject().removeItem(COSName.SUBTYPE); visible.getCOSObject().setItem(COSName.MASK, mask);
            page.getResources().put(COSName.getPDFName("Visible"), visible); rejected(document);
        }
    }
    @Test void recursiveImageMaskIsRejectedWithoutEnteringTheDecoder() throws Exception {
        try (var document = new PDDocument()) {
            var page = page(document); var visible = image(document, 1, 1); visible.getCOSObject().setItem(COSName.SMASK, visible);
            page.getResources().put(COSName.getPDFName("Visible"), visible); rejected(document);
        }
    }
    @Test void combinedPageImageResourcesHaveAnAggregateBound() throws Exception {
        try (var document = new PDDocument()) {
            var page = page(document);
            for (int index = 0; index < 3; index++) page.getResources().put(COSName.getPDFName("Scan" + index), image(document, 5000, 5000));
            rejected(document);
        }
    }
    @Test void benignSharedFormResourcesAreVisitedOnceAndStillRender() throws Exception {
        try (var document = new PDDocument()) {
            var page = page(document); var form = form(document); form.setResources(page.getResources());
            page.getResources().put(COSName.getPDFName("Shared"), form);
            try (var content = new PDFormContentStream(form)) {
                content.setNonStrokingColor(java.awt.Color.RED); content.addRect(10, 10, 100, 100); content.fill();
            }
            try (var content = new PDPageContentStream(document, page)) { content.drawForm(form); }
            try (var loaded = Loader.loadPDF(save(document))) { Gs1PdfResourceGuard.requireSafe(loaded, 0); assertNotNull(render(loaded)); }
        }
    }
    @Test void rotatedNoRotateAppearanceResourceCycleIsRejectedBeforeTransparencyRecursion() throws Exception {
        try (var document = appearanceCycle(true, 90)) { rejected(document); }
    }
    @Test void ordinaryAppearanceBackreferenceIsAllowedWhenTheUnsafeTransparencyRouteIsNotUsed() throws Exception {
        try (var document = appearanceCycle(false, 90)) { Gs1PdfResourceGuard.requireSafe(document, 0); assertNotNull(render(document)); }
        try (var document = appearanceCycle(true, 0)) { Gs1PdfResourceGuard.requireSafe(document, 0); assertNotNull(render(document)); }
    }
    @Test void fullEmbeddedProjectFontDoesNotCountEveryGlyphWidthAsAnImageResource() throws Exception {
        try (var document = new PDDocument(); var fontFile = getClass().getResourceAsStream("/com/vncode/app/assets/fonts/Inter.ttf")) {
            assertNotNull(fontFile); var page = page(document); var font = PDType0Font.load(document, fontFile, false);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(font, 12); content.newLineAtOffset(25, 600); content.showText("Synthetic operator agreement"); content.endText();
            }
            byte[] pdf = save(document); assertTrue(pdf.length < 10 * 1024 * 1024);
            try (var loaded = Loader.loadPDF(pdf)) { Gs1PdfResourceGuard.requireSafe(loaded, 0); assertNotNull(render(loaded)); }
        }
    }
    @Test void unsupportedPageCountOrPageIndexIsRejectedBeforeRendering() throws Exception {
        try (var document = new PDDocument()) {
            page(document); assertThrows(IllegalArgumentException.class, () -> Gs1PdfResourceGuard.requireSafe(document, -1));
            assertThrows(IllegalArgumentException.class, () -> Gs1PdfResourceGuard.requireSafe(document, 1));
            for (int index = 1; index < 201; index++) page(document); rejected(document);
        }
    }
    @Test void invalidPageGeometryAndExcessiveResourceDepthAreRejected() throws Exception {
        try (var document = new PDDocument()) { page(document).setMediaBox(new PDRectangle(21000, 500)); rejected(document); }
        try (var document = new PDDocument()) {
            var resources = page(document).getResources();
            for (int depth = 0; depth < 9; depth++) { var nested = form(document); resources.put(COSName.getPDFName("Nested"), nested); resources = nested.getResources(); }
            rejected(document);
        }
    }
    @Test void appearanceImagesAreCheckedEvenWhenTheyAreNotInPageResources() throws Exception {
        try (var document = new PDDocument()) {
            var page = page(document); var appearance = new PDAppearanceStream(document); appearance.setResources(new PDResources());
            appearance.setBBox(new PDRectangle(100, 100)); appearance.getResources().put(COSName.getPDFName("Scan"), image(document, 12000, 12000));
            var appearances = new PDAppearanceDictionary(); appearances.setNormalAppearance(appearance);
            var annotation = new PDAnnotationSquare(); annotation.setRectangle(new PDRectangle(100, 100)); annotation.setAppearance(appearances); page.getAnnotations().add(annotation);
            rejected(document);
        }
    }
    private static PDDocument appearanceCycle(boolean noRotate, int rotation) throws Exception {
        var document = new PDDocument(); var page = page(document); page.setRotation(rotation);
        var appearance = new PDAppearanceStream(document); appearance.setBBox(new PDRectangle(100, 100)); appearance.setResources(new PDResources());
        appearance.getResources().put(COSName.getPDFName("Self"), appearance);
        try (var content = new PDPageContentStream(document, appearance, appearance.getCOSObject().createOutputStream())) {
            content.setNonStrokingColor(java.awt.Color.RED); content.addRect(5, 5, 50, 50); content.fill();
        }
        var appearances = new PDAppearanceDictionary(); appearances.setNormalAppearance(appearance);
        var annotation = new PDAnnotationSquare(); annotation.setRectangle(new PDRectangle(50, 50, 100, 100)); annotation.setNoRotate(noRotate); annotation.setAppearance(appearances); page.getAnnotations().add(annotation);
        return document;
    }
    private static PDPage page(PDDocument document) { var page = new PDPage(PDRectangle.A4); page.setResources(new PDResources()); document.addPage(page); return page; }
    private static PDFormXObject form(PDDocument document) { var form = new PDFormXObject(document); form.setBBox(PDRectangle.A4); form.setResources(new PDResources()); return form; }
    private static PDImageXObject image(PDDocument document, int width, int height) throws Exception { return new PDImageXObject(document, new ByteArrayInputStream(new byte[]{0}), null, width, height, 8, PDDeviceRGB.INSTANCE); }
    private static byte[] save(PDDocument document) throws Exception { var bytes = new ByteArrayOutputStream(); document.save(bytes); return bytes.toByteArray(); }
    private static java.awt.image.BufferedImage render(PDDocument document) throws Exception { var renderer = new PDFRenderer(document); renderer.setSubsamplingAllowed(true); return renderer.renderImage(0, .5f, ImageType.RGB); }
    private static void rejected(PDDocument document) { assertEquals("gs1.document_resource_limits", assertThrows(IllegalArgumentException.class, () -> Gs1PdfResourceGuard.requireSafe(document, 0)).getMessage()); }
}
