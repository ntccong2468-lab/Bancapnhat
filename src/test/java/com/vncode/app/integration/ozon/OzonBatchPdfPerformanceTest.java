package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.*;

import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local wall-clock benchmark, with real exportAll composition and a localhost label API. */
class OzonBatchPdfPerformanceTest {
    @TempDir Path temp;
    private MockWebServer server;
    private Shop shop;
    private com.vncode.app.features.license.LicenseService prior;
    private com.vncode.app.features.license.LicenseService license;

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", temp.resolve("appdata").toString());
        Database.initDatabase();
        try (var connection=Database.getConnection(); var sql=connection.createStatement()) {
            sql.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'shop','OZON','id','key')");
        }
        prior=LicenseRuntime.service();
        license=LicenseDomainFixture.active(temp.resolve("license"));
        LicenseRuntime.install(license);
        server=new MockWebServer();server.start();
        shop=new Shop(1,"shop",Marketplace.OZON,"id","key");
    }

    @AfterEach void cleanup() throws Exception {
        if(server!=null)server.shutdown();
        if(prior!=null)LicenseRuntime.install(prior);
        if(license!=null)license.close();
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void benchmarkOneHundredOrders() throws Exception { benchmark(100); }
    @Test void benchmarkOneThousandOrders() throws Exception { benchmark(1000); }

    private void benchmark(int count) throws Exception {
        List<String> numbers=java.util.stream.IntStream.range(0,count)
                .mapToObj(i->"96000000-"+String.format("%04d",i)+"-1").toList();
        var postings=numbers.stream().map(number->new OzonPostingDto(number,number,number,"awaiting_deliver",
                "","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,
                List.of(new OzonPostingItemDto(0,"product","sku","offer","Product",1,"RUB","1")))).toList();
        new OzonPostingRepository().upsertPage(1,postings);
        new OzonProductKizPolicyRepository().setRequired(1,"sku",false);

        var reversed=new ArrayList<>(numbers);Collections.reverse(reversed);
        var source=new ByteArrayOutputStream();
        try(var pdf=new PdfDocument(new PdfWriter(source))) {
            var font=PdfFontFactory.createFont();
            for(String number:reversed)new PdfCanvas(pdf.addNewPage()).beginText()
                    .setFontAndSize(font,12).moveText(20,20).showText(number).endText();
        }
        server.enqueue(json("{\"task_id\":7001}"));
        server.enqueue(json("{\"status\":\"completed\",\"file_url\":\""+server.url("/cdn/labels.pdf")+"\"}"));
        server.enqueue(new MockResponse().setBody(new okio.Buffer().write(source.toByteArray())));
        var service=new OzonLabelService(new OzonLabelRepository(),(id,credentials)->new OzonApiClient(id,credentials,
                server.url("/"),new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(10)).build(),
                new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));
        var downloader=new OzonPrintBundleService.OfficialLabelDownloader() {
            public File download(Shop owner,String number,File target) throws IOException {
                throw new AssertionError("The recognized shared PDF must not trigger individual downloads");
            }
            public OzonPrintBundleService.OfficialLabelDownloader forBatch(Shop owner,List<String> selected) {
                return new OzonBatchLabelDownloader(service,owner,selected);
            }
        };
        var exporter=new OzonPrintBundleService(new OzonPostingRepository(),new OzonExemplarJobRepository(),
                (owner,number)->new OzonPreparationResult(number,"NOT_REQUIRED",0,true,false,""),downloader);
        Path labels=temp.resolve("labels.pdf"),picking=temp.resolve("picking.pdf");
        long started=System.nanoTime();
        var result=exporter.exportAll(shop,numbers,labels.toFile(),picking.toFile());
        long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        System.out.printf("OZON_EXPORT_ALL_LOCAL_BENCHMARK orders=%d elapsed_ms=%d api=localhost images=none kiz=none preparation=mock%n",count,millis);
        assertEquals(count,result.postingCount());assertEquals(count,result.totalPages());
        assertEquals(3,server.getRequestCount(),"Single create, poll and download for each local benchmark");
        var request=server.takeRequest(1,TimeUnit.SECONDS);
        assertEquals("/v3/posting/fbs/package-label/create",request.getPath());
        assertEquals(count,com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject()
                .getAsJsonArray("posting_numbers").size());
        try(var pdf=new PdfDocument(new PdfReader(labels.toFile()))) {
            assertEquals(count,pdf.getNumberOfPages());
            for(int i=0;i<count;i++)assertEquals(numbers.get(i),PdfTextExtractor.getTextFromPage(pdf.getPage(i+1)).strip());
        }
    }

    private static MockResponse json(String value) {
        return new MockResponse().setHeader("Content-Type","application/json").setBody(value);
    }
}
