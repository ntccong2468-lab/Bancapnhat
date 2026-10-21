package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.znack.registration.CatalogCardState;
import com.vncode.app.shared.I18nService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Formats an already-loaded row snapshot. Rendering never reads storage or calls an API. */
final class RegistrationTableText {
    private RegistrationTableText() {}
    static String russianSize(String value) {
        return value == null || value.isBlank() ? tr("znack.registration.table_size_unknown") : value.strip();
    }
    static String moderation(Optional<RegisteredGtin> observation, boolean latestUnknown) {
        if (observation.isEmpty()) return tr(latestUnknown ? "znack.registration.table_latest_unknown" : "znack.registration.table_catalog_unknown");
        var card = observation.get();
        var raw = new LinkedHashSet<String>(card.details().values("@catalog_status"));
        raw.addAll(card.details().values("@catalog_detailed_status"));
        var normalized = new HashSet<String>();
        raw.forEach(value -> normalized.add(value.strip().toLowerCase(Locale.ROOT)));
        String label;
        if (card.published() && !Collections.disjoint(normalized, Set.of("errors", "error", "rejected")))
            label = tr("znack.registration.table_published_revision_error");
        else if (card.published() && !Collections.disjoint(normalized, Set.of("draft", "moderation", "notsigned")))
            label = tr("znack.registration.table_published_revision_pending");
        else if (!card.published() && normalized.contains("notsigned"))
            label = tr("znack.registration.status.ready_to_sign");
        else label = tr("znack.registration." + CatalogCardState.of(card).viewStatus);
        if (latestUnknown) label = tr("znack.registration.table_latest_unknown") + "\n" + label;
        if (!raw.isEmpty()) label += "\n" + String.join(" / ", raw);
        return checked(label, card.syncedAt().toString());
    }
    static String marketplace(Optional<RegisteredGtin> proof, List<String> barcodes,
                              boolean latestUnknown, String sourceReadAt, String pendingKey) {
        // A Catalog publication or local updated flag cannot prove a marketplace write.
        if (latestUnknown || time(sourceReadAt).isEmpty())
            return checked(tr("znack.registration.table_latest_unknown"), sourceReadAt);
        if (proof.isEmpty()) return checked(tr("znack.registration.gtin_unverified"), sourceReadAt);
        var card = proof.get();
        if (!card.published()) return checked(tr("znack.registration.table_wait_publication"), sourceReadAt);
        String label = RegisteredGtinProof.contains(barcodes, card.gtin())
                ? tr("znack.registration.table_marketplace_verified") : tr(pendingKey);
        return checked(label, sourceReadAt);
    }
    private static String checked(String text, String value) {
        return time(value).map(at -> text + "\n" + java.text.MessageFormat.format(
                tr("znack.registration.table_checked_at"),
                DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(at)))
                .orElse(text);
    }
    private static Optional<Instant> time(String value) {
        try { return value == null || value.isBlank() ? Optional.empty() : Optional.of(Instant.parse(value)); }
        catch (java.time.format.DateTimeParseException invalid) { return Optional.empty(); }
    }
    private static String tr(String key) { return I18nService.getInstance().tr(key); }
}
