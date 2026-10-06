package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.signature.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ZnackModuleTest {
    @TempDir Path temp;

    @BeforeEach void init() {
        System.setProperty("wcode.appdata.dir", temp.toString());
        Database.initDatabase();
        try (Connection c=Database.getConnection(); Statement st=c.createStatement()) {
            st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shop A','a')");
            st.execute("INSERT INTO shops(id,name,api_key) VALUES(2,'Shop B','b')");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach void clear() { System.clearProperty("wcode.appdata.dir"); }

    @Test void schemaAndSettingsDoNotPersistTokensOrSignatures() throws Exception {
        ZnackRepository repository = repository(1, "Shop A");
        repository.saveSettings(new Settings("true","suz","oms","conn","p","producer","owner","signer","cert",
                "[\"{input}\",\"{output}\"]","n","d",temp.toString(),true));
        assertEquals("oms", repository.getSettings().omsId());
        try (Connection c=Database.getConnection(); Statement st=c.createStatement(); ResultSet rs=st.executeQuery("PRAGMA table_info(znack_settings)")) {
            while(rs.next()) {
                String name=rs.getString("name").toLowerCase();
                assertFalse(name.contains("token") || name.contains("signature"));
            }
        }
    }

    @Test void errorDisplayExtractsHumanMessageFromApiJsonPayloads() {
        String legacySuzError = "Znack API request failed (HTTP 400): {\"error_message\":\"Ошибка аутентификации СУЗ: Сервис вернул пустой ответ\"}";
        String uot1090 = "HTTP 400: 1090; Проверка учетных данных УОТ не пройдена";

        assertTrue(ZnackErrorMessages.isSuzAuthError(legacySuzError));
        assertTrue(ZnackErrorMessages.isSuzAuthError(uot1090));
        assertTrue(ZnackErrorMessages.isSuzAuthError(
                "Znack API request failed (HTTP 400): {\"globalErrors\":[{\"errorCode\":1090,"
                        + "\"error\":\"Проверка учетных данных УОТ не пройдена\"}]}"));
        assertFalse(ZnackErrorMessages.isSuzAuthError("HTTP 400: 10901; unrelated"));
        assertEquals(com.tuandev.fbsbarcode.shared.I18nService.getInstance().tr("znack.error.suz_auth_invalid"),
                ZnackErrorMessages.display(legacySuzError));
        assertEquals("", ZnackErrorMessages.displayForPipeline("FAILED", uot1090));
        assertEquals("HTTP 422: Подпись не соответствует данным документа",
                ZnackErrorMessages.display(
                        "Znack API request failed (HTTP 422): {\"fieldErrors\":[{\"fieldName\":\"signature\",\"errors\":[\"Подпись не соответствует данным документа\"]}]}"));
        assertEquals("HTTP 400: GTIN không hợp lệ",
                ZnackErrorMessages.display(
                        "Znack API request failed (HTTP 400): {\"fieldErrors\":{\"gtin\":\"GTIN không hợp lệ\"}}"));
        assertEquals("Znack API request failed (HTTP 500)",
                ZnackErrorMessages.display("Znack API request failed (HTTP 500): {}"));
        assertEquals("Missing TN VED.", ZnackErrorMessages.display("Missing TN VED."));
        assertEquals("broken {not json", ZnackErrorMessages.display("broken {not json"));
        assertEquals("", ZnackErrorMessages.display(null));
    }

    @Test void waitingIntroductionReadinessHidesExpectedPollingDetailsButKeepsRealErrors() {
        String pollingDetail = "True API readiness: 0/100 KIZ ready, 100 pending. "
                + "KIZ is not APPLIED yet: EMITTED";
        String missingDocuments = "GTIN 04627877922387 has no active National Catalog "
                + "declaration or certificate. Publish or reactivate the GTIN document; "
                + "the introduction will retry automatically.";

        assertEquals("", ZnackErrorMessages.displayForPipeline(
                "WAITING_INTRODUCTION_READINESS", pollingDetail));
        assertEquals("", ZnackErrorMessages.displayForPipeline(
                "WAITING_INTRODUCTION_DOCUMENTS", missingDocuments));
        assertEquals("", ZnackErrorMessages.displayForPipeline(
                "INTRODUCTION_SKIPPED_MISSING_DOCUMENTS", missingDocuments));
        assertEquals("", ZnackErrorMessages.displayForPipeline(
                "WAITING_INTRODUCTION_READINESS", missingDocuments));
        assertEquals("temporary readiness failure", ZnackErrorMessages.displayForPipeline(
                "WAITING_INTRODUCTION_READINESS", "temporary readiness failure"));
        assertEquals(pollingDetail, ZnackErrorMessages.displayForPipeline("FAILED", pollingDetail));
        assertEquals(missingDocuments, ZnackErrorMessages.displayForPipeline(
                "FAILED", missingDocuments));
    }

    @Test void hidesTransientDocumentNotFoundOnlyWhilePollingIntroduction() {
        String direct = "HTTP 404: Документ не найден в ГИС МТ";
        String wrapped = "Znack API request failed (HTTP 404): "
                + "{\"error_message\":\"Документ не найден в ГИС МТ\"}";

        assertTrue(ZnackErrorMessages.isDocumentVisibilityDelay("POLLING_INTRODUCTION", direct));
        assertTrue(ZnackErrorMessages.isDocumentVisibilityDelay("polling_introduction", wrapped));
        assertEquals("", ZnackErrorMessages.displayForPipeline("POLLING_INTRODUCTION", direct));
        assertEquals("", ZnackErrorMessages.displayForPipeline("POLLING_INTRODUCTION", wrapped));
        assertFalse(ZnackErrorMessages.isDocumentVisibilityDelay("FAILED", direct));
        assertEquals(direct, ZnackErrorMessages.displayForPipeline("FAILED", direct));
        assertEquals("HTTP 404: another resource was not found",
                ZnackErrorMessages.displayForPipeline(
                        "POLLING_INTRODUCTION", "HTTP 404: another resource was not found"));
    }

    @Test void identifiesInsufficientFundsOnlyFromZnackError3590OrItsExceptionType() {
        String exactError = "HTTP 400: 3590; \"NotEnoughMoneyException: contract_id=[1234567]: "
                + "Недостаточно средств на лицевом счету\"";

        assertTrue(ZnackErrorMessages.isInsufficientFunds(exactError));
        assertTrue(ZnackErrorMessages.isInsufficientFunds(
                "Znack API request failed: NotEnoughMoneyException: balance is too low"));
        assertFalse(ZnackErrorMessages.isInsufficientFunds("HTTP 400: 35901; unrelated"));
        assertFalse(ZnackErrorMessages.isInsufficientFunds("HTTP 400: 3055; emission is blocked"));
        assertFalse(ZnackErrorMessages.isInsufficientFunds(null));
    }

    @Test void identifiesEmissionTypeBlockedError3055WithoutMatchingSimilarCodes() {
        String exactError = "HTTP 400: 3055; Значение параметра \"Способ выпуска товаров в оборот\" "
                + "\"Произведен в РФ\" не соответствует допустимому для участника по причине "
                + "блокировки данного типа эмиссии";

        assertTrue(ZnackErrorMessages.requiresOperatorTermsSignature(exactError));
        assertTrue(ZnackErrorMessages.requiresOperatorTermsSignature(
                "Znack API request failed (HTTP 400): {\"errorCode\":3055}"));
        assertTrue(ZnackErrorMessages.requiresOperatorTermsSignature(
                "{\"error_code\":\"3055\",\"message\":\"blocked\"}"));
        assertFalse(ZnackErrorMessages.requiresOperatorTermsSignature("HTTP 400: 30550; unrelated"));
        assertFalse(ZnackErrorMessages.requiresOperatorTermsSignature("HTTP 400: 13055; unrelated"));
        assertFalse(ZnackErrorMessages.requiresOperatorTermsSignature(null));
    }

    @Test void pendingSuzBuffer3390IsAnExpectedHiddenWait() {
        String pending = "Znack API request failed (HTTP 400): {\"globalErrors\":[{\"errorCode\":3390,"
                + "\"error\":\"Буфер не активный. Текущий статус буфера: \\\"PENDING\\\"\"}]}";

        assertTrue(ZnackErrorMessages.isPendingKizBuffer(pending));
        assertEquals("", ZnackErrorMessages.displayForPipeline("DOWNLOADING_CODES", pending));
        assertFalse(ZnackErrorMessages.isPendingKizBuffer(
                "HTTP 400: {\"errorCode\":3390,\"error\":\"Buffer REJECTED\"}"));
        assertFalse(ZnackErrorMessages.isPendingKizBuffer("HTTP 400: 33901; Buffer PENDING"));
    }

    @Test void closedSuzOrder2090IsAnInternalHiddenReconciliationState() {
        String direct = "HTTP 400: 2090; Невозможно получить коды для заказа: \"order-id\". "
                + "Неверный статус заказа: \"CLOSED\"";
        String wrapped = "Znack API request failed (HTTP 400): {\"globalErrors\":[{\"errorCode\":2090,"
                + "\"error\":\"Неверный статус заказа: CLOSED\"}]}";

        assertTrue(ZnackErrorMessages.isClosedKizOrder(direct));
        assertTrue(ZnackErrorMessages.isClosedKizOrder(wrapped));
        assertEquals("", ZnackErrorMessages.displayForPipeline("DOWNLOADING_CODES", direct));
        assertFalse(ZnackErrorMessages.isClosedKizOrder("HTTP 400: 2090; order status ACTIVE"));
        assertFalse(ZnackErrorMessages.isClosedKizOrder("HTTP 400: 20901; order status CLOSED"));
    }

    @Test void sanitizerRedactsJsonAndHeaderStyleSecrets() {
        String sanitized=ZnackSanitizer.message("""
                {"token":"secret-token","signature":"secret-signature","pin":"1234"}
                clientToken=secret-client-token Authorization: Bearer secret-bearer
                """);

        assertFalse(sanitized.contains("secret-token"));
        assertFalse(sanitized.contains("secret-signature"));
        assertFalse(sanitized.contains("secret-client-token"));
        assertFalse(sanitized.contains("secret-bearer"));
        assertFalse(sanitized.contains("1234"));
    }

    @Test void diagnosticSanitizerKeepsFullErrorButStillRedactsSecrets() {
        String detail="Ошибка проверки документа. ".repeat(80);
        String raw=detail+" token=secret-token";

        String diagnostic=ZnackSanitizer.diagnostic(raw);

        assertTrue(diagnostic.length()>1000);
        assertTrue(diagnostic.startsWith(detail));
        assertFalse(diagnostic.contains("secret-token"));
    }

    @Test void preservesRawCodesAndIgnoresDuplicateDownloads() {
        ZnackRepository repository=repository(1, "Shop A");
        String raw="010460123456789021abc\u001d91secret";
        repository.upsertProducts(List.of(new Product("04601234567890","Product",null,null,null,null,null)));
        long order=repository.createDraft("04601234567890",2);
        assertEquals(1,repository.insertCodes(order,"04601234567890",new DownloadedCodes(List.of(raw,raw),"block")));
        KizCode code=repository.findCodes(order).getFirst();
        assertEquals(raw,code.rawCode());
        assertTrue(code.displayCode().contains("<GS>"));
    }

    @Test void repositoriesIsolateProductsOrdersAndLogsButRejectGloballyDuplicateCodes() {
        ZnackRepository a=repository(1,"Shop A"), b=repository(2,"Shop B");
        Product product=new Product("04601234567890","Product",null,null,null,null,null);
        a.upsertProducts(List.of(product)); b.upsertProducts(List.of(product));
        long aOrder=a.createDraft(product.gtin(),1), bOrder=b.createDraft(product.gtin(),1);
        String raw="010460123456789021same";
        assertEquals(1,a.insertCodes(aOrder,product.gtin(),new DownloadedCodes(List.of(raw),"a")));
        assertEquals(0,b.insertCodes(bOrder,product.gtin(),new DownloadedCodes(List.of(raw),"b")));
        a.log("TEST","a","INFO","A",200); b.log("TEST","b","INFO","B",201);
        assertTrue(a.findOrder(bOrder).isEmpty());
        assertEquals("a",a.findCodes(aOrder).getFirst().blockId());
        assertTrue(b.findCodes(bOrder).isEmpty());
        assertEquals("Shop A",a.findLogs().getFirst().shopName());
        assertEquals("Shop B",b.findLogs().getFirst().shopName());
    }

    @Test void permitDocumentsAreStoredAsACompleteShopScopedGtinSnapshot() {
        ZnackRepository a=repository(1,"Shop A"), b=repository(2,"Shop B");
        Product product=new Product("04601234567890","Product",null,null,null,null,null);
        a.upsertProducts(List.of(product));
        b.upsertProducts(List.of(product));
        List<GoodsDocument> documents=List.of(
                new GoodsDocument("CONFORMITY_DECLARATION","DECLARATION-1","2026-01-10"),
                new GoodsDocument("CONFORMITY_CERTIFICATE","CERTIFICATE-2","2026-02-11"));

        a.updateProductDocuments(product.gtin(),documents);

        assertEquals(documents,a.findProduct(product.gtin()).orElseThrow().permitDocuments());
        assertTrue(b.findProduct(product.gtin()).orElseThrow().permitDocuments().isEmpty());
        assertEquals("DECLARATION-1",a.findProduct(product.gtin()).orElseThrow().certificateNumber());

        a.updateProductDocuments(product.gtin(),List.of());
        assertTrue(a.findProduct(product.gtin()).orElseThrow().permitDocuments().isEmpty());
        assertNull(a.findProduct(product.gtin()).orElseThrow().certificateNumber());
    }

    @Test void signatureSelectionAndVerifiedStateAreShopSpecific() {
        ZnackRepository a=repository(1,"Shop A"), b=repository(2,"Shop B");
        Settings verifiedA=testedSettings("","","","connection","");
        a.saveSettings(verifiedA);
        b.saveSettings(new Settings("","","","connection","","","","signer","shop-b-cert","[]","","","",false));
        assertEquals("certificate",a.getSettings().signerCertificate());
        assertNotNull(a.getSettings().signerTestedAt());
        assertEquals("shop-b-cert",b.getSettings().signerCertificate());
        assertNull(b.getSettings().signerTestedAt());
    }

    @Test void basicSettingsContainsOnlyRealWorkflowFields() throws Exception {
        String fxml=Files.readString(Path.of("src/main/resources/com/tuandev/fbsbarcode/ui/znack/znack-automation-view.fxml"));
        assertTrue(fxml.contains("signatureSummaryLabel"));
        assertTrue(fxml.contains("signatureCertificateCombo"));
        assertFalse(fxml.contains("refreshCertificatesButton"));
        assertTrue(fxml.contains("testSignatureButton"));
        assertTrue(fxml.contains("omsConnectionField"));
        assertFalse(fxml.contains("documentNumberField"));
        assertFalse(fxml.contains("documentIssueDateField"));
        assertTrue(fxml.contains("autoIntroductionCheck"));
        assertFalse(fxml.contains("documentTypeCombo"));
        assertFalse(fxml.contains("documentExpiryDateField"));
        assertFalse(fxml.contains("advancedSettingsPane"));
        assertFalse(fxml.contains("pdfFolderField"));
        assertFalse(fxml.contains("trueApiUrlField"));
        assertFalse(fxml.contains("suzUrlField"));
        assertTrue(fxml.contains("omsIdField"));
        assertTrue(fxml.contains("omsIdHelpButton"));
        assertTrue(fxml.contains("omsConnectionHelpButton"));
        assertTrue(fxml.contains("omsHelpPane"));
        assertFalse(fxml.contains("buyButton"));
        assertFalse(fxml.contains("downloadButton"));
        assertFalse(fxml.contains("pdfButton"));
        assertFalse(fxml.contains("introduceButton"));
        assertFalse(fxml.contains("confirmButton"));
        assertFalse(fxml.contains("manualCertificateSelectorField"));
        assertFalse(fxml.contains("certmgrPathField"));
        String mapping=Files.readString(Path.of("src/main/resources/com/tuandev/fbsbarcode/ui/kizmapping/kiz-mapping-view.fxml"));
        assertFalse(mapping.contains("syncedColumn"));
        String mappingController=Files.readString(Path.of("src/main/java/com/tuandev/fbsbarcode/ui/kizmapping/KizMappingController.java"));
        assertFalse(mappingController.contains("showCirculationData"));
        assertFalse(mappingController.contains("openCirculationDialog"));
        String supply=Files.readString(Path.of("src/main/resources/com/tuandev/fbsbarcode/ui/supply/supply-detail-view.fxml"));
        assertTrue(supply.contains("minWidth=\"360.0\""));
        assertTrue(supply.contains("prefWidth=\"420.0\""));
        assertTrue(supply.contains("maxWidth=\"520.0\""));
        assertFalse(fxml.contains("cryptcpPathField"));
        assertFalse(fxml.contains("csptestPathField"));
        assertFalse(fxml.contains("authenticateButton"));
        assertFalse(java.util.regex.Pattern.compile("text=\"(?!%)[^\"]+\"").matcher(fxml).find());
    }

    @Test void deletingShopCascadesAllZnackData() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        repository.saveSettings(Settings.empty());
        repository.upsertProducts(List.of(new Product("04601234567890","Product",null,null,null,null,null)));
        repository.log("TEST",null,"INFO","test",null);
        try(Connection c=Database.getConnection();Statement st=c.createStatement()){
            st.executeUpdate("DELETE FROM shops WHERE id=1");
            for(String table:List.of("znack_settings","znack_products","znack_operation_logs")){
                try(ResultSet rs=st.executeQuery("SELECT count(*) FROM "+table+" WHERE shop_id=1")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
            }
        }
    }

    @Test void parsesStandardCertificateDiscoveryJsonAndBlocksUntestedSignature() {
        String json="""
                [{"id":"cert-1","subject":"CN=ООО Example, O=Example","inn":"7701234567","thumbprint":"abc",
                "issuer":"CN=CA","validFrom":"2025-01-01T00:00:00Z","validTo":"2026-01-01T00:00:00Z","hasPrivateKey":true,"provider":"CryptoPro"}]
                """;
        CryptoProCertificateInfo certificate=new CryptoProCertificateDiscoveryService().parse("""
                SHA1 Hash: abc
                Subject: CN=ООО Example, O=Example, INN=7701234567
                Issuer: CN=CA
                Not valid before: 01.01.2025 00:00:00
                Not valid after: 01.01.2027 00:00:00
                Private key: present
                Provider: CryptoPro
                """).getFirst();
        assertEquals("abc",certificate.selector());
        assertTrue(certificate.hasPrivateKey());
        assertEquals("ООО Example / INN 7701234567 / 01.01.2027", certificate.displayName());
        assertEquals(ZnackSafety.UNVERIFIED_SIGNATURE,
                assertThrows(IllegalStateException.class,()->ZnackSafety.requireSigned(
                        new Settings("","","","connection","","","","signer","cert","[]","","","",false),true)).getMessage());
    }

    @Test void ambiguousLegacyMigrationArchivesWithoutAssigningData() throws Exception {
        try(Connection c=Database.getConnection();Statement st=c.createStatement()){
            for(String table:List.of("znack_purchase_pipelines","znack_gtin_mapping_rules","kiz_codes","znack_documents","kiz_orders","znack_products","znack_operation_logs","znack_settings")) {
                st.execute("DROP TABLE "+table);
            }
            st.execute("""
                    CREATE TABLE znack_settings(id INTEGER PRIMARY KEY,true_api_base_url TEXT,suz_base_url TEXT,oms_id TEXT,
                    oms_connection TEXT,participant_inn TEXT,producer_inn TEXT,owner_inn TEXT,signer_executable TEXT,
                    signer_certificate TEXT,signer_arguments_json TEXT,document_number TEXT,document_date TEXT,pdf_folder TEXT,
                    auto_introduction INTEGER,updated_at TEXT)
                    """);
            st.execute("INSERT INTO znack_settings VALUES(1,'legacy','','','','','','','legacy-signer','legacy-cert','[]','','','',0,'2026-01-01T00:00:00Z')");
            ZnackSchemaSupport.initialize(c);
            try(ResultSet rs=st.executeQuery("SELECT count(*) FROM znack_settings")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
            try(ResultSet rs=st.executeQuery("SELECT true_api_base_url FROM znack_legacy_unscoped_znack_settings")){assertTrue(rs.next());assertEquals("legacy",rs.getString(1));}
            try(ResultSet rs=st.executeQuery("SELECT value FROM app_config WHERE key='"+ZnackSchemaSupport.AMBIGUOUS_MIGRATION_NOTICE+"'")){assertTrue(rs.next());assertEquals("pending",rs.getString(1));}
        }
    }

    @Test void legacyMigrationSkipsDuplicateCodesInsteadOfBlockingStartup() throws Exception {
        try(Connection c=Database.getConnection();Statement st=c.createStatement()){
            st.execute("DELETE FROM shops WHERE id=2");
            for(String table:List.of("znack_purchase_pipelines","znack_gtin_mapping_rules","kiz_codes","znack_documents",
                    "kiz_orders","znack_products","znack_operation_logs","znack_settings")) {
                st.execute("DROP TABLE "+table);
            }
            st.execute("""
                    CREATE TABLE znack_settings(id INTEGER PRIMARY KEY,true_api_base_url TEXT,suz_base_url TEXT,oms_id TEXT,
                    oms_connection TEXT,participant_inn TEXT,producer_inn TEXT,owner_inn TEXT,signer_executable TEXT,
                    signer_certificate TEXT,signer_arguments_json TEXT,document_number TEXT,document_date TEXT,pdf_folder TEXT,
                    auto_introduction INTEGER,updated_at TEXT)
                    """);
            st.execute("""
                    CREATE TABLE znack_products(gtin TEXT PRIMARY KEY,product_name TEXT,tn_ved TEXT,certificate_type TEXT,
                    certificate_number TEXT,certificate_date TEXT,production_date TEXT,synced_at TEXT)
                    """);
            st.execute("""
                    CREATE TABLE kiz_orders(id INTEGER PRIMARY KEY,external_order_id TEXT,gtin TEXT,quantity INTEGER,
                    remote_status TEXT,local_status TEXT,error_message TEXT,created_at TEXT,updated_at TEXT)
                    """);
            st.execute("""
                    CREATE TABLE kiz_codes(id INTEGER PRIMARY KEY,order_id INTEGER,raw_code TEXT,display_code TEXT,gtin TEXT,
                    block_id TEXT,pdf_path TEXT,document_id INTEGER,status TEXT,created_at TEXT,updated_at TEXT)
                    """);
            st.execute("INSERT INTO znack_settings VALUES(1,'','','','','','','','','','[]','','','',0,'2026-01-01T00:00:00Z')");
            st.execute("INSERT INTO znack_products VALUES('04601234567890','Product',NULL,NULL,NULL,NULL,NULL,'2026-01-01T00:00:00Z')");
            st.execute("""
                    INSERT INTO kiz_orders VALUES
                    (1,'a','04601234567890',1,'READY','CODES_DOWNLOADED',NULL,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'),
                    (2,'b','04601234567890',1,'READY','CODES_DOWNLOADED',NULL,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')
                    """);
            st.execute("""
                    INSERT INTO kiz_codes VALUES
                    (1,1,'same','same','04601234567890','a',NULL,NULL,'RECEIVED','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'),
                    (2,2,'same','same','04601234567890','b',NULL,NULL,'RECEIVED','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')
                    """);

            ZnackSchemaSupport.initialize(c);

            try(ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM kiz_codes WHERE raw_code='same'")){
                assertTrue(rs.next());
                assertEquals(1,rs.getInt(1));
            }
        }
    }

    @Test void unconfiguredTypedSignerFails() {
        byte[] input="signed input".getBytes(StandardCharsets.UTF_8);
        CryptoProException error=assertThrows(CryptoProException.class,
                ()->ZnackSignatureProvider.unconfigured().sign(input,ZnackSignatureContext.AUTH_CHALLENGE));
        assertEquals(CryptoProErrorCode.CRYPTOPRO_MISSING,error.code());
    }

    @Test void retriesTransientUotCredentialErrorThenSucceeds() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts=new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v4/true-api/product/gtin",exchange->{
            if(attempts.incrementAndGet()==1){
                byte[] body=("{\"globalErrors\":[{\"errorCode\":1090,\"error\":\"Проверка учетных данных УОТ не пройдена\"}],"
                        +"\"success\":false}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400,body.length);exchange.getResponseBody().write(body);exchange.close();
            } else {
                respond(exchange,"[]");
            }
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            new ZnackApiClient().products(base,"abc");
            assertEquals(2,attempts.get());
        } finally { server.stop(0); }
    }

    @Test void apiUsesDocumentedProductPathAndBearerHeader() throws Exception {
        AtomicReference<String> path=new AtomicReference<>(),catalogPath=new AtomicReference<>(),
                categoriesPath=new AtomicReference<>(),attributesPath=new AtomicReference<>(),
                authorization=new AtomicReference<>(),categoriesAuthorization=new AtomicReference<>(),
                attributesAuthorization=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v4/true-api/product/gtin",exchange->{path.set(exchange.getRequestURI().toString());authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));byte[] body="[]".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/api/v3/true-api/nk/feed-product",exchange->{catalogPath.set(exchange.getRequestURI().toString());respond(exchange,"{\"result\":[]}");});
        server.createContext("/api/v3/true-api/nk/categories",exchange->{categoriesPath.set(exchange.getRequestURI().toString());categoriesAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));respond(exchange,"{\"result\":[]}");});
        server.createContext("/api/v3/true-api/nk/attributes",exchange->{attributesPath.set(exchange.getRequestURI().toString());attributesAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));respond(exchange,"{\"result\":[]}");});
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            new ZnackApiClient().products(base,"abc");
            new ZnackApiClient().productCards(base,"abc","04601234567890;04601234567891");
            new ZnackApiClient().nationalCatalogCategories(base,"abc","6104690001");
            new ZnackApiClient().nationalCatalogAttributes(base,"abc",30933);
            assertEquals("/api/v4/true-api/product/gtin?includeSubaccount=false&limit=10000&page=0&pg=lp",path.get());
            assertEquals("/api/v3/true-api/nk/feed-product?gtins=04601234567890%3B04601234567891",catalogPath.get());
            assertEquals("/api/v3/true-api/nk/categories?tnved=6104690001",categoriesPath.get());
            assertEquals("/api/v3/true-api/nk/attributes?cat_id=30933&attr_type=m",attributesPath.get());
            assertEquals("Bearer abc",authorization.get());
            assertEquals("Bearer abc",categoriesAuthorization.get());
            assertEquals("Bearer abc",attributesAuthorization.get());
        } finally { server.stop(0); }
    }

    @Test void missingNationalCatalogCategoryLookupBecomesAnEmptyResult() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/nk/categories",exchange->{
            exchange.sendResponseHeaders(404,-1);
            exchange.close();
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();

            JsonElement response=new ZnackApiClient().nationalCatalogCategories(
                    base,"true-api-token","6104690001");

            assertTrue(response.isJsonNull());
        } finally { server.stop(0); }
    }

    @Test void retriesRateLimitedCatalogGetUsingRetryAfter() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/nk/feed-product",exchange->{
            if(attempts.incrementAndGet()==1){
                byte[] body="{\"error_message\":\"Слишком много запросов\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Retry-After","0");
                exchange.sendResponseHeaders(429,body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            } else {
                respond(exchange,"{\"result\":[]}");
            }
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();

            JsonElement response=new ZnackApiClient().productCards(base,"abc","04601234567890");

            assertEquals(2,attempts.get());
            assertTrue(response.getAsJsonObject().getAsJsonArray("result").isEmpty());
        } finally { server.stop(0); }
    }

    @Test void interpretsRetryAfterAsSecondsWithoutSleepingInTests() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        java.util.ArrayList<Long> delays=new java.util.ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/nk/feed-product",exchange->{
            if(attempts.incrementAndGet()==1){
                exchange.getResponseHeaders().add("Retry-After","7");
                exchange.sendResponseHeaders(429,-1);
                exchange.close();
            } else {
                respond(exchange,"{\"result\":[]}");
            }
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();

            new ZnackApiClient(delays::add).productCards(base,"abc","04601234567890");

            assertEquals(List.of(7_000L),delays);
            assertEquals(2,attempts.get());
        } finally { server.stop(0); }
    }

    @Test void usesBoundedFallbackWhenRetryAfterIsMalformed() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        java.util.ArrayList<Long> delays=new java.util.ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/nk/feed-product",exchange->{
            if(attempts.incrementAndGet()==1){
                exchange.getResponseHeaders().add("Retry-After","invalid");
                exchange.sendResponseHeaders(429,-1);
                exchange.close();
            } else {
                respond(exchange,"{\"result\":[]}");
            }
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();

            new ZnackApiClient(delays::add).productCards(base,"abc","04601234567890");

            assertEquals(List.of(1_000L),delays);
            assertEquals(2,attempts.get());
        } finally { server.stop(0); }
    }

    @Test void stopsRetryingCatalogGetAfterBoundedAttempts() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/nk/feed-product",exchange->{
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After","0");
            exchange.sendResponseHeaders(429,-1);
            exchange.close();
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();

            ZnackApiClient.ZnackApiException error=assertThrows(ZnackApiClient.ZnackApiException.class,
                    ()->new ZnackApiClient(ignored->{}).productCards(base,"abc","04601234567890"));

            assertEquals(429,error.statusCode());
            assertEquals(3,attempts.get());
        } finally { server.stop(0); }
    }

    @Test void rejectsRetryAfterBeyondDocumentedLimitWindow() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        java.util.ArrayList<Long> delays=new java.util.ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/nk/feed-product",exchange->{
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After","301");
            exchange.sendResponseHeaders(429,-1);
            exchange.close();
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();

            ZnackApiClient.ZnackApiException error=assertThrows(ZnackApiClient.ZnackApiException.class,
                    ()->new ZnackApiClient(delays::add).productCards(base,"abc","04601234567890"));

            assertEquals(429,error.statusCode());
            assertEquals(1,attempts.get());
            assertTrue(delays.isEmpty());
        } finally { server.stop(0); }
    }

    @Test void doesNotRetryRateLimitedDocumentPost() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/lk/documents/create",exchange->{
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After","0");
            exchange.sendResponseHeaders(429,-1);
            exchange.close();
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();

            ZnackApiClient.ZnackApiException error=assertThrows(ZnackApiClient.ZnackApiException.class,
                    ()->new ZnackApiClient(ignored->{}).createDocument(base,"abc",new JsonObject()));

            assertEquals(429,error.statusCode());
            assertEquals(1,attempts.get());
        } finally { server.stop(0); }
    }

    @Test void suzOrderSendsExactSignedBodyAndDocumentedHeaders() throws Exception {
        AtomicReference<byte[]> signed=new AtomicReference<>(),sent=new AtomicReference<>();
        AtomicReference<String> signature=new AtomicReference<>(),clientToken=new AtomicReference<>(),authorization=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/order",exchange->{
            sent.set(exchange.getRequestBody().readAllBytes());
            signature.set(exchange.getRequestHeaders().getFirst("X-Signature"));
            clientToken.set(exchange.getRequestHeaders().getFirst("clientToken"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange,"{\"orderId\":\"remote\"}");
        });
        server.start();
        try {
            ZnackRepository repository=repository(1,"Shop A");
            repository.upsertProducts(List.of(new Product("04601234567890","Product",null,null,null,null,null,
                    null,null,"","","",null,"UNIT")));
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            ZnackSignatureProvider signer=(input,context)->{
                assertEquals(ZnackSignatureContext.SUZ_POST_BODY,context);
                signed.set(input.clone());
                return new CryptoProSigningResult(new byte[]{0x30,0x02,0x01,0x00},"");
            };
            ZnackAuthService auth=new ZnackAuthService(new ZnackApiClient(),signer){@Override public String suzToken(Settings s){return "dynamic-client-token";}};
            new ZnackKizOrderService(new ZnackApiClient(),auth,signer,repository)
                    .buy(testedSettings("",base,"oms","connection",""),"04601234567890",2);
            assertArrayEquals(signed.get(),sent.get());
            assertEquals("MAIBAA==",signature.get());
            assertEquals("dynamic-client-token",clientToken.get());
            assertNull(authorization.get());
            JsonObject order=JsonParser.parseString(new String(sent.get(),StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(order.has("signature"));
            assertFalse(order.has("templateId"));
            assertFalse(order.has("cisType"));
            assertFalse(order.has("releaseMethodType"));
            assertEquals("PRODUCTION",order.getAsJsonObject("attributes").get("releaseMethodType").getAsString());
            JsonObject product=order.getAsJsonArray("products").get(0).getAsJsonObject();
            assertEquals("OPERATOR",product.get("serialNumberType").getAsString());
            assertEquals(10,product.get("templateId").getAsInt());
            assertEquals("UNIT",product.get("cisType").getAsString());
        } finally { server.stop(0); }
    }

    @Test void suzOrderUsesBundleTypeForComplexLightIndustryGtin() throws Exception {
        AtomicReference<byte[]> sent=new AtomicReference<>();
        AtomicReference<String> productInfoAuthorization=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v4/true-api/product/info",exchange->{
            productInfoAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange,"{\"results\":[{\"gtin\":\"04627154623006\",\"isKit\":true}]}");
        });
        server.createContext("/api/v3/order",exchange->{
            sent.set(exchange.getRequestBody().readAllBytes());
            respond(exchange,"{\"orderId\":\"remote\"}");
        });
        server.start();
        try {
            ZnackRepository repository=repository(1,"Shop A");
            repository.upsertProducts(List.of(new Product("04627154623006","Complex clothing set",null,
                    null,null,null,null)));
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            ZnackSignatureProvider signer=(input,context)->new CryptoProSigningResult(new byte[]{0x30,0x02,0x01,0x00},"");
            ZnackAuthService auth=new ZnackAuthService(new ZnackApiClient(),signer){
                @Override public String suzToken(Settings s){return "dynamic-client-token";}
                @Override public String trueApiToken(Settings s){return "true-api-token";}
            };

            new ZnackKizOrderService(new ZnackApiClient(),auth,signer,repository)
                    .buy(testedSettings(base,base,"oms","connection",""),"04627154623006",1);

            JsonObject product=JsonParser.parseString(new String(sent.get(),StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("products").get(0).getAsJsonObject();
            assertEquals(10,product.get("templateId").getAsInt());
            assertEquals("BUNDLE",product.get("cisType").getAsString());
            assertEquals("Bearer true-api-token",productInfoAuthorization.get());
            assertEquals("BUNDLE",repository.findProduct("04627154623006").orElseThrow().cisType());
        } finally { server.stop(0); }
    }

    @Test void ambiguousOrderReconciliationUsesRecoveryListAndExactPurchaseFingerprint() throws Exception {
        AtomicReference<String> path=new AtomicReference<>(),clientToken=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/order/list",exchange->{
            path.set(exchange.getRequestURI().toString());
            clientToken.set(exchange.getRequestHeaders().getFirst("clientToken"));
            respond(exchange,"""
                    {"omsId":"oms","orderInfos":[
                      {"orderId":"wrong-quantity","orderStatus":"READY","createdTimestamp":%d,
                       "buffers":[{"gtin":"04601234567890","totalCodes":1}]},
                      {"orderId":"recovered-order","orderStatus":"READY","createdTimestamp":%d,
                       "buffers":[{"gtin":"04601234567890","totalCodes":2}]}
                    ]}
                    """.formatted(System.currentTimeMillis(),System.currentTimeMillis()));
        });
        server.start();
        try {
            ZnackRepository repository=repository(1,"Shop A");
            repository.upsertProducts(List.of(new Product("04601234567890","Product",null,null,null,null,null)));
            long localId=repository.createDraft("04601234567890",2);
            KizOrder local=repository.findOrder(localId).orElseThrow();
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            ZnackSignatureProvider signer=(input,context)->new CryptoProSigningResult(new byte[]{0x30,0x02,0x01,0x00},"");
            ZnackAuthService auth=new ZnackAuthService(new ZnackApiClient(),signer){
                @Override public String suzToken(Settings ignored){return "dynamic-client-token";}
            };

            ZnackKizOrderService.OrderReconciliation result=
                    new ZnackKizOrderService(new ZnackApiClient(),auth,signer,repository)
                            .reconcile(testedSettings("",base,"oms","connection",""),local);

            assertEquals(ZnackKizOrderService.ReconciliationStatus.MATCHED,result.status());
            assertEquals("recovered-order",result.order().externalOrderId());
            assertEquals("/api/v3/order/list?omsId=oms",path.get());
            assertEquals("dynamic-client-token",clientToken.get());
        } finally { server.stop(0); }
    }

    @Test void suzCodeDownloadUsesDocumentedClientTokenAndQueryParameters() throws Exception {
        AtomicReference<String> path=new AtomicReference<>(),clientToken=new AtomicReference<>(),authorization=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/codes",exchange->{
            path.set(exchange.getRequestURI().toString());
            clientToken.set(exchange.getRequestHeaders().getFirst("clientToken"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange,"{\"codes\":[]}");
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            new ZnackApiClient().codes(base,"dynamic-client-token","oms-value","order-value",12,"04601234567890");
            assertEquals("/api/v3/codes?omsId=oms-value&orderId=order-value&quantity=12&gtin=04601234567890",path.get());
            assertEquals("dynamic-client-token",clientToken.get());
            assertNull(authorization.get());
        } finally { server.stop(0); }
    }

    @Test void codeDownloadRequestsOnlyTheUnpersistedRemainder() throws Exception {
        AtomicReference<String> path=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/codes",exchange->{
            path.set(exchange.getRequestURI().toString());
            respond(exchange,"{\"blockId\":\"second\",\"codes\":[\"new-1\",\"new-2\"]}");
        });
        server.start();
        try {
            ZnackRepository repository=repository(1,"Shop A");
            repository.upsertProducts(List.of(new Product("04601234567890","Product",null,null,null,null,null)));
            long order=repository.createDraft("04601234567890",3);
            repository.updateOrder(order,"external-order","ACTIVE",OrderStatus.CODES_READY,null);
            repository.insertCodes(order,"04601234567890",new DownloadedCodes(List.of("already-saved"),"first"));
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            ZnackApiClient api=new ZnackApiClient();
            ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
                @Override public String suzToken(Settings ignored){return "token";}
            };

            int inserted=new ZnackKizCodeService(api,auth,repository).download(
                    testedSettings("",base,"oms","connection",""),order);

            assertEquals(2,inserted);
            assertEquals(3,repository.findCodes(order).size());
            assertEquals("/api/v3/codes?omsId=oms&orderId=external-order&quantity=2&gtin=04601234567890",path.get());
        } finally { server.stop(0); }
    }

    @Test void issuedBlockRecoverySkipsPersistedBlocksAndIsIdempotent() throws Exception {
        AtomicInteger retryCalls=new AtomicInteger();
        AtomicReference<String> retryPath=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/order/codes/blocks",exchange->respond(exchange,
                "[{\"gtin\":\"04601234567890\",\"blockIds\":[\"saved\",\"missing\"]}]"));
        server.createContext("/api/v3/order/codes/retry",exchange->{
            retryCalls.incrementAndGet();
            retryPath.set(exchange.getRequestURI().toString());
            respond(exchange,"{\"blockId\":\"missing\",\"codes\":[\"recovered-1\",\"recovered-2\"]}");
        });
        server.start();
        try {
            ZnackRepository repository=repository(1,"Shop A");
            repository.upsertProducts(List.of(new Product("04601234567890","Product",null,null,null,null,null)));
            long order=repository.createDraft("04601234567890",3);
            repository.updateOrder(order,"external-order","CLOSED",OrderStatus.WAITING_CODES,null);
            repository.insertCodes(order,"04601234567890",new DownloadedCodes(List.of("already-saved"),"saved"));
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            ZnackApiClient api=new ZnackApiClient();
            ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
                @Override public String suzToken(Settings ignored){return "token";}
            };
            ZnackKizCodeService service=new ZnackKizCodeService(api,auth,repository);
            Settings settings=testedSettings("",base,"oms","connection","");

            assertEquals(2,service.recoverIssuedBlocks(settings,order));
            assertEquals(0,service.recoverIssuedBlocks(settings,order));
            assertEquals(3,repository.findCodes(order).size());
            assertEquals(1,retryCalls.get());
            assertEquals("/api/v3/order/codes/retry?omsId=oms&blockId=missing",retryPath.get());
        } finally { server.stop(0); }
    }

    @Test void suzCodeBlockRecoveryUsesDocumentedClientTokenAndQueryParameters() throws Exception {
        AtomicReference<String> blocksPath=new AtomicReference<>(),retryPath=new AtomicReference<>();
        AtomicReference<String> blocksToken=new AtomicReference<>(),retryToken=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/order/codes/blocks",exchange->{
            blocksPath.set(exchange.getRequestURI().toString());
            blocksToken.set(exchange.getRequestHeaders().getFirst("clientToken"));
            respond(exchange,"{\"blocks\":[]}");
        });
        server.createContext("/api/v3/order/codes/retry",exchange->{
            retryPath.set(exchange.getRequestURI().toString());
            retryToken.set(exchange.getRequestHeaders().getFirst("clientToken"));
            respond(exchange,"{\"codes\":[]}");
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            ZnackApiClient api=new ZnackApiClient();
            api.codeBlocks(base,"dynamic-client-token","oms-value","order-value","04601234567890");
            api.retryCodeBlock(base,"dynamic-client-token","oms-value","block-value");
            assertEquals("/api/v3/order/codes/blocks?omsId=oms-value&orderId=order-value&gtin=04601234567890",blocksPath.get());
            assertEquals("/api/v3/order/codes/retry?omsId=oms-value&blockId=block-value",retryPath.get());
            assertEquals("dynamic-client-token",blocksToken.get());
            assertEquals("dynamic-client-token",retryToken.get());
        } finally { server.stop(0); }
    }

    @Test void trueApiConfirmationUsesDocumentedPathsAndCisArrayBody() throws Exception {
        AtomicReference<String> documentPath=new AtomicReference<>(),cisesPath=new AtomicReference<>(),cisesBody=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v4/true-api/doc/doc-id/info",exchange->{documentPath.set(exchange.getRequestURI().toString());respond(exchange,"{}");});
        server.createContext("/api/v3/true-api/cises/info",exchange->{cisesPath.set(exchange.getRequestURI().toString());cisesBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));respond(exchange,"[]");});
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            ZnackApiClient api=new ZnackApiClient();
            api.document(base,"token","doc-id");
            JsonArray codes=new JsonArray();codes.add("010460123456789021abc");
            api.cisesInfo(base,"token",codes);
            assertEquals("/api/v4/true-api/doc/doc-id/info?pg=lp",documentPath.get());
            assertEquals("/api/v3/true-api/cises/info?pg=lp",cisesPath.get());
            assertEquals(codes,JsonParser.parseString(cisesBody.get()));
        } finally { server.stop(0); }
    }

    @Test void documentCreationUsesTrueApiPath() throws Exception {
        AtomicReference<String> path=new AtomicReference<>(),authorization=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/lk/documents/create",exchange->{
            path.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange,"document-uuid");
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort()+"/api/v3/true-api";

            String response=new ZnackApiClient().createDocument(base,"true-api-token",new JsonObject());

            assertEquals("/api/v3/true-api/lk/documents/create?pg=lp",path.get());
            assertEquals("Bearer true-api-token",authorization.get());
            assertEquals("document-uuid",response);
        } finally { server.stop(0); }
    }

    @Test void documentCreationAlsoAcceptsLegacyObjectResponse() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/lk/documents/create",exchange->
                respond(exchange,"{\"uuid\":\"legacy-document-uuid\"}"));
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();

            String response=new ZnackApiClient().createDocument(base,"true-api-token",new JsonObject());

            assertEquals("legacy-document-uuid",response);
        } finally { server.stop(0); }
    }

    @Test void cisesInfoReturnsStructuredNotFoundBodyForReadinessPolling() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/cises/info",exchange->{
            byte[] body="[{\"cisInfo\":{\"requestedCis\":\"code\"},\"errorMessage\":\"КМ/КИ не найден\",\"errorCode\":\"404\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(404,body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            JsonArray codes=new JsonArray();codes.add("code");

            JsonElement response=new ZnackApiClient().cisesInfo(base,"token",codes);

            assertEquals("КМ/КИ не найден",response.getAsJsonArray().get(0).getAsJsonObject()
                    .get("errorMessage").getAsString());
        } finally { server.stop(0); }
    }

    @Test void missingGeneratedGtinDraftsAreAnEmptyLookupInsteadOfARegistrationError() throws Exception {
        AtomicReference<String> path=new AtomicReference<>(),authorization=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/v3/generate-gtins",exchange->{
            path.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(404,-1);
            exchange.close();
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();

            JsonElement response=new ZnackApiClient().generatedGtins(base,"true-api-token");

            assertTrue(response.isJsonNull());
            assertEquals("/v3/generate-gtins?exist=1",path.get());
            assertEquals("Bearer true-api-token",authorization.get());
        } finally { server.stop(0); }
    }

    @Test void introductionConfirmationAcceptsDirectDocumentStatusResponseWithoutRepeatedDocumentId() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        long orderId=orderWithCodes(repository);
        long documentId=repository.createDocument(orderId,"{}");
        repository.updateDocument(documentId,"doc-id","SUBMITTED",null);
        AtomicReference<JsonElement> cisesRequest=new AtomicReference<>();
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement document(String base,String token,String externalId){
                return JsonParser.parseString("{\"status\":\"CHECKED_OK\"}");
            }
            @Override public JsonElement cisesInfo(String base,String token,JsonElement body){
                cisesRequest.set(body);
                return JsonParser.parseString("[{\"status\":\"INTRODUCED\"}]");
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        assertTrue(new ZnackIntroductionService(api,auth,testSigner(),repository).confirm(
                testedSettings("","","","connection",""),repository.findOrder(orderId).orElseThrow(),
                repository.findCodes(orderId)).introduced());
        assertEquals("010460123456789021abcdefghijklm",cisesRequest.get().getAsJsonArray().get(0).getAsString());
        assertEquals(OrderStatus.INTRODUCED,repository.findOrder(orderId).orElseThrow().localStatus());
    }

    @Test void introductionConfirmationPrefersDocumentSuccessOverNestedFailureStatuses() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        long orderId=orderWithCodes(repository);
        long documentId=repository.createDocument(orderId,"{}");
        repository.updateDocument(documentId,"doc-id","SUBMITTED",null);
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement document(String base,String token,String externalId){
                return JsonParser.parseString(
                        "{\"status\":\"CHECKED_OK\",\"items\":[{\"status\":\"REJECTED\"}]}");
            }
            @Override public JsonElement cisesInfo(String base,String token,JsonElement body){
                return JsonParser.parseString("[{\"status\":\"INTRODUCED\"}]");
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        assertTrue(new ZnackIntroductionService(api,auth,testSigner(),repository).confirm(
                testedSettings("","","","connection",""),repository.findOrder(orderId).orElseThrow(),
                repository.findCodes(orderId)).introduced());
        assertEquals("CHECKED_OK",repository.findLatestDocument(orderId).orElseThrow().status());
    }

    @Test void introductionConfirmationDoesNotDoubleCountParentAndChildStatuses() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        Product product=new Product("04601234567890","Product","6201000000",null,null,null,null);
        repository.upsertProducts(List.of(product));
        long orderId=repository.createDraft(product.gtin(),2);
        repository.insertCodes(orderId,product.gtin(),new DownloadedCodes(List.of("one","two"),"block"));
        long documentId=repository.createDocument(orderId,"{}");
        repository.updateDocument(documentId,"doc-id","SUBMITTED",null);
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement document(String base,String token,String externalId){
                return JsonParser.parseString("{\"status\":\"CHECKED_OK\"}");
            }
            @Override public JsonElement cisesInfo(String base,String token,JsonElement body){
                return JsonParser.parseString("{\"status\":\"INTRODUCED\",\"items\":[{\"status\":\"INTRODUCED\"}]}");
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        assertFalse(new ZnackIntroductionService(api,auth,testSigner(),repository).confirm(
                testedSettings("","","","connection",""),repository.findOrder(orderId).orElseThrow(),
                repository.findCodes(orderId)).introduced());
        assertNotEquals(OrderStatus.INTRODUCED,repository.findOrder(orderId).orElseThrow().localStatus());
    }

    @Test void introductionConfirmationSplitsMoreThanOneThousandCodesIntoValidTrueApiBatches() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        Product product=new Product("04601234567890","Product","6201000000",null,null,null,null);
        repository.upsertProducts(List.of(product));
        int quantity=2_001;
        long orderId=repository.createDraft(product.gtin(),quantity);
        List<String> rawCodes=new ArrayList<>(quantity);
        for(int index=0;index<quantity;index++)rawCodes.add("code-"+index);
        repository.insertCodes(orderId,product.gtin(),new DownloadedCodes(rawCodes,"block"));
        long documentId=repository.createDocument(orderId,"{}");
        repository.updateDocument(documentId,"doc-id","SUBMITTED",null);
        List<Integer> requestSizes=new ArrayList<>();
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement document(String base,String token,String externalId){
                return JsonParser.parseString("{\"status\":\"CHECKED_OK\"}");
            }
            @Override public JsonElement cisesInfo(String base,String token,JsonElement body){
                JsonArray request=body.getAsJsonArray();
                requestSizes.add(request.size());
                JsonArray response=new JsonArray();
                request.forEach(ignored->{JsonObject item=new JsonObject();item.addProperty("status","INTRODUCED");response.add(item);});
                return response;
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        ZnackIntroductionService.ConfirmResult result=new ZnackIntroductionService(api,auth,testSigner(),repository)
                .confirm(testedSettings("","","","connection",""),repository.findOrder(orderId).orElseThrow(),
                        repository.findCodes(orderId));

        assertTrue(result.introduced());
        assertEquals(List.of(1_000,1_000,1),requestSizes);
        assertTrue(repository.findCodes(orderId).stream()
                .allMatch(code->code.legalStatus()==KizLegalStatus.IN_CIRCULATION));
        assertEquals(OrderStatus.INTRODUCED,repository.findOrder(orderId).orElseThrow().localStatus());
    }

    @Test void persistedPollingIntroductionAutomaticallyResumesWithBatchedConfirmation() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        Product product=new Product("04601234567890","Product","6201000000",null,null,null,null);
        repository.upsertProducts(List.of(product));
        int quantity=1_001;
        long orderId=repository.createDraft(product.gtin(),quantity);
        List<String> rawCodes=new ArrayList<>(quantity);
        for(int index=0;index<quantity;index++)rawCodes.add("resume-code-"+index);
        repository.insertCodes(orderId,product.gtin(),new DownloadedCodes(rawCodes,"block"));
        long documentId=repository.createDocument(orderId,"{}");
        repository.updateDocument(documentId,"doc-id","SUBMITTED",null);
        long pipelineId=repository.createPipeline(product.gtin(),quantity);
        repository.updatePipeline(pipelineId,orderId,PurchaseStage.POLLING_INTRODUCTION,
                "HTTP 400: Too many KIZ codes");
        List<Integer> requestSizes=new ArrayList<>();
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement document(String base,String token,String externalId){
                return JsonParser.parseString("{\"status\":\"CHECKED_OK\"}");
            }
            @Override public JsonElement cisesInfo(String base,String token,JsonElement body){
                JsonArray request=body.getAsJsonArray();
                requestSizes.add(request.size());
                JsonArray response=new JsonArray();
                request.forEach(ignored->{JsonObject item=new JsonObject();item.addProperty("status","INTRODUCED");response.add(item);});
                return response;
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };
        ZnackPurchaseCoordinator coordinator=new ZnackPurchaseCoordinator(repository,null,null,
                new ZnackIntroductionService(api,auth,testSigner(),repository)){
            @Override void schedule(long ignoredPipelineId){
            }
        };

        coordinator.resume(testedSettings("","","","connection",""));

        assertEquals(List.of(1_000,1),requestSizes);
        assertEquals(PurchaseStage.INTRODUCED,repository.findPipeline(pipelineId).orElseThrow().stage());
        assertTrue(repository.findPipeline(pipelineId).orElseThrow().errorMessage().isBlank());
        assertEquals(OrderStatus.INTRODUCED,repository.findOrder(orderId).orElseThrow().localStatus());
    }

    @Test void configuredCryptoProOverrideMustResolveToAnExecutable() {
        CryptoProException error=assertThrows(CryptoProException.class,
                ()->new CryptoProCommandRunner().resolve(temp.resolve("missing-cryptcp").toString(),"cryptcp"));
        assertEquals(CryptoProErrorCode.CRYPTCP_MISSING,error.code());
    }

    @Test void productSyncStoresRealMetadataAndDoesNotEraseManualFallbacksWhenApiOmitsThem() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                return JsonParser.parseString("""
                        {"results":[{"gtin":"04601234567890","productName":"Product","isKit":true,
                        "certificate_type":"UNTRUSTED-FLAT-VALUE","certificate_number":"UNTRUSTED-DOC",
                        "certificate_date":"20.06.2024","production_date":"21.06.2024"}]}
                        """);
            }
            @Override public JsonElement productCards(String base,String token,String gtins){
                return JsonParser.parseString("""
                        {"result":[{"good_name":"National Catalog Product",
                        "categories":[
                          {"cat_id":30717,"cat_name":"Обувь домашняя"},
                          {"cat_id":30718,"cat_name":"Обувь детская"}
                        ],
                        "good_attrs":[
                          {"attr_id":35,"attr_name":"Размер одежды / изделия","attr_value":"XL"},
                          {"attr_id":14013,"attr_name":"Целевой пол","attr_value":"МУЖСКОЙ"},
                          {"attr_id":3959,"attr_name":"Группа ТНВЭД","attr_value":"6202"},
                          {"attr_id":13933,"attr_name":"Код ТНВЭД","attr_value":"6202 30 00 00"},
                          {"attr_id":23557,"certificate_number":"DECLARATION-1","certificate_issued_date":"2026-01-10"},
                          {"attr_id":23561,"certificate_number":"CERTIFICATE-2","certificate_issued_date":"2026-02-11"}
                        ],
                        "identified_by":[{"type":"gtin","value":"04601234567890"}]}]}
                        """);
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };
        ZnackProductService service=new ZnackProductService(api,auth,repository);

        service.sync(testedSettings("","","","connection",""));
        Product synced=repository.findProducts().getFirst();
        assertEquals("National Catalog Product",synced.productName());
        assertEquals("6202300000",synced.tnVed());
        assertEquals("Обувь домашняя, Обувь детская",synced.category());
        assertEquals("DECLARATION-1",synced.certificateNumber());
        assertEquals(List.of(
                new GoodsDocument("CONFORMITY_DECLARATION","DECLARATION-1","2026-01-10"),
                new GoodsDocument("CONFORMITY_CERTIFICATE","CERTIFICATE-2","2026-02-11")),
                synced.permitDocuments());
        assertEquals("21.06.2024",synced.productionDate());
        assertEquals("BUNDLE",synced.cisType());
        var labelMetadata=new KizMappingRepository().findLabelMetadata(1,synced.gtin());
        assertEquals("МУЖСКОЙ",labelMetadata.gender());
        assertEquals("XL",labelMetadata.size());

        repository.updateProductMetadata(new Product(synced.gtin(),synced.productName(),"manual-tnved",
                synced.certificateType(),synced.certificateNumber(),synced.certificateDate(),synced.productionDate()));
        repository.upsertProducts(List.of(new Product(synced.gtin(),"Updated name",null,null,null,null,null)));
        Product preserved=repository.findProducts().getFirst();
        assertEquals("Updated name",preserved.productName());
        assertEquals("manual-tnved",preserved.tnVed());

        repository.upsertProducts(List.of(new Product(synced.gtin(),"Updated name","6202400000",null,null,null,null)));
        assertEquals("6202400000",repository.findProducts().getFirst().tnVed());
        assertEquals("BUNDLE",repository.findProducts().getFirst().cisType());
    }

    @Test void productSyncKeepsGtinWithOnlyConformityCertificate() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                return JsonParser.parseString("""
                        {"results":[{"gtin":"04601234567890","productName":"Product","tnVedCode10":"6202300000"}]}
                        """);
            }
            @Override public JsonElement productCards(String base,String token,String gtins){
                return JsonParser.parseString("""
                        {"result":[{"identified_by":[{"type":"gtin","value":"04601234567890"}],
                        "good_attrs":[{"attr_id":23561,"certificate_number":"CERTIFICATE-ONLY",
                        "certificate_issued_date":"2026-03-11"}]}]}
                        """);
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        new ZnackProductService(api,auth,repository).sync(testedSettings("","","","connection",""));

        Product synced=repository.findProducts().getFirst();
        assertEquals("CONFORMITY_CERTIFICATE",synced.certificateType());
        assertEquals("CERTIFICATE-ONLY",synced.certificateNumber());
        assertEquals("2026-03-11",synced.certificateDate());
        assertEquals(List.of(new GoodsDocument("CONFORMITY_CERTIFICATE","CERTIFICATE-ONLY","2026-03-11")),
                synced.permitDocuments());
    }

    @Test void productSyncAcceptsCatalogUiCardShapeForCertificateAndLabelMetadata() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        repository.upsertProducts(List.of(new Product(
                "04627877922363","Old product","6107110000",null,null,null,null)));
        repository.softDeleteProducts(List.of("04627877922363"));
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                return JsonParser.parseString("""
                        {"results":[{"gtin":"04627877922363","productName":"Product",
                        "tnVedCode10":"6107110000"}]}
                        """);
            }
            @Override public JsonElement productCards(String base,String token,String gtins){
                return JsonParser.parseString("""
                        {"result":[{
                          "gtin":"4627877922363",
                          "status":"published",
                          "businessLayer":{"attrGroup":[{"attributes":[
                            {"id":23561,"name":"Сертификат соответствия","value":"11555611",
                             "showValue":{"number":"ЕАЭС RU С-CN.АБ47.В.03492/24","dateFrom":"2024-01-26"}},
                            {"id":14013,"name":"Целевой пол","value":"МУЖСКОЙ"},
                            {"id":35,"name":"Размер одежды / изделия","value":"48-56"}
                          ]}]}
                        }]}
                        """);
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        new ZnackProductService(api,auth,repository).sync(testedSettings("","","","connection",""));

        Product synced=repository.findProduct("04627877922363").orElseThrow();
        assertEquals(List.of("04627877922363"),repository.findProducts().stream().map(Product::gtin).toList(),
                "a complete document must restore a GTIN hidden by the no-document sync rule");
        assertEquals(List.of(new GoodsDocument("CONFORMITY_CERTIFICATE",
                "ЕАЭС RU С-CN.АБ47.В.03492/24","2024-01-26")),synced.permitDocuments());
        var metadata=new KizMappingRepository().findLabelMetadata(1,synced.gtin());
        assertEquals("МУЖСКОЙ",metadata.gender());
        assertEquals("48-56",metadata.size());
    }

    @Test void productSyncUsesOfficialRegistryCertificateForWaitingIntroductionWhenFeedOmitsIt() throws Exception {
        String gtin="04627877922394";
        ZnackRepository repository=repository(1,"Shop A");
        repository.upsertProducts(List.of(new Product(
                gtin,"Трусы боксеры для мальчиков","6107110000",null,null,null,null)));
        long orderId=repository.createDraft(gtin,1);
        repository.insertCodes(orderId,gtin,new DownloadedCodes(List.of(
                "010462787792239421abcdefghijklm\u001D91ABCD\u001D92signature"),"block"));
        long pipelineId=repository.createPipeline(gtin,1);
        repository.updatePipeline(pipelineId,orderId,PurchaseStage.WAITING_INTRODUCTION_READINESS,
                "GTIN "+gtin+" has no active National Catalog declaration or certificate. "
                        +"Publish or reactivate the GTIN document; the introduction will retry automatically.");
        repository.softDeleteProducts(List.of(gtin));
        AtomicInteger registryCalls=new AtomicInteger();
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                return JsonParser.parseString("""
                        {"results":[{"gtin":"04627877922394","productName":"Трусы боксеры для мальчиков",
                        "tnVedCode10":"6107110000"}]}
                        """);
            }
            @Override public JsonElement productCards(String base,String token,String gtins){
                return JsonParser.parseString("""
                        {"result":[{"identified_by":[{"type":"gtin","value":"04627877922394"}],
                        "good_name":"Трусы боксеры для мальчиков"}]}
                        """);
            }
            @Override public JsonElement permitDocuments(String base,String token,String requestedGtin,String inn){
                registryCalls.incrementAndGet();
                assertEquals(gtin,requestedGtin);
                return JsonParser.parseString("""
                        {"result":{"documents":[{
                          "attr_id":23561,
                          "number":"ЕАЭС RU С-CN.АБ47.В.03492/24",
                          "from_date":"2024-01-26",
                          "status_group":1,
                          "status":"Действует"
                        }]}}
                        """);
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        new ZnackProductService(api,auth,repository).sync(testedSettings("","","","connection",""));

        assertEquals(1,registryCalls.get(),"one registry lookup is enough for every waiting order of the GTIN");
        Product synced=repository.findProduct(gtin).orElseThrow();
        assertEquals(List.of(new GoodsDocument("CONFORMITY_CERTIFICATE",
                "ЕАЭС RU С-CN.АБ47.В.03492/24","2024-01-26")),synced.permitDocuments());
        assertEquals(List.of(gtin),repository.findProducts().stream().map(Product::gtin).toList(),
                "an active official certificate must restore the GTIN from trash");
        assertEquals(List.of(pipelineId),repository.findWaitingIntroductionDocumentPipelines().stream()
                .map(ZnackPurchasePipelineState::id).toList(),
                "legacy missing-document readiness waits must remain recoverable after upgrade");
        assertTrue(repository.findActivePipelines().isEmpty(),
                "legacy missing-document waits must not be polled as ordinary readiness work");
    }

    @Test void productSyncPreservesExistingDocumentsAndActiveStatusWhenCatalogBatchFails() throws Exception {
        String gtin = "04627877922395";
        ZnackRepository repository = repository(1, "Shop A");
        Product existing = new Product(gtin, "Existing Product", "6107110000", null, null, null, null);
        repository.upsertProducts(List.of(existing));
        repository.updateProductDocuments(gtin, List.of(new GoodsDocument("CONFORMITY_DECLARATION", "DECLARATION-123", "2024-01-01")));

        ZnackApiClient api = new ZnackApiClient() {
            @Override public JsonElement products(String base, String token) {
                return JsonParser.parseString("""
                        {"results":[{"gtin":"04627877922395","productName":"Existing Product",
                        "tnVedCode10":"6107110000"}]}
                        """);
            }
            @Override public JsonElement productCards(String base, String token, String gtins) {
                throw new RuntimeException("Simulated HTTP 429 catalog failure");
            }
        };
        ZnackAuthService auth = new ZnackAuthService(api, testSigner()) {
            @Override public String trueApiToken(Settings s) { return "token"; }
        };

        new ZnackProductService(api, auth, repository).sync(testedSettings("", "", "", "connection", ""));

        Product synced = repository.findProduct(gtin).orElseThrow();
        assertEquals(List.of(gtin), repository.findProducts().stream().map(Product::gtin).toList(),
                "failed catalog batches must not soft-delete active products");
        assertEquals(List.of(new GoodsDocument("CONFORMITY_DECLARATION", "DECLARATION-123", "2024-01-01")),
                synced.permitDocuments(), "existing local documents must be preserved when catalog batch fails");
    }

    @Test void productSyncFetchesEveryReportedPage() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        AtomicInteger requestedPage=new AtomicInteger(-1);
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                return JsonParser.parseString("{\"results\":[{\"gtin\":\"04601234567890\",\"productName\":\"A\",\"tnVedCode10\":\"6101000000\"}],\"total\":2}");
            }
            @Override public JsonElement products(String base,String token,int page,int limit){
                requestedPage.set(page);
                return JsonParser.parseString("{\"results\":[{\"gtin\":\"04601234567891\",\"productName\":\"B\",\"tnVedEaes\":\"6202000000\"}],\"total\":2}");
            }
            @Override public JsonElement productCards(String base,String token,String gtins){
                JsonArray result=new JsonArray();
                for(String gtin:gtins.split(";")){
                    result.add(JsonParser.parseString("""
                            {"good_attrs":[{"attr_id":23557,"certificate_number":"DECL-%s",
                            "certificate_issued_date":"2026-01-10"}],
                            "identified_by":[{"type":"gtin","value":"%s"}]}
                            """.formatted(gtin,gtin)));
                }
                JsonObject response=new JsonObject();
                response.add("result",result);
                return response;
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        List<Product> products=new ZnackProductService(api,auth,repository)
                .sync(testedSettings("","","","connection",""));

        assertEquals(1,requestedPage.get());
        assertEquals(List.of("04601234567890","04601234567891"),products.stream().map(Product::gtin).toList());
        assertEquals(List.of("6101000000","6202000000"),products.stream().map(Product::tnVed).toList());
        assertEquals(2,repository.findProducts().size());
    }

    @Test void productSyncPersistsSuccessfulCatalogBatchesAndReportsExhaustedOnesAsPartial() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        AtomicInteger catalogRequests=new AtomicInteger();
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                JsonArray results=new JsonArray();
                for(int index=0;index<26;index++){
                    JsonObject product=new JsonObject();
                    product.addProperty("gtin",String.format("0460123456%04d",index));
                    product.addProperty("good_status","published");
                    results.add(product);
                }
                JsonObject response=new JsonObject();
                response.add("results",results);
                return response;
            }
            @Override public JsonElement productCards(String base,String token,String gtins)throws java.io.IOException{
                if(catalogRequests.incrementAndGet()>1){
                    throw new ZnackApiClient.ZnackApiException("Znack API request failed",429,
                            "{\"error_message\":\"Слишком много запросов\"}");
                }
                String gtin=gtins.split(";")[0];
                return JsonParser.parseString("""
                        {"result":[{"good_name":"Enriched product","good_attrs":[
                        {"attr_id":13933,"attr_value":"6202 30 00 00"},
                        {"attr_id":23557,"certificate_number":"DECL-1","certificate_issued_date":"2026-01-10"}],
                        "identified_by":[{"type":"gtin","value":"%s"}]}]}
                        """.formatted(gtin));
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        new ZnackProductService(api,auth,repository).sync(testedSettings("","","","connection",""));

        assertEquals("Enriched product",repository.findProduct("04601234560000").orElseThrow().productName());
        OperationLog syncLog=repository.findLogs().stream()
                .filter(log->"GTIN_SYNC".equals(log.action())).findFirst().orElseThrow();
        assertEquals("WARN",syncLog.severity());
        assertTrue(syncLog.message().contains("partial"));
        assertTrue(syncLog.message().contains("1 catalog batch"));
    }

    @Test void productSyncKeepsV119PriorityForPreviouslyIncompleteGtins() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        String missingGtin="04601234560025";
        List<Product> existing=new ArrayList<>();
        for(int index=0;index<25;index++)existing.add(new Product(
                String.format("0460123456%04d",index),"Existing "+index,"6202300000",null,null,null,null));
        existing.add(new Product(missingGtin,"","",null,null,null,null));
        repository.upsertProducts(existing);

        AtomicInteger catalogRequests=new AtomicInteger();
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                JsonArray results=new JsonArray();
                for(int index=0;index<26;index++){
                    JsonObject product=new JsonObject();
                    product.addProperty("gtin",String.format("0460123456%04d",index));
                    product.addProperty("good_status","published");
                    results.add(product);
                }
                JsonObject response=new JsonObject();
                response.add("results",results);
                return response;
            }
            @Override public JsonElement productCards(String base,String token,String gtins)throws java.io.IOException{
                if(catalogRequests.incrementAndGet()>1){
                    throw new ZnackApiClient.ZnackApiException("Znack API request failed",429,
                            "{\"error_message\":\"Слишком много запросов\"}");
                }
                JsonArray result=new JsonArray();
                if(List.of(gtins.split(";")).contains(missingGtin)){
                    result.add(JsonParser.parseString("""
                            {"good_name":"Recovered name","tnved":"6202300000",
                             "identified_by":[{"type":"gtin","value":"%s"}]}
                            """.formatted(missingGtin)));
                }
                JsonObject response=new JsonObject();
                response.add("result",result);
                return response;
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        new ZnackProductService(api,auth,repository).sync(testedSettings("","","","connection",""));

        Product recovered=repository.findProduct(missingGtin).orElseThrow();
        assertEquals("Recovered name",recovered.productName());
        assertEquals("6202300000",recovered.tnVed());
    }

    @Test void productSyncIgnoresTechnicalGtinsAndDeletesUnreferencedExistingOnes() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        String technical="02900699308808";
        repository.upsertProducts(List.of(new Product(technical,"Old technical",null,null,null,null,null)));
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                return JsonParser.parseString("""
                        {"results":[
                          {"gtin":"02900699308808","productName":"Technical"},
                          {"gtin":"04601234567890","productName":"Orderable"}
                        ]}
                        """);
            }
            @Override public JsonElement productCards(String base,String token,String gtins){
                assertEquals("04601234567890",gtins);
                return JsonParser.parseString("""
                        {"result":[{"good_attrs":[
                        {"attr_id":23557,"certificate_number":"DECL-1","certificate_issued_date":"2026-01-10"}],
                        "identified_by":[{"type":"gtin","value":"04601234567890"}]}]}
                        """);
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        List<Product> products=new ZnackProductService(api,auth,repository)
                .sync(testedSettings("","","","connection",""));

        assertEquals(List.of("04601234567890"),products.stream().map(Product::gtin).toList());
        assertEquals(List.of("04601234567890"),repository.findProducts().stream().map(Product::gtin).toList());
        try(Connection c=Database.getConnection();Statement st=c.createStatement();ResultSet rs=st.executeQuery(
                "SELECT COUNT(*) FROM znack_products WHERE gtin LIKE '029%'")){
            assertTrue(rs.next());
            assertEquals(0,rs.getInt(1));
        }
    }

    @Test void deleteProductRemovesGtinWithMappingsOrdersCodesAndPipelines() {
        ZnackRepository repository=repository(1,"Shop A");
        com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository mappings=
                new com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository();
        String mappedOnly="04601234567890";
        String purchased="04601234567891";
        repository.upsertProducts(List.of(
                new Product(mappedOnly,"Mapped",null,null,null,null,null),
                new Product(purchased,"Purchased",null,null,null,null,null)));
        mappings.replaceRulesForGtin(1,mappedOnly,
                List.of(new com.tuandev.fbsbarcode.features.kizmapping.ZnackGtinMappingSelection("Shoes",null,true)));
        long order=repository.createDraft(purchased,1);
        repository.insertCodes(order,purchased,new DownloadedCodes(
                List.of("010460123456789121abcdefghijklm91ABCD92sig"),"block"));
        repository.createPipeline(purchased,1); // in-flight buy task must not block deletion

        repository.deleteProduct(mappedOnly); // mapped, no purchase
        repository.deleteProduct(purchased);  // mapped + order + codes + pipeline

        assertTrue(repository.findProducts().isEmpty(),"both GTINs removed");
        assertTrue(repository.findOrders().isEmpty(),"orders removed");
        assertTrue(repository.findActivePipelines().isEmpty(),"pipelines removed");
        assertTrue(repository.findCodes(order).isEmpty(),"downloaded codes removed");
        assertTrue(mappings.findRulesForGtin(1,mappedOnly).isEmpty(),"mapping rules removed");
    }

    @Test void productSyncSkipsNonPublishedCardsAndDeletesUnreferencedExistingOnes() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        String nowDraft="04601234567891";
        repository.upsertProducts(List.of(new Product(nowDraft,"Was published before",null,null,null,null,null)));
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                return JsonParser.parseString("""
                        {"results":[
                          {"gtin":"04601234567890","productName":"Published","good_status":"published"},
                          {"gtin":"04601234567891","productName":"Draft now","good_status":"draft"},
                          {"gtin":"04601234567892","productName":"Errors","good_detailed_status":"errors"}
                        ]}
                        """);
            }
            @Override public JsonElement productCards(String base,String token,String gtins){
                return JsonParser.parseString("""
                        {"result":[{"good_attrs":[
                        {"attr_id":23557,"certificate_number":"DECL-1","certificate_issued_date":"2026-01-10"}],
                        "identified_by":[{"type":"gtin","value":"04601234567890"}]}]}
                        """);
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        List<Product> products=new ZnackProductService(api,auth,repository)
                .sync(testedSettings("","","","connection",""));

        assertEquals(List.of("04601234567890"),products.stream().map(Product::gtin).toList());
        assertEquals(List.of("04601234567890"),repository.findProducts().stream().map(Product::gtin).toList());
    }

    @Test void productSyncMovesPublishedGtinWithoutGoodsDocumentsToTrash() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                return JsonParser.parseString("""
                        {"results":[
                          {"gtin":"04601234567890","productName":"Documented","good_status":"published"},
                          {"gtin":"04601234567891","productName":"Missing document","good_status":"published"}
                        ]}
                        """);
            }
            @Override public JsonElement productCards(String base,String token,String gtins){
                return JsonParser.parseString("""
                        {"result":[
                          {"good_attrs":[{"attr_id":23557,"certificate_number":"DECL-1",
                           "certificate_issued_date":"2026-01-10"}],
                           "identified_by":[{"type":"gtin","value":"04601234567890"}]},
                          {"good_attrs":[],
                           "identified_by":[{"type":"gtin","value":"04601234567891"}]}
                        ]}
                        """);
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        List<Product> products=new ZnackProductService(api,auth,repository)
                .sync(testedSettings("","","","connection",""));

        assertEquals(List.of("04601234567890"),products.stream().map(Product::gtin).toList());
        assertEquals(List.of("04601234567891"),
                repository.findDeletedProducts().stream().map(Product::gtin).toList());
    }

    @Test void partialCatalogResponseDoesNotClassifyAnUnreturnedGtinAsMissingDocuments() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement products(String base,String token){
                return JsonParser.parseString("""
                        {"results":[
                          {"gtin":"04601234567890","productName":"Returned card","good_status":"published"},
                          {"gtin":"04601234567891","productName":"Missing card","good_status":"published"}
                        ]}
                        """);
            }
            @Override public JsonElement productCards(String base,String token,String gtins){
                return JsonParser.parseString("""
                        {"result":[
                          {"good_attrs":[{"attr_id":23557,"certificate_number":"DECL-1",
                           "certificate_issued_date":"2026-01-10"}],
                           "identified_by":[{"type":"gtin","value":"04601234567890"}]}
                        ]}
                        """);
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
        };

        List<Product> products=new ZnackProductService(api,auth,repository)
                .sync(testedSettings("","","","connection",""));

        assertEquals(List.of("04601234567890"),products.stream().map(Product::gtin).toList());
        assertTrue(repository.findDeletedProducts().isEmpty());
        assertTrue(repository.findProduct("04601234567891").isEmpty());
    }

    @Test void blankHostsResolveToProductionWithoutReplacingCustomHosts() {
        Settings blank=Settings.empty();
        assertEquals(ZnackModels.PRODUCTION_TRUE_API,blank.resolvedTrueApiBaseUrl());
        assertEquals(ZnackModels.PRODUCTION_SUZ,blank.resolvedSuzBaseUrl());
        Settings custom=new Settings("https://true.example/custom","https://suz.example/custom","","","","","","","","[]","","","",false);
        assertEquals("https://true.example/custom",custom.resolvedTrueApiBaseUrl());
        assertEquals("https://suz.example/custom",custom.resolvedSuzBaseUrl());
    }

    @Test void normalizesTrueApiMethodPaths() {
        String base=ZnackModels.PRODUCTION_TRUE_API;
        assertEquals("https://markirovka.crpt.ru",ZnackApiClient.apiRoot(base));
        assertEquals("https://markirovka.crpt.ru/api/v3/true-api",ZnackApiClient.authBase(base));
        assertEquals("https://markirovka.crpt.ru/api/v4/true-api",ZnackApiClient.trueApiBase(base,4));
        assertEquals(ZnackModels.PRODUCTION_NATIONAL_CATALOG,ZnackApiClient.nationalCatalogBase(base));
        assertEquals(ZnackModels.SANDBOX_NATIONAL_CATALOG,
                ZnackApiClient.nationalCatalogBase("https://markirovka.sandbox.crptech.ru/api/v3/true-api"));
    }

    @Test void permitLookupUsesNationalCatalogContractAndBearerToken() throws Exception {
        AtomicReference<String> body=new AtomicReference<>(),authorization=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/v4/rd-info-by-gtin",exchange->{
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange,"{\"result\":{\"documents\":[]}}");
        });
        server.start();
        try{
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            new ZnackApiClient().permitDocuments(base,"true-api-token","04601234567890","7701234567");
            assertEquals("Bearer true-api-token",authorization.get());
            JsonObject request=JsonParser.parseString(body.get()).getAsJsonObject();
            assertEquals("04601234567890",request.get("gtin").getAsString());
            assertEquals("7701234567",request.get("inn").getAsString());
        }finally{server.stop(0);}
    }

    @Test void trueAndSuzAuthenticationUseTrueApiHostOmitBlankInnAndDeriveParticipant() throws Exception {
        AtomicReference<String> signInPath=new AtomicReference<>(),requestBody=new AtomicReference<>();
        String jwt=jwtWithInn("7701234567");
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/api/v3/true-api/auth/key",exchange->respond(exchange,"{\"uuid\":\"u\",\"data\":\"challenge\"}"));
        server.createContext("/api/v3/true-api/auth/simpleSignIn",exchange->{signInPath.set(exchange.getRequestURI().toString());requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));respond(exchange,"{\"token\":\""+jwt+"\"}");});
        server.start();
        try {
            String trueBase="http://127.0.0.1:"+server.getAddress().getPort()+"/api/v3/true-api";
            Settings settings=testedSettings(trueBase,"http://unused.example","","connection","");
            ZnackAuthService auth=new ZnackAuthService(new ZnackApiClient(),testSigner());
            auth.trueApiToken(settings);
            auth.suzToken(settings);
            assertEquals("/api/v3/true-api/auth/simpleSignIn/connection",signInPath.get());
            assertFalse(requestBody.get().contains("\"inn\""));
            assertEquals("7701234567",auth.authenticatedParticipantInn());
            assertEquals("7701234567",auth.resolvedParticipantInn(settings));
            assertEquals("781234567890",ZnackAuthService.certificateInn("{\"inn\":\"781234567890\"}"));
            Settings override=testedSettings(trueBase,"","","","781234567890");
            assertEquals("781234567890",auth.resolvedParticipantInn(override));
        } finally { server.stop(0); }
    }

    @Test void ownProductionIntroductionPrefersActiveDeclarationAndParticipantFallbacks() throws Exception {
        ZnackRepository repository=repository(1, "Shop A");
        Product product=new Product("04601234567890","Product","6201000000",null,null,null,null);
        repository.upsertProducts(List.of(product));
        long orderId=repository.createDraft("04601234567890",1);
        String normalizedCis="010460123456789021abcdefghijklm";
        repository.insertCodes(orderId,"04601234567890",new DownloadedCodes(
                List.of(normalizedCis+"\u001D91ABCD\u001D92signature"),"block"));
        AtomicReference<JsonObject> request=new AtomicReference<>();
        AtomicReference<String> registryGtin=new AtomicReference<>(),registryInn=new AtomicReference<>();
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement permitDocuments(String base,String token,String gtin,String inn){
                registryGtin.set(gtin);registryInn.set(inn);
                return JsonParser.parseString("""
                        {"result":{"documents":[
                          {"attr_id":23557,"number":"DECLARATION-1","from_date":"2026-01-10","status_group":1},
                          {"attr_id":23561,"number":"CERTIFICATE-2","from_date":"2026-02-11","status":"Действует"},
                          {"attr_id":23557,"number":"SUSPENDED","from_date":"2025-01-01","status_group":2}
                        ]}}
                        """);
            }
            @Override public String createDocument(String base,String token,JsonObject body){request.set(body);return "doc";}
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
            @Override public String resolvedParticipantInn(Settings s){return "7701234567";}
        };
        Settings settings=settingsWithDocument("",false,"DEFAULT-MUST-NOT-BE-USED","20.06.2024","16.06.2029");
        new ZnackIntroductionService(api,auth,testSigner(),repository).submit(settings,repository.findOrder(orderId).orElseThrow(),product,repository.findCodes(orderId));
        String encoded=request.get().get("product_document").getAsString();
        JsonObject payload=JsonParser.parseString(new String(java.util.Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("7701234567",payload.get("participant_inn").getAsString());
        assertEquals("7701234567",payload.get("producer_inn").getAsString());
        assertEquals("7701234567",payload.get("owner_inn").getAsString());
        assertEquals("OWN_PRODUCTION",payload.get("production_type").getAsString());
        assertEquals(normalizedCis,payload.getAsJsonArray("products").get(0).getAsJsonObject().get("uit_code").getAsString());
        assertTrue(payload.getAsJsonArray("products").get(0).getAsJsonObject().has("tnved_code"));
        JsonArray certificates=payload.getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonArray("certificate_document_data");
        assertEquals(List.of("DECLARATION-1"),certificates.asList().stream()
                .map(JsonElement::getAsJsonObject).map(c->c.get("certificate_number").getAsString()).toList());
        assertEquals(List.of("CONFORMITY_DECLARATION"),certificates.asList().stream()
                .map(JsonElement::getAsJsonObject).map(c->c.get("certificate_type").getAsString()).toList());
        assertEquals(List.of("2026-01-10"),certificates.asList().stream()
                .map(JsonElement::getAsJsonObject).map(c->c.get("certificate_date").getAsString()).toList());
        assertTrue(certificates.asList().stream().noneMatch(c->c.getAsJsonObject().has("certificate_expiration_date")));
        assertEquals("04601234567890",registryGtin.get());
        assertEquals("",registryInn.get());
        assertEquals(List.of("DECLARATION-1"),repository.findProduct(product.gtin()).orElseThrow()
                .permitDocuments().stream().map(GoodsDocument::number).toList());
        assertEquals("doc",repository.findLatestDocument(orderId).orElseThrow().externalDocumentId());
    }

    @Test void ownProductionIntroductionFallsBackToActiveConformityCertificate() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        Product product=new Product("04601234567890","Product","6201000000",null,null,null,null);
        repository.upsertProducts(List.of(product));
        long orderId=repository.createDraft(product.gtin(),1);
        repository.insertCodes(orderId,product.gtin(),new DownloadedCodes(List.of(
                "010460123456789021abcdefghijklm\u001D91ABCD\u001D92signature"),"block"));
        AtomicReference<JsonObject> request=new AtomicReference<>();
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement permitDocuments(String base,String token,String gtin,String inn){
                return JsonParser.parseString("""
                        {"result":{"documents":[
                          {"attr_id":23561,"number":"CERTIFICATE-ONLY","from_date":"2026-03-11","status_group":1}
                        ]}}
                        """);
            }
            @Override public String createDocument(String base,String token,JsonObject body){request.set(body);return "doc";}
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
            @Override public String resolvedParticipantInn(Settings s){return "7701234567";}
        };

        new ZnackIntroductionService(api,auth,testSigner(),repository).submit(
                settingsWithDocument("",false,"LEGACY-MUST-NOT-BE-USED","20.06.2024","16.06.2029"),
                repository.findOrder(orderId).orElseThrow(),product,repository.findCodes(orderId));

        String encoded=request.get().get("product_document").getAsString();
        JsonObject payload=JsonParser.parseString(new String(java.util.Base64.getDecoder().decode(encoded),
                StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject certificate=payload.getAsJsonArray("products").get(0).getAsJsonObject()
                .getAsJsonArray("certificate_document_data").get(0).getAsJsonObject();
        assertEquals("CONFORMITY_CERTIFICATE",certificate.get("certificate_type").getAsString());
        assertEquals("CERTIFICATE-ONLY",certificate.get("certificate_number").getAsString());
        assertEquals("2026-03-11",certificate.get("certificate_date").getAsString());
        assertEquals(List.of(new GoodsDocument("CONFORMITY_CERTIFICATE","CERTIFICATE-ONLY","2026-03-11")),
                repository.findProduct(product.gtin()).orElseThrow().permitDocuments());
    }

    @Test void inactiveGtinDocumentsBlockSubmissionClearStaleSnapshotAndIgnoreLegacyDefault() {
        ZnackRepository repository=repository(1,"Shop A");
        Product product=new Product("04601234567890","Product","6201000000",null,null,null,null);
        repository.upsertProducts(List.of(product));
        repository.updateProductDocuments(product.gtin(),List.of(
                new GoodsDocument("CONFORMITY_DECLARATION","STALE","2025-01-01")));
        long orderId=repository.createDraft(product.gtin(),1);
        repository.insertCodes(orderId,product.gtin(),new DownloadedCodes(List.of("inactive-document-code"),"block"));
        AtomicInteger createCalls=new AtomicInteger();
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement permitDocuments(String base,String token,String gtin,String inn){
                return JsonParser.parseString("""
                        {"result":{"documents":[{"attr_id":23557,"number":"SUSPENDED",
                        "from_date":"2025-01-01","status_group":2}]}}
                        """);
            }
            @Override public String createDocument(String base,String token,JsonObject body){
                createCalls.incrementAndGet();return "must-not-submit";
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
            @Override public String resolvedParticipantInn(Settings s){return "7701234567";}
        };

        assertThrows(ZnackIntroductionService.PermitDocumentsUnavailableException.class,
                ()->new ZnackIntroductionService(api,auth,testSigner(),repository).submit(
                        settingsWithDocument("",true,"LEGACY-DEFAULT","20.06.2024","20.06.2029"),
                        repository.findOrder(orderId).orElseThrow(),product,repository.findCodes(orderId)));

        assertEquals(0,createCalls.get());
        assertTrue(repository.findLatestDocument(orderId).isEmpty());
        assertTrue(repository.findProduct(product.gtin()).orElseThrow().permitDocuments().isEmpty());
    }

    @Test void pendingRegistryLookupRetriesWithoutClearingTheLastDocumentSnapshot() {
        ZnackRepository repository=repository(1,"Shop A");
        Product product=new Product("04601234567890","Product","6201000000",null,null,null,null);
        repository.upsertProducts(List.of(product));
        List<GoodsDocument> snapshot=List.of(
                new GoodsDocument("CONFORMITY_CERTIFICATE","CERTIFICATE-1","2026-01-10"));
        repository.updateProductDocuments(product.gtin(),snapshot);
        long orderId=repository.createDraft(product.gtin(),1);
        repository.insertCodes(orderId,product.gtin(),new DownloadedCodes(List.of("pending-registry-code"),"block"));
        AtomicInteger createCalls=new AtomicInteger();
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement permitDocuments(String base,String token,String gtin,String inn){
                return JsonParser.parseString("""
                        {"result":{"documents":[],"errors":[{"code":"18",
                        "message":"Поиск сведений инициирован"}]}}
                        """);
            }
            @Override public String createDocument(String base,String token,JsonObject body){
                createCalls.incrementAndGet();return "must-not-submit";
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
            @Override public String resolvedParticipantInn(Settings s){return "7701234567";}
        };

        ZnackIntroductionService.PermitDocumentsUnavailableException error=assertThrows(
                ZnackIntroductionService.PermitDocumentsUnavailableException.class,
                ()->new ZnackIntroductionService(api,auth,testSigner(),repository).submit(
                        settingsWithDocument("",true,"","",""),
                        repository.findOrder(orderId).orElseThrow(),product,repository.findCodes(orderId)));

        assertFalse(error.confirmedMissing());
        assertEquals(0,createCalls.get());
        assertEquals(snapshot,repository.findProduct(product.gtin()).orElseThrow().permitDocuments());
    }

    @Test void definitiveIntroductionApiRejectionIsNotMarkedAsAmbiguous() {
        ZnackRepository repository=repository(1,"Shop A");
        long orderId=orderWithCodes(repository);
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement permitDocuments(String base,String token,String gtin,String inn){
                return JsonParser.parseString("""
                        {"result":{"documents":[{"attr_id":23557,"number":"DOC-1",
                        "from_date":"2026-01-10","status_group":1}]}}
                        """);
            }
            @Override public String createDocument(String base,String token,JsonObject body)throws java.io.IOException{
                throw new ZnackApiClient.ZnackApiException("Znack API request failed",422,
                        "{\"error\":\"document unavailable\"}");
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,testSigner()){
            @Override public String trueApiToken(Settings s){return "token";}
            @Override public String resolvedParticipantInn(Settings s){return "7701234567";}
        };

        assertThrows(ZnackApiClient.ZnackApiException.class,()->new ZnackIntroductionService(
                api,auth,testSigner(),repository).submit(
                settingsWithDocument("",true,"DOC-1","20.06.2024","20.06.2029"),
                repository.findOrder(orderId).orElseThrow(),repository.findProducts().getFirst(),
                repository.findCodes(orderId)));

        assertEquals("REJECTED",repository.findLatestDocument(orderId).orElseThrow().status());
    }

    @Test void introductionSigningFailureDoesNotCreateBlockingDocument() {
        ZnackRepository repository=repository(1,"Shop A");
        long orderId=orderWithCodes(repository);
        ZnackSignatureProvider failingSigner=(input,context)->{
            throw new CryptoProException(CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT,"token unavailable");
        };
        ZnackApiClient api=new ZnackApiClient(){
            @Override public JsonElement permitDocuments(String base,String token,String gtin,String inn){
                return JsonParser.parseString("""
                        {"result":{"documents":[{"attr_id":23557,"number":"DOC-1",
                        "from_date":"2026-01-10","status_group":1}]}}
                        """);
            }
        };
        ZnackAuthService auth=new ZnackAuthService(api,failingSigner){
            @Override public String trueApiToken(Settings s){return "token";}
            @Override public String resolvedParticipantInn(Settings s){return "7701234567";}
        };

        assertThrows(CryptoProException.class,()->new ZnackIntroductionService(
                api,auth,failingSigner,repository).submit(
                settingsWithDocument("",true,"DOC-1","20.06.2024","20.06.2029"),
                repository.findOrder(orderId).orElseThrow(),repository.findProducts().getFirst(),
                repository.findCodes(orderId)));
        assertTrue(repository.findLatestDocument(orderId).isEmpty());
    }

    @Test void znackTranslationsIncludeRequiredWorkflowKeys() {
        for(Locale locale:List.of(Locale.ENGLISH,Locale.forLanguageTag("ru"),Locale.forLanguageTag("vi"),Locale.CHINESE)){
            ResourceBundle bundle=ResourceBundle.getBundle("com.tuandev.fbsbarcode.i18n.messages",locale);
            assertFalse(bundle.getString("znack.settings.basic").isBlank());
            assertFalse(bundle.getString("znack.save_authenticate").isBlank());
            assertFalse(bundle.getString("common.help").isBlank());
            assertFalse(bundle.getString("znack.help.oms_id.steps").isBlank());
            assertFalse(bundle.getString("znack.help.oms_connection.steps").isBlank());
            assertFalse(bundle.getString("znack.help.oms_id.warning").isBlank());
            assertFalse(bundle.getString("znack.help.oms_connection.warning").isBlank());
            assertFalse(bundle.getString("znack.signature.error.cryptcp_license").isBlank());
            assertFalse(bundle.getString("znack.signature.error.details").isBlank());
            assertFalse(bundle.getString("common.copy").isBlank());
            assertFalse(bundle.getString("supply.gtin_inventory.title").isBlank());
            assertFalse(bundle.getString("supply.gtin_inventory.buy_title").isBlank());
            assertFalse(bundle.getString("supply.gtin_inventory.error.pipeline_active").isBlank());
            assertFalse(bundle.getString("znack.document_type").isBlank());
            assertFalse(bundle.getString("znack.document_type.conformity_declaration").isBlank());
            assertFalse(bundle.getString("kiz_mapping.mapping.summary").isBlank());
            assertFalse(bundle.getString("znack.status_value.waiting_introduction_readiness").isBlank());
            assertFalse(bundle.getString("znack.insufficient_funds.title").isBlank());
            assertFalse(bundle.getString("znack.insufficient_funds.header").isBlank());
            assertFalse(bundle.getString("znack.insufficient_funds.content").isBlank());
            assertFalse(bundle.getString("znack.insufficient_funds.retry").isBlank());
            assertFalse(bundle.getString("znack.operator_terms.title").isBlank());
            assertFalse(bundle.getString("znack.operator_terms.header").isBlank());
            assertFalse(bundle.getString("znack.operator_terms.content").isBlank());
            assertFalse(bundle.getString("znack.missing_documents.title").isBlank());
            assertFalse(bundle.getString("znack.missing_documents.header").isBlank());
            assertFalse(bundle.getString("znack.missing_documents.intro").isBlank());
            assertFalse(bundle.getString("znack.missing_documents.action").isBlank());
        }
        assertEquals("Получите omsConnection в СУЗ: Управление заказами → Устройства → Идентификатор соединения.",
                ResourceBundle.getBundle("com.tuandev.fbsbarcode.i18n.messages",Locale.forLanguageTag("ru")).getString("znack.oms_connection_help"));
        assertEquals("Không tìm thấy chữ ký điện tử. Vui lòng cắm USB token, kiểm tra CryptoPro rồi thử lại.",
                ResourceBundle.getBundle("com.tuandev.fbsbarcode.i18n.messages",Locale.forLanguageTag("vi")).getString("znack.signature.not_found"));
        assertEquals("Đang chờ lưu thông",
                ResourceBundle.getBundle("com.tuandev.fbsbarcode.i18n.messages", Locale.forLanguageTag("vi"))
                        .getString("znack.status_value.waiting_introduction_readiness"));
    }

    @Test void sanitizesNestedErrorDetailsBeforeLogging() {
        String diagnostic = ZnackSanitizer.error(new RuntimeException("Signing failed",
                new IllegalStateException("token=secret-value pin=1234")));

        assertTrue(diagnostic.contains("Signing failed"));
        assertTrue(diagnostic.contains("[REDACTED]"));
        assertFalse(diagnostic.contains("secret-value"));
        assertFalse(diagnostic.contains("1234"));
    }

    @Test void mapsBufferStatuses() {
        assertEquals(OrderStatus.WAITING_CODES,new BufferStatus("PENDING",0,false,null).localStatus());
        assertEquals(OrderStatus.WAITING_CODES,new BufferStatus("PENDING",1,false,null).localStatus());
        assertEquals(OrderStatus.CODES_READY,new BufferStatus("ACTIVE",1,false,null).localStatus());
        assertEquals(OrderStatus.CODES_READY,new BufferStatus("READY",1,false,null).localStatus());
        assertEquals(OrderStatus.FAILED,new BufferStatus("REJECTED",0,true,"bad").localStatus());
    }

    @Test void defaultGoodsDocumentsAreShopScopedAndDatesAreStrict() {
        ZnackRepository a=repository(1,"Shop A"),b=repository(2,"Shop B");
        Settings aSettings=settingsWithDocument(temp.toString(),true,"ЕАЭС N RU Д-TR.РА05.В.15176/24","20.06.2024","16.06.2029");
        a.saveSettings(aSettings);b.saveSettings(Settings.empty());
        assertEquals("ЕАЭС N RU Д-TR.РА05.В.15176/24",a.getSettings().documentNumber());
        assertEquals("16.06.2029",a.getSettings().documentExpiryDate());
        assertEquals("CONFORMITY_DECLARATION",a.getSettings().documentType());
        assertEquals("CONFORMITY_DECLARATION",Settings.empty().documentType());
        assertTrue(b.getSettings().documentNumber().isBlank());
        assertTrue(aSettings.hasDefaultGoodsDocument());
        assertDoesNotThrow(Settings.empty()::validateDefaultGoodsDocument);
        assertDoesNotThrow(aSettings::validateGoodsDocumentDates);
        assertThrows(IllegalArgumentException.class,()->settingsWithDocument("",true,"doc","00.00.0000","16.06.2029").validateGoodsDocumentDates());
        assertThrows(IllegalArgumentException.class,()->settingsWithDocument("",true,"doc","2024-06-20","16.06.2029").validateGoodsDocumentDates());
        assertDoesNotThrow(()->settingsWithDocument("",true,"doc","20.06.2029","not-used").validateGoodsDocumentDates());
    }

    @Test void legacySettingsAndFlatGtinFieldsAreNeverPromotedToRegistryDocuments() {
        Settings defaults=settingsWithDocument("",true,"DEFAULT","20.06.2024","");
        Product inherited=new Product("04601234567890","Product","6201000000",null,null,null,null);
        Product overridden=new Product("04601234567890","Product","6201000000","CONFORMITY_CERTIFICATE",
                "OVERRIDE","21.06.2024",null);
        Product partial=new Product("04601234567890","Product","6201000000",null,"PARTIAL",null,null);

        assertTrue(defaults.hasDefaultGoodsDocument());
        assertTrue(inherited.permitDocuments().isEmpty());
        assertTrue(overridden.permitDocuments().isEmpty());
        assertTrue(partial.permitDocuments().isEmpty());
    }

    @Test void introductionColumnsAreAddedIdempotentlyWithoutRemovingExistingData() throws Exception {
        ZnackRepository repository=repository(1,"Shop A");
        repository.upsertProducts(List.of(new Product("04601234567890","Product",null,null,null,null,null)));
        try(Connection c=Database.getConnection()){
            ZnackSchemaSupport.initialize(c);
            ZnackSchemaSupport.initialize(c);
        }
        try(Connection c=Database.getConnection();Statement st=c.createStatement()){
            assertTrue(hasColumn(st,"znack_settings","document_type"));
            assertTrue(hasColumn(st,"znack_products","good_mark_flag"));
            assertTrue(hasColumn(st,"znack_products","good_turn_flag"));
            assertTrue(hasColumn(st,"znack_products","readiness_checked_at"));
            assertTrue(hasColumn(st,"znack_products","cis_type"));
            assertTrue(hasColumn(st,"znack_products","permit_documents_json"));
        }
        assertEquals("Product",repository.findProducts().getFirst().productName());
    }

    private static String jwtWithInn(String inn){
        return "header."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"participant_inn\":\""+inn+"\"}").getBytes(StandardCharsets.UTF_8))+".signature";
    }

    private static ZnackRepository repository(int id, String name) {
        return new ZnackRepository(new ShopContext(id, name));
    }

    private static Settings testedSettings(String trueBase, String suzBase, String omsId, String connection, String participantInn) {
        return new Settings(trueBase,suzBase,omsId,connection,participantInn,"","","signer","certificate","[]",
                "","","",false,"","[]","certificate",java.time.Instant.now());
    }

    private static ZnackSignatureProvider testSigner() {
        return (input, context) -> new CryptoProSigningResult(new byte[]{0x30, 0x02, 0x01, 0x00}, "");
    }

    private static Settings settingsWithDocument(String pdfFolder,boolean auto,String number,String issue,String expiry) {
        return new Settings("","","oms","connection","","","","","certificate","[]",number,issue,pdfFolder,auto,
                "","[]","certificate",java.time.Instant.now(),"","","",60,expiry,"CONFORMITY_DECLARATION");
    }

    private static long orderWithCodes(ZnackRepository repository) {
        Product product=new Product("04601234567890","Product","6201000000",null,null,null,null);
        repository.upsertProducts(List.of(product));
        long order=repository.createDraft(product.gtin(),1);
        repository.insertCodes(order,product.gtin(),new DownloadedCodes(
                List.of("010460123456789021abcdefghijklm\u001D91ABCD\u001D92signature"),"block"));
        return order;
    }

    private static boolean hasColumn(Statement statement,String table,String column)throws java.sql.SQLException{
        try(ResultSet rs=statement.executeQuery("PRAGMA table_info("+table+")")){
            while(rs.next())if(column.equalsIgnoreCase(rs.getString("name")))return true;
            return false;
        }
    }

    private static ZnackIntroductionService introductionCounter(ZnackRepository repository,AtomicInteger count) {
        return new ZnackIntroductionService(new ZnackApiClient(),new ZnackAuthService(new ZnackApiClient(),testSigner()),testSigner(),repository){
            @Override public long submit(Settings s,KizOrder order,Product product,List<KizCode> codes){count.incrementAndGet();return 1;}
        };
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,String value)throws java.io.IOException{
        byte[] body=value.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
    }
}
