package com.vncode.app.features.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1AgreementServiceTest {
    @TempDir Path directory;
    Shop shop; Gs1Repository repository; Fake backend;
    private static final byte[] PDF = "%PDF-1.7\nsynthetic agreement\n%%EOF".getBytes(StandardCharsets.UTF_8);
    static OperatorAgreement pending() { return new OperatorAgreement("87", "Fixture", "fixture.pdf", "2026-11-17", 1, 1, null); }
    static OperatorAgreement signed() { return new OperatorAgreement("87", "Fixture", "fixture.pdf", "2026-11-17", 2, 1, 4); }
    @BeforeEach void initialize() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString()); Database.initDatabase();
        shop = GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        repository = new Gs1Repository(directory.resolve("agreements.db")); backend = new Fake();
    }
    @AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }
    private Gs1AgreementService service() { return new Gs1AgreementService(repository, (shop, settings, scope, guard) -> backend); }
    static final class Fake implements Gs1AgreementService.Backend {
        List<OperatorAgreement> current = List.of(pending()); byte[] pdf = PDF.clone(), signedBytes;
        int reads, signatures, posts; Exception signingFailure, postFailure; Runnable afterSigning = () -> {}, afterPost = () -> {};
        boolean confirm = true;
        Integer outgoingStatus; int edoReads;
        public List<OperatorAgreement> list() { reads++; return current; }
        public byte[] pdf(OperatorAgreement agreement) { assertEquals("87", agreement.id()); return pdf.clone(); }
        public String sign(byte[] bytes) throws Exception { signatures++; signedBytes = bytes.clone(); afterSigning.run(); if (signingFailure != null) throw signingFailure; return "c3ludGhldGlj"; }
        public String submit(String id, String signature) throws Exception { posts++; assertEquals("87", id); afterPost.run(); if (postFailure != null) throw postFailure; if (confirm) current = List.of(signed()); return "synthetic-edo-id"; }
        public Integer edoStatus(String id) { assertEquals("synthetic-edo-id", id); edoReads++; return outgoingStatus; }
    }
    @Test void exactReviewedBytesRequireConsentAndReadbackBeforeConfirmation() throws Exception {
        var service = service(); try (var prepared = service.prepare(shop, pending())) {
            assertThrows(IllegalArgumentException.class, () -> service.sign(shop, prepared, false)); assertEquals(0, backend.signatures);
            byte[] clone = prepared.pdf(); clone[0] = 0; assertArrayEquals(PDF, prepared.pdf());
            var result = service.sign(shop, prepared, true);
            assertEquals(Gs1AgreementService.State.CONFIRMED, result.state()); assertArrayEquals(PDF, backend.signedBytes);
            assertEquals(1, backend.posts); assertEquals(Gs1Repository.State.SUBMITTED, repository.request("7700000000", prepared.requestId()).state());
            assertEquals(Gs1AgreementService.State.CONFIRMED, service.sign(shop, prepared, true).state()); assertEquals(1, backend.posts);
        }
    }
    @Test void successfulPostWithoutConfirmedExactIdStaysPendingAcrossRestart() throws Exception {
        backend.confirm = false; var first = service(); String id;
        try (var prepared = first.prepare(shop, pending())) {
            id = prepared.requestId(); assertEquals(Gs1AgreementService.State.PENDING, first.sign(shop, prepared, true).state());
        }
        var restarted = new Gs1AgreementService(new Gs1Repository(directory.resolve("agreements.db")), (shop, settings, scope, guard) -> backend);
        try (var restored = restarted.prepare(shop, pending())) {
            assertEquals(id, restored.requestId()); assertEquals(Gs1AgreementService.State.PENDING, restarted.sign(shop, restored, true).state());
            assertEquals(1, backend.posts); assertEquals(1, backend.signatures);
            backend.current = List.of(signed()); assertEquals(Gs1AgreementService.State.CONFIRMED, restarted.reconcile(shop, id).state());
        }
    }
    @Test void uncertainPostMustReadInsteadOfSigningOrPostingAgainEvenIfStillAwaiting() throws Exception {
        backend.postFailure = new IOException("synthetic disconnect"); var service = service(); String id;
        try (var prepared = service.prepare(shop, pending())) {
            id = prepared.requestId(); assertThrows(IOException.class, () -> service.sign(shop, prepared, true));
            assertEquals(Gs1Repository.State.RECONCILE_REQUIRED, repository.request("7700000000", id).state());
        }
        backend.postFailure = null;
        try (var restored = service().prepare(shop, pending())) {
            assertEquals(id, restored.requestId()); assertEquals(Gs1AgreementService.State.PENDING, service().sign(shop, restored, true).state());
            assertEquals(1, backend.signatures); assertEquals(1, backend.posts);
        }
    }
    @Test void anotherSignedDocumentOrMissingIdCannotConfirmThisAgreement() throws Exception {
        backend.confirm = false; backend.afterPost = () -> backend.current = List.of(new OperatorAgreement("99", "Other", "other.pdf", "", 2, 1, 4));
        var service = service(); try (var prepared = service.prepare(shop, pending())) {
            assertEquals(Gs1AgreementService.State.RECONCILE_REQUIRED, service.sign(shop, prepared, true).state());
            assertEquals(Gs1Repository.State.RECONCILE_REQUIRED, repository.request("7700000000", prepared.requestId()).state());
        }
    }
    @Test void sameIdWithChangedMetadataCannotConfirmTheReviewedAgreement() throws Exception {
        backend.confirm = false; backend.afterPost = () -> backend.current = List.of(new OperatorAgreement("87", "Different contract", "other.pdf", "2026-11-17", 2, 1, 4));
        var service = service(); try (var prepared = service.prepare(shop, pending())) {
            assertEquals(Gs1AgreementService.State.RECONCILE_REQUIRED, service.sign(shop, prepared, true).state());
            assertEquals(Gs1Repository.State.RECONCILE_REQUIRED, repository.request("7700000000", prepared.requestId()).state());
        }
    }
    @Test void cancellationBeforePostLeavesExactDraftRetryable() throws Exception {
        backend.signingFailure = new IOException("synthetic cancelled signing"); var service = service();
        try (var prepared = service.prepare(shop, pending())) {
            assertThrows(IOException.class, () -> service.sign(shop, prepared, true)); assertEquals(0, backend.posts);
            assertEquals(Gs1Repository.State.DRAFT, repository.request("7700000000", prepared.requestId()).state());
            backend.signingFailure = null; assertEquals(Gs1AgreementService.State.CONFIRMED, service.sign(shop, prepared, true).state()); assertEquals(1, backend.posts);
        }
    }
    @Test void changedPdfCannotReachSignerEvenWithSameIdAndConsent() throws Exception {
        var service = service(); try (var prepared = service.prepare(shop, pending())) {
            backend.pdf = "%PDF-1.7\nchanged agreement\n%%EOF".getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalStateException.class, () -> service.sign(shop, prepared, true)); assertEquals(0, backend.signatures); assertEquals(0, backend.posts);
        }
    }
    @Test void certificateChangeCannotUseAnExistingDraftApproval() throws Exception {
        var service = service(); try (var prepared = service.prepare(shop, pending())) {
            GtinWriteTestFixture.saveSettings(shop, "B".repeat(40), ZnackModels.PRODUCTION_TRUE_API);
            assertThrows(IllegalStateException.class, () -> service.sign(shop, prepared, true)); assertEquals(0, backend.signatures); assertEquals(0, backend.posts);
        }
    }
    @Test void changedCertificateCanReadAnUncertainOldRequestButNeverResendIt() throws Exception {
        backend.postFailure = new IOException("synthetic disconnect"); var service = service(); String id;
        try (var prepared = service.prepare(shop, pending())) { id = prepared.requestId(); assertThrows(IOException.class, () -> service.sign(shop, prepared, true)); }
        GtinWriteTestFixture.saveSettings(shop, "B".repeat(40), ZnackModels.PRODUCTION_TRUE_API); backend.postFailure = null;
        try (var restored = service().prepare(shop, pending())) {
            assertEquals(id, restored.requestId()); assertEquals(Gs1AgreementService.State.PENDING, service().sign(shop, restored, true).state());
            assertEquals(1, backend.posts); assertEquals(1, backend.signatures);
        }
    }
    @Test void scopeChangeDuringSigningStopsBeforePostAndRestoresUnsentDraft() throws Exception {
        var service = service(); try (var prepared = service.prepare(shop, pending())) {
            backend.afterSigning = () -> GtinWriteTestFixture.saveSettings(shop, "B".repeat(40), ZnackModels.PRODUCTION_TRUE_API);
            assertThrows(IllegalStateException.class, () -> service.sign(shop, prepared, true)); assertEquals(0, backend.posts);
            assertEquals(Gs1Repository.State.DRAFT, repository.request("7700000000", prepared.requestId()).state());
        }
    }
    @Test void foreignEnterpriseRequestAndClosedPreviewCannotSend() throws Exception {
        var foreign = repository.createRequest("7800000000", "OPERATOR_AGREEMENT:foreign", "True API", "Fixture", "{}");
        assertThrows(RuntimeException.class, () -> service().reconcile(shop, foreign.id())); assertEquals(0, backend.reads);
        var prepared = service().prepare(shop, pending()); prepared.close();
        assertThrows(IllegalStateException.class, prepared::pdf);
        assertThrows(IllegalStateException.class, () -> service().sign(shop, prepared, true)); assertEquals(0, backend.posts);
    }
    @Test void operatorCounterSignaturePendingIsNotHiddenBySellerConfirmation() throws Exception {
        backend.afterPost = () -> backend.current = List.of(new OperatorAgreement("87", "Fixture", "fixture.pdf", "2026-11-17", 2, 1, 2)); backend.confirm = false;
        var service = service(); try (var prepared = service.prepare(shop, pending())) {
            var result = service.sign(shop, prepared, true); assertEquals(Gs1AgreementService.State.CONFIRMED, result.state());
            assertTrue(result.agreement().operatorPending());
        }
    }
    @Test void retainedAcknowledgedEdoIdCanConfirmTheCounterSignatureWhenCrmOmitsIt() throws Exception {
        backend.confirm = false; backend.outgoingStatus = 4;
        backend.afterPost = () -> backend.current = List.of(new OperatorAgreement("87", "Fixture", "fixture.pdf", "2026-11-17", 2, 1, null));
        var service = service(); try (var prepared = service.prepare(shop, pending())) {
            var result = service.sign(shop, prepared, true);
            assertEquals(Gs1AgreementService.State.CONFIRMED, result.state()); assertFalse(result.agreement().operatorPending());
            assertEquals("synthetic-edo-id", result.agreement().edoDocumentId()); assertEquals(1, backend.edoReads);
            var refreshed = service().list(shop).getFirst(); assertFalse(refreshed.operatorPending()); assertEquals(2, backend.edoReads);
        }
    }
    @Test void anotherVerifiedStoreOfTheSameEnterpriseCannotRepeatAnUncertainSignature() throws Exception {
        backend.postFailure = new IOException("synthetic disconnect"); var service = service(); String id;
        try (var prepared = service.prepare(shop, pending())) { id = prepared.requestId(); assertThrows(IOException.class, () -> service.sign(shop, prepared, true)); }
        Shop another = GtinWriteTestFixture.shop(Marketplace.OZON); backend.postFailure = null;
        try (var restored = service().prepare(another, pending())) {
            assertEquals(id, restored.requestId()); assertTrue(restored.requiresReconciliation());
            assertEquals(Gs1AgreementService.State.PENDING, service().sign(another, restored, true).state());
            assertEquals(1, backend.posts); assertEquals(1, backend.signatures);
        }
    }
    @Test void marketplaceKeyRotationCannotCreateANewSignatureForTheSamePendingContract() throws Exception {
        backend.postFailure = new IOException("synthetic disconnect"); var service = service(); String id;
        try (var prepared = service.prepare(shop, pending())) { id = prepared.requestId(); assertThrows(IOException.class, () -> service.sign(shop, prepared, true)); }
        try (var connection = Database.getConnection(); var update = connection.prepareStatement("UPDATE shops SET api_key=? WHERE id=?")) {
            update.setString(1, "rotated-synthetic-key"); update.setInt(2, shop.getId()); update.executeUpdate();
        }
        shop.setApiKey("rotated-synthetic-key"); backend.postFailure = null;
        try (var restored = service().prepare(shop, pending())) {
            assertEquals(id, restored.requestId()); assertTrue(restored.requiresReconciliation());
            assertEquals(Gs1AgreementService.State.PENDING, service().sign(shop, restored, true).state()); assertEquals(1, backend.posts);
        }
    }
    @Test void anotherStoreCannotUseAnUnsentDraftConsentEvenForTheSameEnterprise() throws Exception {
        var service = service(); try (var prepared = service.prepare(shop, pending())) {
            Shop another = GtinWriteTestFixture.shop(Marketplace.OZON);
            assertThrows(Exception.class, () -> service().sign(another, prepared, true));
            assertEquals(0, backend.posts); assertEquals(0, backend.signatures);
        }
    }
    @Test void canonicalProductionEndpointChangesStillReconcileTheOriginalAttempt() throws Exception {
        backend.postFailure = new IOException("synthetic disconnect"); var service = service(); String id;
        try (var prepared = service.prepare(shop, pending())) { id = prepared.requestId(); assertThrows(IOException.class, () -> service.sign(shop, prepared, true)); }
        GtinWriteTestFixture.saveSettings(shop, GtinWriteTestFixture.CERTIFICATE, "https://markirovka.crpt.ru/api/v4/true-api"); backend.postFailure = null;
        try (var restored = service().prepare(shop, pending())) {
            assertEquals(id, restored.requestId()); assertTrue(restored.requiresReconciliation());
            assertEquals(Gs1AgreementService.State.PENDING, service().sign(shop, restored, true).state()); assertEquals(1, backend.posts);
        }
    }
}
