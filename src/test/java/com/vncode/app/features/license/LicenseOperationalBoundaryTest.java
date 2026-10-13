package com.vncode.app.features.license;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.features.fbo.FboKizPrintPlanner;
import com.vncode.app.features.gs1.Gs1ApplicationService;
import com.vncode.app.features.gs1.Gs1LettersService;
import com.vncode.app.features.gs1.Gs1Repository;
import com.vncode.app.features.packing.PackingWorkflow;
import com.vncode.app.features.print.OrderExportWorkflow;
import com.vncode.app.features.print.ZnackKizExportService;
import com.vncode.app.integration.gs1.Gs1MailAccount;
import com.vncode.app.integration.gs1.Gs1MailClient;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LicenseOperationalBoundaryTest {
    @TempDir Path directory;
    private final LicenseService prior = LicenseRuntime.service();
    private LicenseService denied;

    private void deny() throws Exception {
        if (denied == null) denied = LicenseDomainFixture.denied(directory.resolve("denied"));
        LicenseRuntime.install(denied);
    }

    @AfterEach void restore() {
        LicenseRuntime.install(prior);
        if (denied != null) denied.close();
    }

    @Test void deniedPackingStopsBeforeAnyNewShipmentPreflightOrWrite() throws Exception {
        deny();
        var shop = new Shop(1, "Fixture", "fixture-token");
        var workflow = new PackingWorkflow();
        assertThrows(LicenseGate.LicenseDeniedException.class,
                () -> workflow.createShipment(shop, "Fixture", List.of(1L)));
        assertThrows(LicenseGate.LicenseDeniedException.class,
                () -> workflow.addOrdersToSupply(shop, "SUPPLY", List.of(1L)));
        assertThrows(LicenseGate.LicenseDeniedException.class,
                () -> workflow.deliverSupply(shop, null));
        assertThrows(LicenseGate.LicenseDeniedException.class,
                () -> workflow.getSupplyBarcodePdf(shop, null));
    }

    @Test void deniedFboPlanCannotReserveAnyInventory() throws Exception {
        deny();
        assertThrows(LicenseGate.LicenseDeniedException.class,
                () -> new FboKizPrintPlanner().plan(1, List.of()));
    }

    @Test void deniedWbExportCannotReserveKizOrCreateOutputFiles() throws Exception {
        deny();
        var labels = directory.resolve("labels.pdf").toFile();
        var details = directory.resolve("details.pdf").toFile();
        var request = new OrderExportWorkflow.ExportRequest(new Shop(1, "Fixture", "fixture-token"),
                "SUPPLY", "Fixture", List.of(), null, labels, details);
        assertThrows(LicenseGate.LicenseDeniedException.class,
                () -> new OrderExportWorkflow().export(request));
        assertFalse(labels.exists());
        assertFalse(details.exists());
    }

    @Test void deniedGtinInventoryPdfCannotReserveOrConsumeNewKiz() throws Exception {
        deny();
        var target = directory.resolve("gtin-kiz.pdf").toFile();
        assertThrows(LicenseGate.LicenseDeniedException.class,
                () -> new ZnackKizExportService().export(1, "04601234567890", 1, target));
        assertFalse(target.exists());
    }

    @Test void deniedGs1DraftCreatesNoNewRequestOrRemoteDraft() throws Exception {
        var repository = new Gs1Repository(directory.resolve("gs1.db"));
        deny();
        assertThrows(LicenseGate.LicenseDeniedException.class,
                () -> new Gs1LettersService(repository).draft(
                        "7707083893", "Fixture", Gs1LettersService.Kind.RENEWAL, null, ""));
        assertThrows(LicenseGate.LicenseDeniedException.class,
                () -> new Gs1ApplicationService(repository).prepareSigning(null, new JsonObject()));
        assertTrue(repository.requests("7707083893").isEmpty());
    }

    @Test void deniedMailSendLeavesReviewedDraftUnclaimed() throws Exception {
        var repository = new Gs1Repository(directory.resolve("gs1.db"));
        var sends = new AtomicInteger();
        var service = new Gs1LettersService(repository, (a, l) -> sends.incrementAndGet());
        var request = service.draft("7707083893", "Fixture", Gs1LettersService.Kind.RENEWAL, null, "");
        deny();
        assertThrows(LicenseGate.LicenseDeniedException.class,
                () -> service.send(account(), request.inn(), request.id()));
        assertEquals(0, sends.get());
        assertEquals(Gs1Repository.State.DRAFT, repository.request(request.inn(), request.id()).state());
    }

    @Test void acceptedMailCheckpointIsPreservedWhenRightsAreLostDuringSend() throws Exception {
        denied = LicenseDomainFixture.denied(directory.resolve("denied"));
        var repository = new Gs1Repository(directory.resolve("gs1.db"));
        var service = new Gs1LettersService(repository, (a, l) -> LicenseRuntime.install(denied));
        var request = service.draft("7707083893", "Fixture", Gs1LettersService.Kind.RENEWAL, null, "");
        service.send(account(), request.inn(), request.id());
        assertEquals(Gs1Repository.State.SENT, repository.request(request.inn(), request.id()).state());
        assertEquals(1, repository.messages(request.inn(), request.id()).size());
    }

    @Test void licenseLostDuringSigningRestoresUnsentDraftWithoutPosting() throws Exception {
        denied = LicenseDomainFixture.denied(directory.resolve("denied"));
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"data\":\"YWJj\"}"));
            server.enqueue(new MockResponse().setBody("{\"csrfToken\":\"fixture\",\"gs1Member\":false}"));
            var session = new NationalCatalogGs1Client(new OkHttpClient(), server.url("/rest/")).login(
                    "7707083893", "A".repeat(40), (p, c) -> new CryptoProSigningResult(new byte[]{1}, ""));
            var form = JsonParser.parseString("{\"applicant\":{\"inn\":\"7707083893\",\"companyName\":\"Fixture\"}}").getAsJsonObject();
            server.enqueue(new MockResponse().setBody(form.toString()));
            server.enqueue(new MockResponse().setBody("{}"));
            server.enqueue(new MockResponse().setBody(form.toString()));
            server.enqueue(new MockResponse().setBody("{\"XMLForSign\":\"<application><inn>7707083893</inn><companyName>Fixture</companyName></application>\"}"));
            var repository = new Gs1Repository(directory.resolve("gs1.db"));
            var service = new Gs1ApplicationService(repository);
            var prepared = service.prepareSigning(session, form);
            server.enqueue(new MockResponse().setBody(form.toString()));
            assertThrows(LicenseGate.LicenseDeniedException.class, () -> service.submit(session, prepared, xml -> {
                LicenseRuntime.install(denied);
                return "<signed/>";
            }));
            assertEquals(7, server.getRequestCount(), "No sign-form POST may be sent after signature wait loses rights");
            assertEquals(Gs1Repository.State.DRAFT, repository.request(session.inn(), prepared.requestId()).state());
        }
    }

    @Test void sentCopyReconciliationIsAvailableAfterLicenseDenial() throws Exception {
        var repository = new Gs1Repository(directory.resolve("gs1.db"));
        var sends = new AtomicInteger();
        var service = new Gs1LettersService(repository, (a, l) -> {
            sends.incrementAndGet();
            throw new java.io.IOException("Unknown delivery");
        });
        var request = service.draft("7707083893", "Fixture", Gs1LettersService.Kind.RENEWAL, null, "");
        assertThrows(java.io.IOException.class, () -> service.send(account(), request.inn(), request.id()));
        var evidence = new Gs1MailClient().export(account().from(), service.letter(request));
        deny();
        assertEquals(Gs1Repository.State.SENT,
                service.reconcileSent(account(), request.inn(), request.id(), evidence));
        assertEquals(1, sends.get(), "Reconciliation cannot send another letter");
    }

    private static Gs1MailAccount account() {
        return new Gs1MailAccount("smtp.fixture", 465, "imap.fixture", 993,
                "fixture", "sender@fixture.ru", "dpapi:fixture");
    }
}
