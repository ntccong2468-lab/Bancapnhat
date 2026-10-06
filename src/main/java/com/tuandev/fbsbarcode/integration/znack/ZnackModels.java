package com.tuandev.fbsbarcode.integration.znack;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

public final class ZnackModels {
    public static final String PRODUCTION_TRUE_API = "https://markirovka.crpt.ru/api/v3/true-api";
    public static final String PRODUCTION_SUZ = "https://suzgrid.crpt.ru";
    public static final String PRODUCTION_NATIONAL_CATALOG =
            "https://xn--80aqu.xn----7sbabas4ajkhfocclk9d3cvfsa.xn--p1ai";
    public static final String SANDBOX_NATIONAL_CATALOG = "https://api.nk.sandbox.crptech.ru";

    private ZnackModels() {
    }

    public enum OrderStatus {
        DRAFT, SUBMITTED, WAITING_CODES, CODES_READY, CODES_DOWNLOADED, PDF_GENERATED,
        INTRODUCTION_SKIPPED_MISSING_DOCUMENTS, INTRODUCTION_SKIPPED_MISSING_METADATA,
        WAITING_INTRODUCTION_READINESS, WAITING_INTRODUCTION_DOCUMENTS,
        INTRO_SENT, INTRODUCED, INTRODUCTION_FAILED, FAILED, CANCELLED
    }

    public enum KizInventoryStatus {
        AVAILABLE, RESERVED, CONSUMED, ARCHIVED
    }

    public enum KizLegalStatus {
        RECEIVED, PRINTED, INTRO_SENT, IN_CIRCULATION
    }

    public enum PurchaseStage {
        QUEUED, VALIDATING, CREATING_ORDER, RECONCILING_ORDER, POLLING_ORDER, DOWNLOADING_CODES,
        INTRODUCTION_SKIPPED_MISSING_DOCUMENTS, INTRODUCTION_SKIPPED_MISSING_METADATA,
        WAITING_INTRODUCTION_READINESS, WAITING_INTRODUCTION_DOCUMENTS,
        SUBMITTING_INTRODUCTION, POLLING_INTRODUCTION,
        INTRODUCTION_FAILED, INTRODUCED, COMPLETED, FAILED
    }

    public record ShopContext(int shopId, String shopName) {
        public ShopContext {
            if (shopId <= 0) throw new IllegalArgumentException("A persisted shop is required.");
            shopName = shopName == null ? "" : shopName;
        }
    }

    public record Settings(String trueApiBaseUrl, String suzBaseUrl, String omsId, String omsConnection,
                           String participantInn, String producerInn, String ownerInn, String signerExecutable,
                           String signerCertificate, String signerArgumentsJson, String documentNumber,
                           String documentDate, String pdfFolder, boolean autoIntroduction,
                           String certificateListExecutable, String certificateListArgumentsJson,
                           String certificateMetadataJson, Instant signerTestedAt,
                           String certmgrPath, String cryptcpPath, String csptestPath, int cryptoProTimeoutSeconds,
                           String documentExpiryDate, String documentType) {
        public static final String DEFAULT_DOCUMENT_TYPE = "CONFORMITY_DECLARATION";
        private static final DateTimeFormatter GOODS_DOCUMENT_DATE =
                DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);

        public Settings {
            documentType = blank(documentType) ? DEFAULT_DOCUMENT_TYPE : documentType.trim();
        }

        public Settings(String trueApiBaseUrl, String suzBaseUrl, String omsId, String omsConnection,
                        String participantInn, String producerInn, String ownerInn, String signerExecutable,
                        String signerCertificate, String signerArgumentsJson, String documentNumber,
                        String documentDate, String pdfFolder, boolean autoIntroduction) {
            this(trueApiBaseUrl, suzBaseUrl, omsId, omsConnection, participantInn, producerInn, ownerInn,
                    signerExecutable, signerCertificate, signerArgumentsJson, documentNumber, documentDate,
                    pdfFolder, autoIntroduction, "", "[]", "", null, "", "", "", 60, "", "");
        }

        public Settings(String trueApiBaseUrl, String suzBaseUrl, String omsId, String omsConnection,
                        String participantInn, String producerInn, String ownerInn, String signerExecutable,
                        String signerCertificate, String signerArgumentsJson, String documentNumber,
                        String documentDate, String pdfFolder, boolean autoIntroduction,
                        String certificateListExecutable, String certificateListArgumentsJson,
                        String certificateMetadataJson, Instant signerTestedAt) {
            this(trueApiBaseUrl, suzBaseUrl, omsId, omsConnection, participantInn, producerInn, ownerInn,
                    signerExecutable, signerCertificate, signerArgumentsJson, documentNumber, documentDate,
                    pdfFolder, autoIntroduction, certificateListExecutable, certificateListArgumentsJson,
                    certificateMetadataJson, signerTestedAt, "", "", "", 60, "", "");
        }

        public Settings(String trueApiBaseUrl, String suzBaseUrl, String omsId, String omsConnection,
                        String participantInn, String producerInn, String ownerInn, String signerExecutable,
                        String signerCertificate, String signerArgumentsJson, String documentNumber,
                        String documentDate, String pdfFolder, boolean autoIntroduction,
                        String certificateListExecutable, String certificateListArgumentsJson,
                        String certificateMetadataJson, Instant signerTestedAt,
                        String certmgrPath, String cryptcpPath, String csptestPath, int cryptoProTimeoutSeconds) {
            this(trueApiBaseUrl, suzBaseUrl, omsId, omsConnection, participantInn, producerInn, ownerInn,
                    signerExecutable, signerCertificate, signerArgumentsJson, documentNumber, documentDate,
                    pdfFolder, autoIntroduction, certificateListExecutable, certificateListArgumentsJson,
                    certificateMetadataJson, signerTestedAt, certmgrPath, cryptcpPath, csptestPath,
                    cryptoProTimeoutSeconds, "", "");
        }

        public Settings(String trueApiBaseUrl, String suzBaseUrl, String omsId, String omsConnection,
                        String participantInn, String producerInn, String ownerInn, String signerExecutable,
                        String signerCertificate, String signerArgumentsJson, String documentNumber,
                        String documentDate, String pdfFolder, boolean autoIntroduction,
                        String certificateListExecutable, String certificateListArgumentsJson,
                        String certificateMetadataJson, Instant signerTestedAt,
                        String certmgrPath, String cryptcpPath, String csptestPath, int cryptoProTimeoutSeconds,
                        String documentExpiryDate) {
            this(trueApiBaseUrl, suzBaseUrl, omsId, omsConnection, participantInn, producerInn, ownerInn,
                    signerExecutable, signerCertificate, signerArgumentsJson, documentNumber, documentDate,
                    pdfFolder, autoIntroduction, certificateListExecutable, certificateListArgumentsJson,
                    certificateMetadataJson, signerTestedAt, certmgrPath, cryptcpPath, csptestPath,
                    cryptoProTimeoutSeconds, documentExpiryDate, "");
        }

        public static Settings empty() {
            return new Settings("", "", "", "", "", "", "", "", "", "[]", "", "", "", false,
                    "", "[]", "", null, "", "", "", 60, "", "");
        }

        public String resolvedTrueApiBaseUrl() {
            return trueApiBaseUrl == null || trueApiBaseUrl.isBlank() ? PRODUCTION_TRUE_API : trueApiBaseUrl.trim();
        }

        public String resolvedSuzBaseUrl() {
            return suzBaseUrl == null || suzBaseUrl.isBlank() ? PRODUCTION_SUZ : suzBaseUrl.trim();
        }

        public int resolvedCryptoProTimeoutSeconds() {
            return cryptoProTimeoutSeconds <= 0 ? 60 : Math.min(cryptoProTimeoutSeconds, 600);
        }

        public Settings withDefaultGoodsDocument(String type, String number, String date) {
            return new Settings(trueApiBaseUrl, suzBaseUrl, omsId, omsConnection, participantInn, producerInn,
                    ownerInn, signerExecutable, signerCertificate, signerArgumentsJson, number, date, pdfFolder,
                    autoIntroduction, certificateListExecutable, certificateListArgumentsJson,
                    certificateMetadataJson, signerTestedAt, certmgrPath, cryptcpPath, csptestPath,
                    cryptoProTimeoutSeconds, documentExpiryDate, type);
        }

        public boolean hasDefaultGoodsDocument() {
            return defaultGoodsDocument().complete();
        }

        public GoodsDocument defaultGoodsDocument() {
            return new GoodsDocument(documentType, documentNumber, documentDate);
        }

        public void validateGoodsDocumentDates() {
            validateGoodsDocumentDate(documentDate, "Document issue date");
        }

        public void validateDefaultGoodsDocument() {
            validateGoodsDocumentDates();
            GoodsDocument document = defaultGoodsDocument();
            if ((!blank(documentNumber) || !blank(documentDate)) && !document.complete()) {
                throw new IllegalArgumentException("Missing " + document.missingFields() + ".");
            }
        }

        public static void validateGoodsDocumentDate(String value, String field) {
            if (blank(value)) return;
            try { parseDate(value); }
            catch (DateTimeParseException e) {
                throw new IllegalArgumentException(field + " must use dd.MM.yyyy format.");
            }
        }

        private static LocalDate parseDate(String value) {
            return LocalDate.parse(value.trim(), GOODS_DOCUMENT_DATE);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    public record Product(String gtin, String productName, String tnVed, String certificateType,
                          String certificateNumber, String certificateDate, String productionDate,
                          Boolean goodMarkFlag, Boolean goodTurnFlag, String cardStatus,
                          String cardDetailedStatus, String category, Instant readinessCheckedAt,
                          String cisType, List<GoodsDocument> permitDocuments) {
        public Product {
            cisType = cisType == null || cisType.isBlank()
                    ? null
                    : cisType.trim().toUpperCase(java.util.Locale.ROOT);
            permitDocuments = permitDocuments == null ? List.of() : List.copyOf(permitDocuments);
        }

        public Product(String gtin, String productName, String tnVed, String certificateType,
                       String certificateNumber, String certificateDate, String productionDate) {
            this(gtin, productName, tnVed, certificateType, certificateNumber, certificateDate, productionDate,
                    null, null, "", "", "", null, null, List.of());
        }

        public Product(String gtin, String productName, String tnVed, String certificateType,
                       String certificateNumber, String certificateDate, String productionDate,
                       Boolean goodMarkFlag, Boolean goodTurnFlag, String cardStatus,
                       String cardDetailedStatus, String category, Instant readinessCheckedAt) {
            this(gtin, productName, tnVed, certificateType, certificateNumber, certificateDate, productionDate,
                    goodMarkFlag, goodTurnFlag, cardStatus, cardDetailedStatus, category, readinessCheckedAt, null,
                    List.of());
        }

        public Product(String gtin, String productName, String tnVed, String certificateType,
                       String certificateNumber, String certificateDate, String productionDate,
                       Boolean goodMarkFlag, Boolean goodTurnFlag, String cardStatus,
                       String cardDetailedStatus, String category, Instant readinessCheckedAt,
                       String cisType) {
            this(gtin, productName, tnVed, certificateType, certificateNumber, certificateDate, productionDate,
                    goodMarkFlag, goodTurnFlag, cardStatus, cardDetailedStatus, category, readinessCheckedAt,
                    cisType, List.of());
        }

        public boolean cardReadyForIntroduction() {
            return Boolean.TRUE.equals(goodMarkFlag) && Boolean.TRUE.equals(goodTurnFlag);
        }
    }

    public record GoodsDocument(String type, String number, String date) {
        public boolean anyValue() {
            return !blank(type) || !blank(number) || !blank(date);
        }

        public boolean complete() {
            return !blank(type) && !blank(number) && !blank(date);
        }

        public String missingFields() {
            List<String> missing = new java.util.ArrayList<>();
            if (blank(type)) missing.add("document type");
            if (blank(number)) missing.add("document number");
            if (blank(date)) missing.add("document issue date");
            return String.join(", ", missing);
        }
    }

    public record KizOrder(long id, String externalOrderId, String gtin, int quantity, String remoteStatus,
                           OrderStatus localStatus, String errorMessage, Instant createdAt, Instant updatedAt) {
    }

    public record KizCode(long id, long orderId, String rawCode, String displayCode, String gtin, String blockId,
                          String pdfPath, Long documentId, KizInventoryStatus inventoryStatus,
                          KizLegalStatus legalStatus) {
    }

    public record Document(long id, long orderId, String payloadJson, String externalDocumentId, String status,
                           String errorMessage) {
    }

    public record OperationLog(long id, int shopId, String shopName, String action, String entityReference,
                               String severity, String message, Integer httpStatus, Instant createdAt) {
    }

    public record BufferStatus(String remoteStatus, int availableCodes, boolean rejected, String rejectionReason) {
        public OrderStatus localStatus() {
            if (rejected || "DECLINED".equalsIgnoreCase(remoteStatus) || "REJECTED".equalsIgnoreCase(remoteStatus)) {
                return OrderStatus.FAILED;
            }
            // SUZ may expose a positive availableCodes count while the buffer itself is still
            // PENDING. The /codes endpoint rejects that race with business error 3390, so the
            // buffer status remains the authoritative readiness signal.
            if ("ACTIVE".equalsIgnoreCase(remoteStatus) || "READY".equalsIgnoreCase(remoteStatus)) {
                return OrderStatus.CODES_READY;
            }
            return OrderStatus.WAITING_CODES;
        }
    }

    public record DownloadedCodes(List<String> codes, String blockId) {
        public DownloadedCodes {
            codes = codes == null ? List.of() : List.copyOf(codes);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
