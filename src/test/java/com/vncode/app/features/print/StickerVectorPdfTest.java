package com.vncode.app.features.print;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.itextpdf.kernel.pdf.*;
import com.vncode.app.models.Order;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StickerVectorPdfTest {
    @TempDir Path directory;
    @Test void allFiveQrPlacementsScanAtThermalResolutionsInBothPageOrders()throws Exception {
        for(var pageOrder:PrintPageOrder.values()) {
            var order=new Order();order.setId(123L);order.setName("Тестовая одежда");order.setBarcode("8938501432101");
            order.setSticker("TEST 12");order.setStickerCode("Тест-WB-123456789");
            order.setKiz("010000000000000021DEMO000000001\u001d91TEST\u001d92NOTVALID");
            var target=directory.resolve(pageOrder.name()+".pdf");
            new BarcodePrintService().export(new PrintTemplateService().createSystemDefaultTemplate("Test"),List.of(order),target.toFile(),new PrintJobOptions(pageOrder,1));
            int page=pageOrder==PrintPageOrder.STICKER_THEN_BARCODE?0:1;
            try(var pdf=Loader.loadPDF(target.toFile())) {
                assertEquals(2,pdf.getNumberOfPages());
                float width=pdf.getPage(page).getMediaBox().getWidth(),height=pdf.getPage(page).getMediaBox().getHeight();
                double[][] positions={{width/2-40,height/2-40,80},{8,height-38,30},{width-38,height-38,30},{8,8,30},{width-38,8,30}};
                for(int dpi:new int[]{203,300}) {
                    var image=new PDFRenderer(pdf).renderImageWithDPI(page,dpi,ImageType.GRAY);
                    for(var pos:positions){double scale=dpi/72d;int x=(int)Math.floor(pos[0]*scale),y=(int)Math.floor((height-pos[1]-pos[2])*scale);int side=(int)Math.ceil(pos[2]*scale);
                        var crop=image.getSubimage(x,y,Math.min(side+1,image.getWidth()-x),Math.min(side+1,image.getHeight()-y));
                        var bitmap=new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(crop)));
                        assertEquals(order.getStickerCode(),new QRCodeReader().decode(bitmap,Map.of(DecodeHintType.TRY_HARDER,true)).getText());
                    }
                    image.flush();
                }
            }
            try(var pdf=new PdfDocument(new PdfReader(target.toFile()))){
                var forms=pdf.getPage(page+1).getResources().getPdfObject().getAsDictionary(PdfName.XObject);
                assertEquals(1,forms.size(),"The five QR placements share one module form instead of raster images");
                assertEquals(PdfName.Form,forms.getAsStream(forms.keySet().iterator().next()).getAsName(PdfName.Subtype));
            }
        }
    }
}
