package com.vncode.app.integration.znack;

import com.vncode.app.integration.znack.ZnackModels.Product;
import com.vncode.app.integration.znack.ZnackModels.Settings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable remote-account, method and group identity for one approved purchase. */
public record ZnackPurchaseContext(Settings settings, ZnackProductGroup group, ZnackPurchaseMethod method) {
    public ZnackPurchaseContext {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(method, "method");
    }
    public static ZnackPurchaseContext forProduct(Settings settings, Product product, ZnackPurchaseMethod method) {
        return new ZnackPurchaseContext(settings, ZnackProductGroup.fromProduct(product), method);
    }
    public ZnackPurchaseContext newPurchase() { return new ZnackPurchaseContext(settings.withAutoIntroduction(true), group, method); }
    public String accountKey() {
        return digest(List.of(settings.resolvedSuzBaseUrl(), text(settings.omsId())));
    }
    public String identity() {
        return digest(List.of(credentialIdentity(settings), group.apiCode(), method.name()));
    }
    public static String credentialIdentity(Settings s) {
        return digest(List.of(s.resolvedTrueApiBaseUrl(),s.resolvedSuzBaseUrl(),text(s.omsId()),text(s.omsConnection()),
                text(s.participantInn()),text(s.producerInn()),text(s.ownerInn()),text(s.signerCertificate()),
                text(s.signerExecutable()),text(s.signerArgumentsJson()),text(s.cryptcpPath()),text(s.certificateMetadataJson())));
    }
    /** Replace only verified authority fields, keeping this member's approved document/local preferences. */
    public static Settings withCredentials(Settings original,Settings next) {
        return new Settings(next.trueApiBaseUrl(),next.suzBaseUrl(),next.omsId(),next.omsConnection(),
                next.participantInn(),next.producerInn(),next.ownerInn(),next.signerExecutable(),next.signerCertificate(),
                next.signerArgumentsJson(),original.documentNumber(),original.documentDate(),original.pdfFolder(),
                original.autoIntroduction(),next.certificateListExecutable(),next.certificateListArgumentsJson(),
                next.certificateMetadataJson(),next.signerTestedAt(),next.certmgrPath(),next.cryptcpPath(),next.csptestPath(),
                next.cryptoProTimeoutSeconds(),original.documentExpiryDate(),original.documentType());
    }
    private static String text(String value) { return value == null ? "" : value.strip(); }
    static String digest(Object value) {
        try {
            byte[] bytes = com.vncode.app.features.products.ProductJson.GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
