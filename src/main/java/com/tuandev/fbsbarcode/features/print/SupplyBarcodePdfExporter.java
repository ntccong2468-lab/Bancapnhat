package com.tuandev.fbsbarcode.features.print;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class SupplyBarcodePdfExporter {
    private SupplyBarcodePdfExporter() {
    }

    public static byte[] exportSingleLabel(byte[] imageBytes) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            return new byte[0];
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PageSize pageSize = new PageSize(
                (float) PrintTemplateService.PAGE_WIDTH,
                (float) PrintTemplateService.PAGE_HEIGHT
        );

        try (PdfWriter writer = new PdfWriter(output);
             PdfDocument pdfDocument = new PdfDocument(writer);
             Document document = new Document(pdfDocument, pageSize)) {
            document.setMargins(0, 0, 0, 0);

            Image image = new Image(ImageDataFactory.create(imageBytes));
            image.scaleAbsolute(pageSize.getWidth(), pageSize.getHeight());
            image.setFixedPosition(0, 0);
            document.add(image);
        }

        return output.toByteArray();
    }
}
