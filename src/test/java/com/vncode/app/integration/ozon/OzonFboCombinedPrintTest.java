package com.vncode.app.integration.ozon;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.google.zxing.oned.Code128Reader;
import com.vncode.app.config.Database;
import com.vncode.app.features.fbo.*;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.print.history.PrintHistoryService;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Ozon FBO must print and recover one physical combined label for each marked unit. */
class OzonFboCombinedPrintTest {
    private static final String GTIN="04610689132457";
    private static final String OZN="OZN1234567890";
    private static final List<String> CODES=List.of(
            "010461068913245721FIXTURE-0001\u001d91ABCD\u001d92SIGNATURE",
            "010461068913245721FIXTURE-0002\u001d91ABCD\u001d92SIGNATURE");
    @TempDir Path temporaryDirectory;
    private Shop shop;

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",temporaryDirectory.resolve("appdata").toString());Database.initDatabase();
        shop=new Shop(1,"Fixture Ozon",Marketplace.OZON,"fixture-client","fixture-secret");
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,?,'OZON',?,?)")){
            p.setString(1,shop.getName());p.setString(2,shop.getClientId());p.setString(3,shop.getApiKey());p.executeUpdate();
        }
        new OzonCatalogRepository().upsertPage(1,List.of(product("101","9001","offer-52",List.of(GTIN,OZN)),product("102","9002","offer-54",List.of("OZN9876543210"))),"");
        new OzonProductKizPolicyRepository().setRequired(1,"9002",false);
        var repository=new ZnackRepository(new ShopContext(1,shop.getName()));repository.upsertProducts(List.of(new Product(GTIN,"Fixture suit",null,null,null,null,null)));
        long order=repository.createDraft(GTIN,2);repository.insertCodes(order,GTIN,new DownloadedCodes(CODES,"fixture-block"));
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE kiz_codes SET legal_status='IN_CIRCULATION' WHERE shop_id=1");
        }
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,com.vncode.app.features.gtinsync.GtinWriteTestFixture.CERTIFICATE,com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);
        var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,Marketplace.OZON,List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(GTIN,"152DASH-52","Коричневый","52",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),java.time.Instant.now(),details)));
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void twoMarkedUnitsProduceTwoDecodableCombinedPagesAndPreserveUnmarkedCopies()throws Exception {
        var required=find("9001");var unmarked=find("9002");
        assertEquals(OZN,required.sku(),"The newly added numeric GTIN must not replace the actual Ozon barcode");
        var plan=new OzonFboKizPrintPlanner().plan(1,List.of(new FboBarcodePrintItem(required,2),new FboBarcodePrintItem(unmarked,1)));
        assertEquals(List.of(FboPrintPage.Kind.COMBINED,FboPrintPage.Kind.COMBINED,FboPrintPage.Kind.BARCODE,FboPrintPage.Kind.BARCODE),plan.pages().stream().map(FboPrintPage::kind).toList());
        assertEquals(2,plan.usedKizs().size());assertEquals(2,count("RESERVED"));
        var exporter=new OzonFboBarcodePdfExporter();var orders=exporter.toOrders(plan.pages());
        assertEquals(4,orders.size());assertEquals(OZN,orders.getFirst().getBarcode());assertNotNull(orders.getFirst().getKiz());assertNull(orders.getLast().getKiz());
        Path labels=temporaryDirectory.resolve("combined.pdf");var published=new AtomicInteger();exporter.exportPlan(plan,labels.toFile(),published::incrementAndGet);assertEquals(1,published.get());
        try(var document=Loader.loadPDF(labels.toFile())){
            assertEquals(4,document.getNumberOfPages());
            for(int page=0;page<2;page++)for(float dpi:new float[]{203,300}){
                assertDimensions(document,page);assertEquals(OZN,decodeBarcode(document,page,dpi));
                var matrix=decodeMatrix(document,page,dpi);assertEquals("]d2",matrix.getResultMetadata().get(ResultMetadataType.SYMBOLOGY_IDENTIFIER));
                assertEquals(KizService.scannerSafeCode(CODES.get(page)),KizService.scannerSafeCode(matrix.getText()));
            }
            for(int page=2;page<4;page++){assertDimensions(document,page);assertEquals("OZN9876543210",decodeBarcode(document,page,203));}
        }
        assertEquals(2,count("RESERVED"),"Rendering alone cannot consume stock outside the publish checkpoint");
    }

    @Test void missingAndAmbiguousActualBarcodesFailBeforeAnyInventoryReservation()throws Exception {
        var selected=find("9001");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM ozon_product_barcodes WHERE shop_id=1 AND product_id='101'");}
        var missing=assertThrows(Exception.class,()->new OzonFboKizPrintPlanner().plan(1,List.of(new FboBarcodePrintItem(selected,1))));
        assertTrue(message(missing).contains("OZON_LABEL_BARCODE_MISSING"));assertEquals(0,count("RESERVED"));assertEquals(2,count("AVAILABLE"));
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) VALUES(1,'101',?)")){
            for(String barcode:List.of(OZN,"OZN9999999999")){p.setString(1,barcode);p.addBatch();}p.executeBatch();
        }
        var ambiguous=assertThrows(Exception.class,()->new OzonFboKizPrintPlanner().plan(1,List.of(new FboBarcodePrintItem(selected,1))));
        assertTrue(message(ambiguous).contains("OZON_LABEL_BARCODE_AMBIGUOUS"));assertEquals(0,count("RESERVED"));assertEquals(2,count("AVAILABLE"));
    }

    @Test void anotherSellerProductCannotReuseSelectedSkuAndBarcode()throws Exception {
        var actual=find("9001");var wrong=new FboProductSku(actual.nmId(),actual.vendorCode(),actual.subjectName(),actual.brand(),actual.title(),actual.color(),actual.size(),actual.ruSize(),actual.sku(),actual.imageUrl(),actual.requiresKiz(),actual.catalogSku(),actual.variantId(),actual.verifiedGtin(),"different-product");
        var mismatch=assertThrows(Exception.class,()->new OzonFboKizPrintPlanner().plan(1,List.of(new FboBarcodePrintItem(wrong,1))));
        assertTrue(message(mismatch).contains("OZON_LABEL_PRODUCT_MISMATCH"));assertEquals(0,count("RESERVED"));assertEquals(2,count("AVAILABLE"));
    }

    @Test void unusableKizCannotPublishOrConsumeAndReservationCanBeReleased()throws Exception {
        var plan=new OzonFboKizPrintPlanner().plan(1,List.of(new FboBarcodePrintItem(find("9001"),1)));var original=plan.pages().getFirst();
        var invalid=new FboPrintPlan(List.of(new FboPrintPage(original.product(),"X".repeat(5000),original.pairNumber(),FboPrintPage.Kind.COMBINED,original.categoryStock())),plan.usedKizs());
        Path labels=temporaryDirectory.resolve("unchanged.pdf");byte[] prior="existing seller document".getBytes(StandardCharsets.UTF_8);Files.write(labels,prior);var publish=new AtomicInteger();
        assertThrows(IOException.class,()->new OzonFboBarcodePdfExporter().exportPlan(invalid,labels.toFile(),publish::incrementAndGet));
        assertArrayEquals(prior,Files.readAllBytes(labels));assertEquals(0,publish.get());assertEquals(1,count("RESERVED"));assertEquals(0,count("CONSUMED"));
        new ZnackGtinInventoryService().release(1,plan.usedKizs());assertEquals(2,count("AVAILABLE"));
    }

    @Test void publishedCheckpointRecoversExactCombinedHistoryWithoutCatalogLicenseOrMoreStock()throws Exception {
        var plan=new OzonFboKizPrintPlanner().plan(1,List.of(new FboBarcodePrintItem(find("9001"),2)));var exporter=new OzonFboBarcodePdfExporter();var history=new PrintHistoryService();
        String layout=OzonFboBarcodePdfExporter.frozenLayout(plan);assertTrue(layout.contains("OZON_FBO_COMBINED_V1"));
        assertFalse(layout.contains(CODES.getFirst()));long journal=history.recordPreparedFboJob(shop,OzonFboBarcodePdfExporter.TEMPLATE_NAME,layout,exporter.toOrders(plan.pages()));
        Path impossible=Files.createDirectory(temporaryDirectory.resolve("occupied.pdf"));Files.writeString(impossible.resolve("keep.txt"),"keep");
        assertThrows(IOException.class,()->exporter.exportPlan(plan,impossible.toFile(),()->new ZnackGtinInventoryService().consumeForFboPrint(1,plan.usedKizs(),journal)));
        var job=history.getJobs(1).stream().filter(j->j.id()==journal).findFirst().orElseThrow();assertEquals("pdf_ready",job.status());assertTrue(job.canReprint());assertEquals(2,count("CONSUMED"));assertEquals(0,count("AVAILABLE"));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM ozon_product_barcodes WHERE shop_id=1");s.execute("DELETE FROM ozon_products WHERE shop_id=1");}
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temporaryDirectory.resolve("denied-license"))){
            LicenseRuntime.install(denied);Path recovered=temporaryDirectory.resolve("recovered.pdf");history.reprint(job,recovered.toFile(),temporaryDirectory.resolve("details.pdf").toFile());
            try(var document=Loader.loadPDF(recovered.toFile())){
                assertEquals(2,document.getNumberOfPages());for(int page=0;page<2;page++){
                    assertEquals(OZN,decodeBarcode(document,page,203));assertEquals(KizService.scannerSafeCode(CODES.get(page)),KizService.scannerSafeCode(decodeMatrix(document,page,300).getText()));
                }
            }
        }finally{LicenseRuntime.install(prior);}
        assertEquals(2,count("CONSUMED"));assertEquals(0,count("AVAILABLE"));assertEquals(2,history.getItems(journal).size());
    }

    private FboProductSku find(String sku){return new OzonFboProductRepository().search(new FboProductSearchCriteria(1,sku,List.of(),10,0)).getFirst();}
    private int count(String status)throws Exception{try(var c=Database.getConnection();var p=c.prepareStatement("SELECT count(*) FROM kiz_codes WHERE shop_id=1 AND status=?")){p.setString(1,status);try(var r=p.executeQuery()){r.next();return r.getInt(1);}}}
    private static OzonProductDto product(String id,String sku,String offer,List<String> barcodes){return new OzonProductDto(id,offer,sku,"Костюм мужской","","152DASH-52","Коричневый","52","Костюмы","Мужской",false,"",barcodes);}
    private static String message(Throwable failure){StringBuilder text=new StringBuilder();for(var e=failure;e!=null;e=e.getCause())text.append(e.getMessage()).append(' ');return text.toString();}
    private static void assertDimensions(PDDocument pdf,int page){assertEquals(58,pdf.getPage(page).getMediaBox().getWidth()*25.4/72,.2);assertEquals(40,pdf.getPage(page).getMediaBox().getHeight()*25.4/72,.2);}
    private static Result decodeMatrix(PDDocument document,int pageIndex,float dpi)throws Exception{
        BufferedImage page=new PDFRenderer(document).renderImageWithDPI(pageIndex,dpi,ImageType.GRAY);float scale=dpi/72f;int margin=Math.round(3*scale),x=Math.max(0,Math.round((float)(2.5*72/25.4)*scale)-margin),y=Math.max(0,Math.round((float)(5*72/25.4)*scale)-margin);int side=Math.min(Math.round(50*scale)+2*margin,Math.min(page.getWidth()-x,page.getHeight()-y));
        return new DataMatrixReader().decode(bitmap(page.getSubimage(x,y,side,side)),Map.of(DecodeHintType.TRY_HARDER,true));
    }
    private static String decodeBarcode(PDDocument document,int pageIndex,float dpi)throws Exception{
        BufferedImage page=new PDFRenderer(document).renderImageWithDPI(pageIndex,dpi,ImageType.GRAY);int y=Math.round((float)(27.5*dpi/25.4));return new Code128Reader().decode(bitmap(page.getSubimage(0,y,page.getWidth(),page.getHeight()-y)),Map.of(DecodeHintType.TRY_HARDER,true)).getText();
    }
    private static BinaryBitmap bitmap(BufferedImage image){return new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));}
}
