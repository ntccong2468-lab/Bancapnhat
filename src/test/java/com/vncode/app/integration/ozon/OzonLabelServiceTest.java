package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonLabelServiceTest {
    @TempDir
    Path temporaryDirectory;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        System.setProperty("vncode.appdata.dir", temporaryDirectory.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,?,?,?,?)")) {
            statement.setString(1, "Ozon");
            statement.setString(2, Marketplace.OZON.name());
            statement.setString(3, "client-1");
            statement.setString(4, "secret");
            statement.executeUpdate();
        }
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ozon_postings(shop_id,posting_number,status,synced_at)
                        VALUES(1,'posting-1','awaiting_deliver','2026-08-18T00:00:00Z')
                        """)) {
            statement.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("vncode.appdata.dir");
        server.shutdown();
    }

    @Test
    void legacyNumericTaskIsPolledAsAnIntegerAccordingToTheOfficialSchema()throws Exception{
        var repository=new OzonLabelRepository();repository.findOrCreate(1,"posting-1");repository.update(1,"posting-1","7001","POLLING",null,null);
        byte[] pdf=textPdf("LEGACY-POSTING");
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public MockResponse dispatch(RecordedRequest request){
            if(request.getPath().equals("/v1/posting/fbs/package-label/get")){
                var task=com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().getAsJsonPrimitive("task_id");
                return task.isNumber()?json("{\"result\":{\"status\":\"completed\",\"file_url\":\""+server.url("/cdn/legacy.pdf")+"\"}}"):
                        new MockResponse().setResponseCode(400).setBody("{\"code\":3,\"message\":\"task_id must be an integer\"}");
            }
            return new MockResponse().setBody(new okio.Buffer().write(pdf));
        }});
        var file=temporaryDirectory.resolve("legacy-number.pdf").toFile();new OzonLabelService(repository,this::client).downloadOfficialPdf(owner(),"posting-1",file);
        assertEquals("LEGACY-POSTING",pageText(file));assertEquals(2,server.getRequestCount());
    }

    @Test
    void newSinglePostingUsesTheCurrentV3CreateAndV2NumericGetProtocol()throws Exception{
        byte[] pdf=textPdf("CURRENT-POSTING");
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public MockResponse dispatch(RecordedRequest request){
            if(request.getPath().equals("/v3/posting/fbs/package-label/create")){
                var body=com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
                if(!body.has("posting_numbers")||body.getAsJsonArray("posting_numbers").size()!=1||!body.getAsJsonArray("posting_numbers").get(0).getAsString().equals("posting-1"))return new MockResponse().setResponseCode(400);
                return json("{\"tasks\":[{\"task_id\":8001,\"task_type\":\"big_label\"}]}");
            }
            if(request.getPath().equals("/v2/posting/fbs/package-label/get")){
                var task=com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().getAsJsonPrimitive("task_id");
                return task.isNumber()?json("{\"status\":{\"code\":\"completed\"},\"file_url\":\""+server.url("/cdn/current.pdf")+"\"}"):new MockResponse().setResponseCode(400);
            }
            if(request.getPath().equals("/cdn/current.pdf"))return new MockResponse().setBody(new okio.Buffer().write(pdf));
            return new MockResponse().setResponseCode(410).setBody("{\"code\":3,\"message\":\"Old label create endpoint disabled\"}");
        }});
        var file=temporaryDirectory.resolve("current-single.pdf").toFile();new OzonLabelService(new OzonLabelRepository(),this::client).downloadOfficialPdf(owner(),"posting-1",file);
        assertEquals("CURRENT-POSTING",pageText(file));assertEquals(3,server.getRequestCount());assertEquals("v3s:8001",new OzonLabelRepository().find(1,"posting-1").taskId());
    }

    @Test
    void aSlowSingleTaskKeepsPollingTheSameReceiptBeyondTheOldSixSecondWindow()throws Exception{
        server.enqueue(json("{\"tasks\":[{\"task_id\":9001,\"task_type\":\"big_label\"}]}"));
        for(int n=0;n<13;n++)server.enqueue(json("{\"status\":{\"code\":\"in_progress\"}}"));
        server.enqueue(json("{\"status\":{\"code\":\"completed\"},\"file_url\":\""+server.url("/cdn/slow.pdf")+"\"}"));
        server.enqueue(new MockResponse().setBody(new okio.Buffer().write(textPdf("SLOW-POSTING"))));
        var waits=new java.util.concurrent.atomic.AtomicInteger();var service=new OzonLabelService(new OzonLabelRepository(),this::client,duration->waits.incrementAndGet());
        var file=temporaryDirectory.resolve("slow-single.pdf").toFile();service.downloadOfficialPdf(owner(),"posting-1",file);
        assertEquals("SLOW-POSTING",pageText(file));assertEquals(13,waits.get());assertEquals(16,server.getRequestCount());
        var paths=new java.util.ArrayList<String>();for(int n=0;n<16;n++)paths.add(server.takeRequest(1,TimeUnit.SECONDS).getPath());
        assertEquals(1,paths.stream().filter(p->p.endsWith("/create")).count());assertEquals(14,paths.stream().filter(p->p.endsWith("/get")).count());
    }

    @Test
    void deniedNewLabelLeavesNoNewJobAndSendsNoHttp() throws Exception {
        var prior = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(temporaryDirectory.resolve("denied"))) {
            LicenseRuntime.install(denied);
            var repository = new OzonLabelRepository();
            assertThrows(LicenseGate.LicenseDeniedException.class, () ->
                    new OzonLabelService(repository, this::client).downloadOfficialPdf(
                            new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret"),
                            "posting-1", temporaryDirectory.resolve("denied.pdf").toFile()));
            assertNull(repository.find(1, "posting-1"));
            assertEquals(0, server.getRequestCount());
        } finally {
            LicenseRuntime.install(prior);
        }
    }

    @Test
    void deniedLicenseCanFinishAnAlreadySubmittedLabelJobWithoutRecreatingIt() throws Exception {
        var repository = new OzonLabelRepository();
        repository.findOrCreate(1, "posting-1");
        repository.update(1, "posting-1", "task-1", "POLLING", null, null);
        server.enqueue(json("{\"result\":{\"status\":\"completed\",\"file_url\":\""
                + server.url("/cdn/accepted.pdf") + "\"}}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("%PDF-1.7\nexisting-label"));
        var prior = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(temporaryDirectory.resolve("denied"))) {
            LicenseRuntime.install(denied);
            var target = temporaryDirectory.resolve("finished.pdf").toFile();
            new OzonLabelService(repository, this::client).downloadOfficialPdf(
                    new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret"), "posting-1", target);
            assertTrue(target.isFile());
            assertEquals("READY", repository.find(1, "posting-1").status());
            assertEquals(2, server.getRequestCount());
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().contains("/get"));
        } finally {
            LicenseRuntime.install(prior);
        }
    }

    @Test
    void ambiguousCreateWithoutTaskIdIsNeverSubmittedAgainBlindly() {
        OzonLabelRepository repository = new OzonLabelRepository();
        repository.findOrCreate(1, "posting-1");
        repository.update(1, "posting-1", null, "RECONCILE_REQUIRED", null, "transport");
        Shop shop = new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret");

        IOException failure = assertThrows(IOException.class, () -> new OzonLabelService()
                .downloadOfficialPdf(shop, "posting-1", temporaryDirectory.resolve("label.pdf").toFile()));

        assertEquals(
                "Ozon did not return a label task ID. VN code will not create another label job automatically.",
                failure.getMessage());
        assertEquals("RECONCILE_REQUIRED", repository.find(1, "posting-1").status());
    }

    @Test
    void downloadsOfficialBigLabelWithoutSendingApiCredentialToCdn() throws Exception {
        server.enqueue(json("{\"result\":{\"tasks\":["
                + "{\"task_id\":2002,\"task_type\":\"small_label\"},"
                + "{\"task_id\":2001,\"task_type\":\"big_label\"}]}}"));
        server.enqueue(json("{\"result\":{\"status\":\"completed\",\"file_url\":\""
                + server.url("/cdn/official.pdf") + "\"}}"));
        byte[] pdf = "%PDF-1.7\nofficial-ozon-label".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/pdf").setBody(new okio.Buffer().write(pdf)));
        Path target = temporaryDirectory.resolve("official-label.pdf");

        new OzonLabelService(new OzonLabelRepository(), this::client)
                .downloadOfficialPdf(
                        new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret"),
                        "posting-1",
                        target.toFile());

        assertTrue(java.util.Arrays.equals(pdf, java.nio.file.Files.readAllBytes(target)));
        RecordedRequest create = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest poll = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest download = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v3/posting/fbs/package-label/create", create.getPath());
        assertTrue(poll.getBody().readUtf8().contains("\"task_id\":2001"));
        assertEquals("/cdn/official.pdf", download.getPath());
        assertNull(download.getHeader("Api-Key"));
        OzonLabelRepository.LabelJob persisted = new OzonLabelRepository().find(1, "posting-1");
        assertEquals("v3s:2001", persisted.taskId());
        assertNull(persisted.outputPath());
    }

    @Test
    void acceptsCompatibleDirectTaskIdResponseForASingleV3Job() throws Exception {
        server.enqueue(json("{\"result\":{\"task_id\":3001}}"));
        server.enqueue(json("{\"result\":{\"status\":\"completed\",\"file_url\":\""
                + server.url("/cdn/direct-task.pdf") + "\"}}"));
        byte[] pdf = "%PDF-1.7\ndirect-task-label".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/pdf").setBody(new okio.Buffer().write(pdf)));

        new OzonLabelService(new OzonLabelRepository(), this::client)
                .downloadOfficialPdf(
                        new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret"),
                        "posting-1",
                        temporaryDirectory.resolve("direct-task.pdf").toFile());

        assertEquals("v3s:3001", new OzonLabelRepository().find(1, "posting-1").taskId());
    }

    @Test void completedSingleTasksAndNewOrdersAreRegroupedIntoBatchesOfAtMost1000()throws Exception {
        var numbers=java.util.stream.IntStream.range(0,1001).mapToObj(i->"91000000-"+String.format("%04d",i)+"-1").toList();
        try(var c=Database.getConnection()) {
            c.setAutoCommit(false);
            try(var posting=c.prepareStatement("INSERT INTO ozon_postings(shop_id,posting_number,status,synced_at) VALUES(1,?,'awaiting_deliver','2026-10-29T00:00:00Z')");
                var label=c.prepareStatement("INSERT INTO ozon_label_jobs(shop_id,posting_number,task_id,status,created_at,updated_at) VALUES(1,?,?,'READY','2026-10-29T00:00:00Z','2026-10-29T00:00:00Z')")) {
                for(int i=0;i<numbers.size();i++) {
                    posting.setString(1,numbers.get(i));posting.addBatch();
                    if(i<1000){label.setString(1,numbers.get(i));label.setString(2,"v3s:"+(8000+i));label.addBatch();}
                }
                posting.executeBatch();label.executeBatch();c.commit();
            }
        }
        var creates=new java.util.concurrent.atomic.AtomicInteger();var payloads=new java.util.concurrent.ConcurrentHashMap<String,byte[]>();
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public MockResponse dispatch(RecordedRequest request){
            try {
                String path=request.getPath();
                if(path.equals("/v3/posting/fbs/package-label/create")) {
                    var body=com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().getAsJsonArray("posting_numbers");
                    assertTrue(body.size()<=1000);String task=Integer.toString(7000+creates.incrementAndGet());
                    var reverse=new java.util.ArrayList<String>();for(var number:body)reverse.add(number.getAsString());java.util.Collections.reverse(reverse);
                    payloads.put(task,textPdf(reverse.toArray(String[]::new)));
                    return json("{\"tasks\":[{\"task_id\":"+task+",\"task_type\":\"big_label\"}]}");
                }
                if(path.equals("/v2/posting/fbs/package-label/get")) {
                    String task=com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().get("task_id").getAsString();
                    return json("{\"status\":{\"code\":\"completed\"},\"file_url\":\""+server.url("/cdn/"+task)+"\"}");
                }
                if(path.startsWith("/cdn/"))return new MockResponse().setBody(new okio.Buffer().write(payloads.get(path.substring(5))));
                return new MockResponse().setResponseCode(404);
            }catch(Exception failure){throw new AssertionError(failure);}
        }});
        try(var batch=batch(numbers)) {
            for(String number:numbers) {
                var page=batch.verifiedCarrierPage(owner(),number);assertNotNull(page);
                assertEquals(number,com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(page.source().getPage(page.page())).strip());
            }
        }
        assertEquals(2,creates.get());assertEquals(6,server.getRequestCount(),"1001 orders need two creates, two polls and two PDF downloads, including previously individual tasks");
        var saved=new OzonLabelRepository().findMany(1,numbers);
        for(int i=0;i<numbers.size();i++){assertEquals(i<1000?"v3:7001":"v3:7002",saved.get(numbers.get(i)).taskId());assertEquals("READY",saved.get(numbers.get(i)).status());}
    }

    @Test void regroupClaimRollsBackIfAnySingleTaskIsStillInProgress()throws Exception {
        var numbers=twoPostings();var repo=new OzonLabelRepository();
        for(int i=0;i<numbers.size();i++){repo.findOrCreate(1,numbers.get(i));repo.update(1,numbers.get(i),"v3s:"+(8000+i),i==0?"READY":"POLLING",null,null);}
        assertThrows(IllegalStateException.class,()->repo.claimBatch(1,numbers));
        assertEquals("READY",repo.find(1,numbers.getFirst()).status());assertEquals("v3s:8000",repo.find(1,numbers.getFirst()).taskId());
        assertEquals("POLLING",repo.find(1,numbers.getLast()).status());assertEquals("v3s:8001",repo.find(1,numbers.getLast()).taskId());
        assertEquals(0,server.getRequestCount());
    }

    @Test void regroupWithoutANewReceiptNeverCreatesASecondTaskOnRetry()throws Exception {
        var numbers=twoPostings();var repo=new OzonLabelRepository();
        for(int i=0;i<numbers.size();i++){repo.findOrCreate(1,numbers.get(i));repo.update(1,numbers.get(i),"v3s:"+(8000+i),"READY",null,null);}
        server.enqueue(json("{}"));
        for(int attempt=0;attempt<2;attempt++)try(var batch=batch(numbers)) {
            assertThrows(java.io.IOException.class,()->batch.verifiedCarrierPage(owner(),numbers.getFirst()));
        }
        for(String number:numbers){assertEquals("RECONCILE_REQUIRED",repo.find(1,number).status());assertNull(repo.find(1,number).taskId());}
        assertEquals(1,server.getRequestCount());
    }

    @Test void isolatedReadyLabelsKeepTheirSingleReceiptWhenPrintingTogether()throws Exception {
        var numbers=twoPostings();var repo=new OzonLabelRepository();
        for(int i=0;i<numbers.size();i++) {
            repo.findOrCreate(1,numbers.get(i));repo.update(1,numbers.get(i),"v3s:"+(8000+i),"READY",null,"BATCH_PAGE_IDENTITY");
            server.enqueue(json("{\"status\":{\"code\":\"completed\"},\"file_url\":\""+server.url("/cdn/isolated-"+i)+"\"}"));
            server.enqueue(new MockResponse().setBody(new okio.Buffer().write(textPdf(numbers.get(i)))));
        }
        try(var batch=batch(numbers)) {
            for(int i=0;i<numbers.size();i++)assertEquals(numbers.get(i),pageText(batch.download(owner(),numbers.get(i),temporaryDirectory.resolve("kept-"+i+".pdf").toFile())));
        }
        assertEquals(4,server.getRequestCount());
        for(int i=0;i<numbers.size();i++)assertEquals("v3s:"+(8000+i),repo.find(1,numbers.get(i)).taskId());
    }

    @Test void reprintingExistingSinglesDoesNotRequirePermissionForANewBatchTask()throws Exception {
        var numbers=twoPostings();var repo=new OzonLabelRepository();
        for(int i=0;i<numbers.size();i++) {
            repo.findOrCreate(1,numbers.get(i));repo.update(1,numbers.get(i),"v3s:"+(8000+i),"READY",null,null);
            server.enqueue(json("{\"status\":{\"code\":\"completed\"},\"file_url\":\""+server.url("/cdn/saved-"+i)+"\"}"));
            server.enqueue(new MockResponse().setBody(new okio.Buffer().write(textPdf(numbers.get(i)))));
        }
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temporaryDirectory.resolve("reprint-only"))) {
            LicenseRuntime.install(denied);
            try(var batch=batch(numbers)) {
                for(int i=0;i<numbers.size();i++)assertEquals(numbers.get(i),pageText(batch.download(owner(),numbers.get(i),temporaryDirectory.resolve("saved-"+i+".pdf").toFile())));
            }
        }finally{LicenseRuntime.install(prior);}
        assertEquals(4,server.getRequestCount());
        for(int i=0;i<numbers.size();i++)assertEquals("v3s:"+(8000+i),repo.find(1,numbers.get(i)).taskId());
    }

    @Test void oneThousandLabelsUseOneTaskAndKeepIdentityWhenPdfPagesAreReversed()throws Exception {
        var numbers=java.util.stream.IntStream.range(0,1000).mapToObj(i->"90000000-"+String.format("%04d",i)+"-1").toList();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_postings(shop_id,posting_number,status,synced_at) VALUES(1,?,'awaiting_deliver','2026-08-18T00:00:00Z')")) {
            for(String number:numbers){p.setString(1,number);p.addBatch();}p.executeBatch();
        }
        var bytes=new java.io.ByteArrayOutputStream();
        try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfWriter(bytes))) {
            var font=com.itextpdf.kernel.font.PdfFontFactory.createFont();
            for(int i=numbers.size()-1;i>=0;i--)new com.itextpdf.kernel.pdf.canvas.PdfCanvas(pdf.addNewPage()).beginText().setFontAndSize(font,12).moveText(20,20).showText(numbers.get(i)).endText();
        }
        server.enqueue(json("{\"tasks\":[{\"task_id\":7001,\"task_type\":\"big_label\"}]}"));
        server.enqueue(json("{\"status\":{\"code\":\"completed\",\"postings_count\":1000,\"printed_postings_count\":1000},\"file_url\":\""+server.url("/cdn/batch.pdf")+"\"}"));
        server.enqueue(new MockResponse().setBody(new okio.Buffer().write(bytes.toByteArray())));
        var shop=new Shop(1,"Ozon",Marketplace.OZON,"client-1","secret");
        long start=System.nanoTime();
        try(var batch=new OzonBatchLabelDownloader(new OzonLabelService(new OzonLabelRepository(),this::client),shop,numbers)) {
            for(int i:new int[]{0,499,999}) {
                var target=temporaryDirectory.resolve("label-"+i+".pdf").toFile();batch.download(shop,numbers.get(i),target);
                try(var actual=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(target))) {
                    assertEquals(1,actual.getNumberOfPages());assertEquals(numbers.get(i),com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(actual.getPage(1)).strip());
                }
            }
        }
        try(var batch=new OzonBatchLabelDownloader(new OzonLabelService(new OzonLabelRepository(),this::client),shop,numbers)) {
            server.enqueue(json("{\"status\":{\"code\":\"completed\"},\"file_url\":\""+server.url("/cdn/batch.pdf")+"\"}"));
            server.enqueue(new MockResponse().setBody(new okio.Buffer().write(bytes.toByteArray())));
            var streamed=temporaryDirectory.resolve("verified-shared-pages.pdf");
            try(var output=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfWriter(streamed.toFile()))){
                for(int i=0;i<numbers.size();i++){
                    var page=batch.verifiedCarrierPage(shop,numbers.get(i));assertNotNull(page);
                    assertEquals(1000-i,page.page(),"Use verified identity rather than the request/page order");
                    page.source().copyPagesTo(page.page(),page.page(),output);
                }
            }
            try(var output=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(streamed.toFile()))){
                assertEquals(1000,output.getNumberOfPages());
                for(int i:new int[]{0,499,999})assertEquals(numbers.get(i),com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(output.getPage(i+1)).strip());
            }
        }
        assertEquals(5,server.getRequestCount(),"One task, two read/download passes, no per-posting downloads");
        var create=server.takeRequest(1,TimeUnit.SECONDS);assertEquals("/v3/posting/fbs/package-label/create",create.getPath());
        assertEquals(1000,com.google.gson.JsonParser.parseString(create.getBody().readUtf8()).getAsJsonObject().getAsJsonArray("posting_numbers").size());
        assertEquals("/v2/posting/fbs/package-label/get",server.takeRequest(1,TimeUnit.SECONDS).getPath());
        assertEquals("v3:7001",new OzonLabelRepository().find(1,numbers.getLast()).taskId());
        assertEquals("/cdn/batch.pdf",server.takeRequest(1,TimeUnit.SECONDS).getPath());
        assertEquals("/v2/posting/fbs/package-label/get",server.takeRequest(1,TimeUnit.SECONDS).getPath());
        assertEquals("/cdn/batch.pdf",server.takeRequest(1,TimeUnit.SECONDS).getPath());
        server.enqueue(json("{\"status\":{\"code\":\"completed\"},\"file_url\":\""+server.url("/cdn/batch.pdf")+"\"}"));
        server.enqueue(new MockResponse().setBody(new okio.Buffer().write(bytes.toByteArray())));
        var single=temporaryDirectory.resolve("reprint-one.pdf").toFile();
        new OzonLabelService(new OzonLabelRepository(),this::client).downloadOfficialPdf(shop,numbers.get(499),single);
        try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(single))) {
            assertEquals(1,pdf.getNumberOfPages(),"A single reprint from a shared task must not print all 1000 labels");
            assertEquals(numbers.get(499),com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(1)).strip());
        }
        assertEquals(7,server.getRequestCount(),"A reprint uses the saved task without another create");
        java.nio.file.Files.writeString(temporaryDirectory.resolve("batch-timing.txt"),"1000-label mock task and identity index: "+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)+" ms");
    }
    @Test void ambiguousBulkTaskIsNotCreatedAgainAfterRestart()throws Exception {
        var shop=new Shop(1,"Ozon",Marketplace.OZON,"client-1","secret");
        server.enqueue(json("{}"));
        var first=new OzonLabelService(new OzonLabelRepository(),this::client);
        assertThrows(IOException.class,()->first.downloadOfficialBatchPdf(shop,java.util.List.of("posting-1"),temporaryDirectory.resolve("first.pdf").toFile()));
        var restarted=new OzonLabelService(new OzonLabelRepository(),this::client);
        assertThrows(IOException.class,()->restarted.downloadOfficialBatchPdf(shop,java.util.List.of("posting-1"),temporaryDirectory.resolve("second.pdf").toFile()));
        assertEquals(1,server.getRequestCount());assertEquals("RECONCILE_REQUIRED",new OzonLabelRepository().find(1,"posting-1").status());
    }

    @Test void sharedPagesMatchShippingBarcodesAndNotTheirReversedPosition()throws Exception{
        var numbers=twoPostings();transportBarcodes(numbers.get(0),"ROUTE10001");transportBarcodes(numbers.get(1),"ROUTE10002");
        enqueueBatch(textPdf("ROUTE10002","ROUTE10001"));
        try(var batch=batch(numbers)){
            assertEquals("ROUTE10001",pageText(batch.download(owner(),numbers.get(0),temporaryDirectory.resolve("first.pdf").toFile())));
            assertEquals("ROUTE10002",pageText(batch.download(owner(),numbers.get(1),temporaryDirectory.resolve("second.pdf").toFile())));
        }assertEquals(3,server.getRequestCount());
    }

    @Test void barcodeOnlySharedPagesDecodeTheirTransportIdentity()throws Exception{
        var numbers=twoPostings();transportBarcodes(numbers.get(0),"ROUTE10001");transportBarcodes(numbers.get(1),"ROUTE10002");
        var bytes=new java.io.ByteArrayOutputStream();
        try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfWriter(bytes))){
            for(String value:java.util.List.of("ROUTE10002","ROUTE10001")){
                var page=pdf.addNewPage(new com.itextpdf.kernel.geom.PageSize(230,150));
                var barcode=new com.itextpdf.barcodes.Barcode128(pdf);barcode.setCode(value);barcode.setFont(null);barcode.setBarHeight(50);barcode.setX(1);
                new com.itextpdf.kernel.pdf.canvas.PdfCanvas(page).addXObjectAt(barcode.createFormXObject(pdf),12,30);
            }
        }
        enqueueBatch(bytes.toByteArray());
        try(var batch=batch(numbers)){
            var target=batch.download(owner(),numbers.get(0),temporaryDirectory.resolve("barcode.pdf").toFile());
            try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(target))){assertEquals(1,pdf.getNumberOfPages());}
            try(var pdf=org.apache.pdfbox.Loader.loadPDF(target)){
                var image=new org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(0,203);
                try{
                    var bitmap=new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.client.j2se.BufferedImageLuminanceSource(image)));
                    assertEquals("ROUTE10001",new com.google.zxing.oned.Code128Reader().decode(bitmap,java.util.Map.of(com.google.zxing.DecodeHintType.TRY_HARDER,true)).getText());
                }
                finally{image.flush();}
            }
        }assertEquals(3,server.getRequestCount(),"Barcode identity keeps the shared task fast path");
    }

    @Test void postingTextAcceptsPrintedDashVariantsAndSpacesWithoutMixingPostings()throws Exception{
        var numbers=twoPostings();enqueueBatch(textPdf("90000000 \u2013 0002 \u2013 1","90000000 - 0001 - 1"));
        try(var batch=batch(numbers)){
            assertTrue(pageText(batch.download(owner(),numbers.get(0),temporaryDirectory.resolve("normalized.pdf").toFile())).contains("0001"));
        }assertEquals(3,server.getRequestCount());
    }

    @Test void unidentifiedSharedPagesUseExactSinglePostingTasksAndReprintTheirSavedReceipts()throws Exception{
        var numbers=twoPostings();enqueueBatch(textPdf("PRODUCT-SHARED","PRODUCT-SHARED"));
        enqueueSingle("8001",textPdf("FIRST-ONLY","FIRST-PRODUCT"));enqueueSingle("8002",textPdf("SECOND-ONLY","SECOND-PRODUCT"));
        try(var batch=batch(numbers)){
            assertTrue(pageText(batch.download(owner(),numbers.get(0),temporaryDirectory.resolve("isolated-first.pdf").toFile())).startsWith("FIRST-ONLY"));
            assertTrue(pageText(batch.download(owner(),numbers.get(1),temporaryDirectory.resolve("isolated-second.pdf").toFile())).startsWith("SECOND-ONLY"));
        }
        for(int i=0;i<3;i++)server.takeRequest(1,TimeUnit.SECONDS);
        for(int i=0;i<2;i++){
            var request=server.takeRequest(1,TimeUnit.SECONDS);assertEquals("/v3/posting/fbs/package-label/create",request.getPath());
            var body=com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().getAsJsonArray("posting_numbers");assertEquals(1,body.size());assertEquals(numbers.get(i),body.get(0).getAsString());
            server.takeRequest(1,TimeUnit.SECONDS);assertNull(server.takeRequest(1,TimeUnit.SECONDS).getHeader("Api-Key"));
        }
        assertEquals("v3s:8001",new OzonLabelRepository().find(1,numbers.get(0)).taskId());
        for(String number:numbers) {
            var pinned=new OzonLabelRepository().find(1,number);
            assertEquals("BATCH_PAGE_IDENTITY",pinned.safeErrorCode());
            assertTrue(!OzonLabelService.canRegroup(pinned),"A label isolated for missing identity must remain isolated after successful download");
        }
        server.enqueue(json("{\"result\":{\"status\":\"completed\",\"file_url\":\""+server.url("/cdn/reprint.pdf")+"\"}}"));
        server.enqueue(new MockResponse().setBody(new okio.Buffer().write(textPdf("FIRST-ONLY"))));
        new OzonLabelService(new OzonLabelRepository(),this::client).downloadOfficialPdf(owner(),numbers.get(0),temporaryDirectory.resolve("reprint-isolated.pdf").toFile());
        assertEquals(11,server.getRequestCount(),"Reprinting the isolated task never creates another task");
    }

    @Test void conflictingPageIdentitiesUseIsolatedLabelsInsteadOfChoosingTheFirstMatch()throws Exception{
        var numbers=twoPostings();enqueueBatch(textPdf(String.join(" ",numbers)));
        enqueueSingle("8001",textPdf("FIRST-ONLY"));
        try(var batch=batch(numbers)){assertEquals("FIRST-ONLY",pageText(batch.download(owner(),numbers.get(0),temporaryDirectory.resolve("conflict.pdf").toFile())));}
        assertEquals(6,server.getRequestCount());
    }

    @Test void aLostSingleTaskReceiptIsNotRecreatedOrClearedByTheSharedTask()throws Exception{
        var numbers=twoPostings();enqueueBatch(textPdf("UNREADABLE-IDENTITY"));server.enqueue(json("{}"));
        try(var batch=batch(numbers)){assertEquals("OZON_LABEL_TASK_UNKNOWN",assertThrows(IOException.class,()->batch.download(owner(),numbers.get(0),temporaryDirectory.resolve("lost.pdf").toFile())).getMessage());}
        var repo=new OzonLabelRepository();assertEquals("v3:7001",repo.find(1,numbers.get(0)).taskId());assertEquals("ISOLATING",repo.find(1,numbers.get(0)).status());
        try(var batch=batch(numbers)){assertThrows(IOException.class,()->batch.download(owner(),numbers.get(0),temporaryDirectory.resolve("lost-retry.pdf").toFile()));}
        assertThrows(IllegalStateException.class,()->repo.updateBatch(1,java.util.List.of(numbers.get(0)),"v3:7001","READY",null));
        assertEquals("ISOLATING",repo.find(1,numbers.get(0)).status());assertEquals(4,server.getRequestCount());
    }

    @Test void anExplicitlyFailedSingleTaskCanBeRegeneratedOnTheNextPrintAttempt()throws Exception{
        server.enqueue(json("{\"tasks\":[{\"task_id\":9101,\"task_type\":\"big_label\"}]}"));
        server.enqueue(json("{\"status\":{\"code\":\"error\"},\"error\":{\"code\":\"LABEL_NOT_READY\"}}"));
        var repository=new OzonLabelRepository();var service=new OzonLabelService(repository,this::client,duration->{});var file=temporaryDirectory.resolve("retry-failed.pdf").toFile();
        assertThrows(IOException.class,()->service.downloadOfficialPdf(owner(),"posting-1",file));assertEquals("FAILED",repository.find(1,"posting-1").status());
        enqueueSingle("9102",textPdf("REGENERATED"));service.downloadOfficialPdf(owner(),"posting-1",file);
        assertEquals("REGENERATED",pageText(file));assertEquals("v3s:9102",repository.find(1,"posting-1").taskId());assertEquals(5,server.getRequestCount());
    }

    @Test void aLostRetryReceiptKeepsTheFailedSingleTaskAndCannotStartAnotherCreate()throws Exception{
        var repository=new OzonLabelRepository();repository.findOrCreate(1,"posting-1");repository.update(1,"posting-1","v3s:9201","FAILED",null,"label_failed");
        server.enqueue(json("{}"));var service=new OzonLabelService(repository,this::client);var file=temporaryDirectory.resolve("unknown-retry.pdf").toFile();
        assertEquals("OZON_LABEL_TASK_UNKNOWN",assertThrows(IOException.class,()->service.downloadOfficialPdf(owner(),"posting-1",file)).getMessage());
        assertEquals("v3s:9201",repository.find(1,"posting-1").taskId());assertEquals("ISOLATING",repository.find(1,"posting-1").status());
        assertThrows(IOException.class,()->service.downloadOfficialPdf(owner(),"posting-1",file));assertEquals(1,server.getRequestCount());
    }

    @Test void aLocalPublishFailureResumesTheExistingSuccessfulRemoteTask()throws Exception{
        var repository=new OzonLabelRepository();repository.findOrCreate(1,"posting-1");repository.update(1,"posting-1","v3s:9301","FAILED",null,"publish_failed");
        server.enqueue(json("{\"status\":{\"code\":\"completed\"},\"file_url\":\""+server.url("/cdn/local-retry.pdf")+"\"}"));
        server.enqueue(new MockResponse().setBody(new okio.Buffer().write(textPdf("EXISTING-TASK"))));
        var file=temporaryDirectory.resolve("local-retry.pdf").toFile();new OzonLabelService(repository,this::client).downloadOfficialPdf(owner(),"posting-1",file);
        assertEquals("EXISTING-TASK",pageText(file));assertEquals("v3s:9301",repository.find(1,"posting-1").taskId());assertEquals(2,server.getRequestCount());
        assertEquals("/v2/posting/fbs/package-label/get",server.takeRequest(1,TimeUnit.SECONDS).getPath());
    }

    @Test void explicitRejectionReleasesOnlyTheUnsentIsolationForAControlledRetry()throws Exception{
        var numbers=twoPostings();enqueueBatch(textPdf("UNREADABLE-IDENTITY"));server.enqueue(new MockResponse().setResponseCode(400).setBody("{}"));
        try(var batch=batch(numbers)){assertThrows(OzonApiException.class,()->batch.download(owner(),numbers.get(0),temporaryDirectory.resolve("rejected.pdf").toFile()));}
        assertEquals("ISOLATE_REQUIRED",new OzonLabelRepository().find(1,numbers.get(0)).status());enqueueSingle("8001",textPdf("FIRST-ONLY"));
        try(var batch=batch(numbers)){assertEquals("FIRST-ONLY",pageText(batch.download(owner(),numbers.get(0),temporaryDirectory.resolve("retry.pdf").toFile())));}
        assertEquals(7,server.getRequestCount(),"Retry preserves the original batch and sends one single-posting task");
    }

    private Shop owner(){return new Shop(1,"Ozon",Marketplace.OZON,"client-1","secret");}
    private java.util.List<String> twoPostings()throws Exception{
        var result=java.util.List.of("90000000-0001-1","90000000-0002-1");
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_postings(shop_id,posting_number,status,synced_at) VALUES(1,?,'awaiting_deliver','2026-08-18T00:00:00Z')")){
            for(String number:result){p.setString(1,number);p.addBatch();}p.executeBatch();
        }return result;
    }
    private void transportBarcodes(String number,String barcode)throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_postings SET upper_barcode=? WHERE shop_id=1 AND posting_number=?")){p.setString(1,barcode);p.setString(2,number);p.executeUpdate();}
    }
    private OzonBatchLabelDownloader batch(java.util.List<String> numbers){return new OzonBatchLabelDownloader(new OzonLabelService(new OzonLabelRepository(),this::client),owner(),numbers);}
    private void enqueueBatch(byte[] bytes){
        server.enqueue(json("{\"tasks\":[{\"task_id\":7001,\"task_type\":\"big_label\"}]}"));
        server.enqueue(json("{\"status\":{\"code\":\"completed\"},\"file_url\":\""+server.url("/cdn/batch.pdf")+"\"}"));server.enqueue(new MockResponse().setBody(new okio.Buffer().write(bytes)));
    }
    private void enqueueSingle(String task,byte[] bytes){
        server.enqueue(json("{\"result\":{\"task_id\":\""+task+"\"}}"));server.enqueue(json("{\"result\":{\"status\":\"completed\",\"file_url\":\""+server.url("/cdn/"+task+".pdf")+"\"}}"));server.enqueue(new MockResponse().setBody(new okio.Buffer().write(bytes)));
    }
    private byte[] textPdf(String... texts)throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfWriter(bytes))){
            var font=com.itextpdf.kernel.font.PdfFontFactory.createFont();for(String text:texts)new com.itextpdf.kernel.pdf.canvas.PdfCanvas(pdf.addNewPage()).beginText().setFontAndSize(font,12).moveText(20,20).showText(text).endText();
        }return bytes.toByteArray();
    }
    private String pageText(java.io.File file)throws Exception{
        try(var pdf=new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfReader(file))){return com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(1)).strip();}
    }

    private OzonApiClient client(int shopId, OzonCredentials credentials) {
        return new OzonApiClient(
                shopId,
                credentials,
                server.url("/"),
                new OkHttpClient.Builder()
                        .connectTimeout(200, TimeUnit.MILLISECONDS)
                        .readTimeout(200, TimeUnit.MILLISECONDS)
                        .writeTimeout(200, TimeUnit.MILLISECONDS)
                        .callTimeout(500, TimeUnit.MILLISECONDS)
                        .build(),
                new OzonApiRateLimiter(Duration.ZERO),
                new OzonRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body);
    }
}
