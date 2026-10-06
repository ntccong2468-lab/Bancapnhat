package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventoryService;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.DownloadedCodes;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.LocalDataMigrationGate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Explicitly invoked production acceptance harness for Ozon FBS Standard.
 *
 * <p>This class is deliberately kept under test sources, is not included in shipped binaries and
 * never prints credentials, raw KIZ values, posting numbers, product names or raw API responses.
 * Remote mutations are split into separate commands. In particular, {@code ship} requires a
 * confirmation token equal to the redacted posting alias printed by {@code status}.
 */
public final class OzonLiveAcceptanceCli {
    private static final String SHOP_NAME = "Ozon Live Acceptance";
    private static final String SELECTED_POSTING_KEY = "ozon_live_selected_posting";
    private static final String SELECTED_ALIAS_KEY = "ozon_live_selected_alias";
    private static final String KIZ_FINGERPRINT_KEY = "ozon_live_kiz_fingerprint";
    private static final String RETIRED_KIZ_FINGERPRINT = "acab07e4d777";
    private static final int DEFAULT_KIZ_LINE = 2;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private OzonLiveAcceptanceCli() {
    }

    static void initializeLocalDatabase() {
        Database.initDatabase();
    }

    public static void main(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            System.setProperty("wcode.appdata.dir", parsed.dataDir().toString());
            secureDirectory(parsed.dataDir());
            try (LocalDataMigrationGate.Session ignored = LocalDataMigrationGate.prepare(
                    AppPaths.appDataDir(), "1.1.7-live", "ozon-live")) {
                initializeLocalDatabase();
                Shop shop = ensureShop(loadCredentials(parsed.envFile()));
                switch (parsed.command()) {
                    case "discover" -> discover(shop, parsed);
                    case "prepare" -> prepare(shop, parsed);
                    case "status" -> status(shop, parsed);
                    case "diagnose" -> diagnose(shop, parsed);
                    case "reconcile-set" -> reconcileSet(shop, parsed);
                    case "ship" -> ship(shop, parsed);
                    case "label" -> label(shop, parsed);
                    default -> throw new SafeFailure("unsupported_command");
                }
            } finally {
                secureDatabaseFiles(parsed.dataDir());
            }
        } catch (Exception exception) {
            System.out.println("live_result=failed");
            System.out.println("safe_error=" + safeError(exception));
            System.exit(1);
        }
    }

    private static void discover(Shop shop, Arguments arguments) throws IOException {
        OzonSyncWorkflow workflow = new OzonSyncWorkflow();
        OzonConnectionCheck connection = workflow.checkConnection(shop);
        OzonSyncReport sync = workflow.syncOverview(shop);
        List<Candidate> candidates = eligibleCandidates(shop.getId(), arguments);
        System.out.println("live_result=read_only_ok");
        System.out.println("account=" + maskedIdentity(connection.clientId()));
        System.out.println("roles=" + connection.roleCount());
        System.out.println("warehouses=" + connection.warehouseCount());
        System.out.println("exemplar_access=" + connection.exemplarAccess());
        System.out.println("ship_access=" + connection.shipAccess());
        System.out.println("label_access=" + connection.labelAccess());
        System.out.println("products_synced=" + sync.products());
        System.out.println("postings_synced=" + sync.postings());
        System.out.println("eligible_marking_candidates=" + candidates.size());
        for (Candidate candidate : candidates) {
            System.out.println("candidate=" + candidate.alias()
                    + " marking=" + candidate.marking()
                    + " quantity=" + candidate.quantity()
                    + " sku_suffix=" + suffix(candidate.item().sku(), 4)
                    + " shipment=" + safeTimestamp(candidate.posting().shipmentAt()));
        }
        Candidate selected = selectCandidate(candidates, arguments.postingAlias());
        if (selected != null) {
            putConfig(SELECTED_POSTING_KEY, selected.posting().postingNumber());
            putConfig(SELECTED_ALIAS_KEY, selected.alias());
            System.out.println("selected_candidate=" + selected.alias());
            System.out.println("next_command=prepare");
        } else if (candidates.size() > 1) {
            System.out.println("selection_required=true");
            System.out.println("next_command=discover --posting-alias=<candidate>");
        } else {
            System.out.println("selection_required=false");
            System.out.println("next_command=wait_for_eligible_posting");
        }
        writeEvidence(arguments.dataDir(), "discover", Map.of(
                "result", "read_only_ok",
                "account", maskedIdentity(connection.clientId()),
                "roles", connection.roleCount(),
                "warehouses", connection.warehouseCount(),
                "productsSynced", sync.products(),
                "postingsSynced", sync.postings(),
                "eligibleCandidates", candidates.size(),
                "selectedCandidate", selected == null ? "" : selected.alias()));
    }

    private static void prepare(Shop shop, Arguments arguments) throws IOException {
        String postingNumber = requireSelectedPosting();
        String selectedAlias = alias(postingNumber);
        String storedAlias = getConfig(SELECTED_ALIAS_KEY);
        if (!selectedAlias.equals(storedAlias)) throw new SafeFailure("selection_integrity_failed");

        OzonApiClient api = api(shop);
        OzonPostingDto posting = OzonJson.parsePostingDetail(api.getPosting(postingNumber, true));
        new OzonPostingRepository().upsertDetail(shop.getId(), posting);
        Candidate candidate = candidate(posting);
        if (candidate == null) throw new SafeFailure("posting_not_eligible");

        KizFixture fixture = loadKizFixture(arguments.kizFile(), arguments.kizLine());
        stageInventory(shop, candidate, fixture);
        OzonExemplarService exemplars = new OzonExemplarService();
        OzonPreparationResult first = exemplars.prepare(shop, postingNumber);
        OzonExemplarJob firstJob = new OzonExemplarJobRepository().find(shop.getId(), postingNumber);
        long firstJobId = firstJob == null ? -1 : firstJob.id();
        List<Long> firstKizIds = firstJob == null ? List.of() : new OzonExemplarJobRepository()
                .summaries(firstJob.id()).stream().map(OzonExemplarJobRepository.ExemplarSummary::kizId).toList();

        // A second call is part of the acceptance gate: it must resume the same durable job and
        // must not reserve another KIZ.
        OzonPreparationResult second = exemplars.prepare(shop, postingNumber);
        OzonExemplarJob secondJob = new OzonExemplarJobRepository().find(shop.getId(), postingNumber);
        List<Long> secondKizIds = secondJob == null ? List.of() : new OzonExemplarJobRepository()
                .summaries(secondJob.id()).stream().map(OzonExemplarJobRepository.ExemplarSummary::kizId).toList();
        boolean duplicateSafe = secondJob != null && firstJobId == secondJob.id() && firstKizIds.equals(secondKizIds);

        Verification verification = verify(shop, candidate, fixture);
        boolean accepted = "ACCEPTED".equals(first.stage())
                && "ACCEPTED".equals(second.stage())
                && first.shipReady() && second.shipReady();
        System.out.println("live_result=" + (accepted && verification.pass() && duplicateSafe ? "prepare_ok" : "prepare_incomplete"));
        System.out.println("candidate=" + selectedAlias);
        System.out.println("kiz_fingerprint=" + fixture.fingerprint());
        System.out.println("stage=" + second.stage());
        System.out.println("ship_ready=" + second.shipReady());
        System.out.println("remote_mark_match=" + verification.remoteMarkMatch());
        System.out.println("remote_mark_passed=" + verification.remotePassed());
        System.out.println("local_kiz_consumed=" + verification.localConsumed());
        System.out.println("duplicate_click_safe=" + duplicateSafe);
        System.out.println("shipping_performed=false");
        System.out.println("next_command=status");
        writeEvidence(arguments.dataDir(), "prepare", evidenceFor(
                selectedAlias, fixture.fingerprint(), second.stage(), second.shipReady(),
                verification, duplicateSafe, false, ""));
        if (!accepted || !verification.pass() || !duplicateSafe) throw new SafeFailure("prepare_acceptance_failed");
    }

    private static void status(Shop shop, Arguments arguments) throws IOException {
        String postingNumber = requireSelectedPosting();
        OzonPostingDto posting = OzonJson.parsePostingDetail(api(shop).getPosting(postingNumber, true));
        new OzonPostingRepository().upsertDetail(shop.getId(), posting);
        OzonExemplarJob job = new OzonExemplarJobRepository().find(shop.getId(), postingNumber);
        String labelStatus = labelStatus(shop.getId(), postingNumber);
        String shipAction = new OzonExemplarJobRepository().latestActionStatus(shop.getId(), "ship", postingNumber);
        System.out.println("live_result=status_ok");
        System.out.println("candidate=" + alias(postingNumber));
        System.out.println("posting_status=" + safeToken(posting.status()));
        System.out.println("posting_substatus=" + safeToken(posting.substatus()));
        System.out.println("ship_available=" + posting.canShip());
        System.out.println("exemplar_stage=" + (job == null ? "NONE" : job.stage().name()));
        System.out.println("ship_action=" + (shipAction == null ? "NONE" : safeToken(shipAction)));
        System.out.println("label_status=" + labelStatus);
        System.out.println("shipping_performed=" + isPostShipStatus(posting.status()));
        System.out.println("ship_confirmation_token=" + alias(postingNumber));
        writeEvidence(arguments.dataDir(), "status", Map.of(
                "result", "status_ok",
                "candidate", alias(postingNumber),
                "postingStatus", safeToken(posting.status()),
                "shipAvailable", posting.canShip(),
                "exemplarStage", job == null ? "NONE" : job.stage().name(),
                "shipAction", shipAction == null ? "NONE" : safeToken(shipAction),
                "labelStatus", labelStatus));
    }

    private static void diagnose(Shop shop, Arguments arguments) throws IOException {
        String postingNumber = requireSelectedPosting();
        OzonApiClient api = api(shop);
        OzonPostingDto posting = OzonJson.parsePostingDetail(api.getPosting(postingNumber, true));
        OzonExemplarJob job = new OzonExemplarJobRepository().find(shop.getId(), postingNumber);
        if (job == null) throw new SafeFailure("exemplar_job_missing");
        List<OzonExemplarJobRepository.KizBinding> bindings = new OzonExemplarJobRepository().bindings(job.id());
        JsonObject create = api.createOrGetExemplars(OzonExemplarService.createRequest(posting));
        JsonObject statusRequest = new JsonObject();
        statusRequest.addProperty("posting_number", postingNumber);
        JsonObject statusBody = api.exemplarStatus(statusRequest);
        OzonExemplarRemoteStatus remote = OzonExemplarJson.status(statusBody, bindings.size());
        System.out.println("live_result=diagnostic_ok");
        System.out.println("candidate=" + alias(postingNumber));
        System.out.println("job_stage=" + job.stage().name());
        System.out.println("job_error=" + safeToken(job.safeErrorCode()));
        System.out.println("remote_products=" + namedArrayItems(create, "products"));
        System.out.println("remote_exemplars=" + namedArrayItems(create, "exemplars"));
        System.out.println("remote_marks=" + namedArrayItems(create, "marks"));
        System.out.println("mandatory_needed_true=" + booleanOccurrences(create, "is_mandatory_mark_needed", true));
        System.out.println("mandatory_possible_true=" + booleanOccurrences(create, "is_mandatory_mark_possible", true));
        System.out.println("status_has_marks=" + remote.hasRemoteMarks());
        System.out.println("status_all_passed=" + remote.allMarksPassed());
        System.out.println("status_rejected=" + remote.rejected());
        System.out.println("status_ship_available=" + remote.shipAvailable());
        System.out.println("local_bindings=" + bindings.size());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("result", "diagnostic_ok");
        evidence.put("candidate", alias(postingNumber));
        evidence.put("jobStage", job.stage().name());
        evidence.put("jobError", safeToken(job.safeErrorCode()));
        evidence.put("remoteProducts", namedArrayItems(create, "products"));
        evidence.put("remoteExemplars", namedArrayItems(create, "exemplars"));
        evidence.put("remoteMarks", namedArrayItems(create, "marks"));
        evidence.put("mandatoryNeeded", booleanOccurrences(create, "is_mandatory_mark_needed", true));
        evidence.put("mandatoryPossible", booleanOccurrences(create, "is_mandatory_mark_possible", true));
        evidence.put("statusHasMarks", remote.hasRemoteMarks());
        evidence.put("statusAllPassed", remote.allMarksPassed());
        evidence.put("statusRejected", remote.rejected());
        evidence.put("statusShipAvailable", remote.shipAvailable());
        evidence.put("localBindings", bindings.size());
        writeEvidence(arguments.dataDir(), "diagnose", evidence);
    }

    private static void reconcileSet(Shop shop, Arguments arguments) throws IOException {
        String postingNumber = requireSelectedPosting();
        String selectedAlias = alias(postingNumber);
        if (!selectedAlias.equals(arguments.confirmReconcile())) {
            throw new SafeFailure("explicit_reconcile_confirmation_required");
        }
        OzonExemplarJobRepository jobs = new OzonExemplarJobRepository();
        OzonExemplarJob job = jobs.find(shop.getId(), postingNumber);
        if (job == null || job.stage() != OzonExemplarJobStage.RECONCILE_REQUIRED) {
            throw new SafeFailure("reconcile_job_not_ready");
        }
        List<OzonExemplarJobRepository.KizBinding> bindings = jobs.bindings(job.id());
        if (bindings.size() != 1) throw new SafeFailure("reconcile_binding_count_invalid");
        OzonApiClient api = api(shop);
        OzonPostingDto posting = OzonJson.parsePostingDetail(api.getPosting(postingNumber, true));
        JsonObject create = api.createOrGetExemplars(OzonExemplarService.createRequest(posting));
        JsonObject statusRequest = new JsonObject();
        statusRequest.addProperty("posting_number", postingNumber);
        OzonExemplarRemoteStatus remote = OzonExemplarJson.status(api.exemplarStatus(statusRequest), bindings.size());
        if (namedArrayItems(create, "marks") != 0 || remote.hasRemoteMarks()) {
            throw new SafeFailure("remote_mark_not_empty");
        }
        try {
            api.setExemplars(OzonExemplarService.exemplarPayload(postingNumber, bindings, true));
            jobs.logAction(shop.getId(), "exemplar_set", postingNumber, "resubmitted", null,
                    job.requestFingerprint());
        } catch (OzonApiException exception) {
            jobs.logAction(shop.getId(), "exemplar_set_probe", postingNumber, "rejected",
                    exception.safeErrorCode(), job.requestFingerprint());
            System.out.println("live_result=reconcile_set_rejected");
            System.out.println("candidate=" + selectedAlias);
            System.out.println("http_status=" + exception.statusCode());
            System.out.println("upstream_code=" + safeToken(exception.upstreamCode()));
            System.out.println("job_stage=" + job.stage().name());
            System.out.println("local_kiz_reserved=true");
            writeEvidence(arguments.dataDir(), "reconcile-set", Map.of(
                    "result", "reconcile_set_rejected",
                    "candidate", selectedAlias,
                    "httpStatus", exception.statusCode(),
                    "upstreamCode", safeToken(exception.upstreamCode()),
                    "jobStage", job.stage().name(),
                    "localKizReserved", true));
            throw new SafeFailure("reconcile_set_rejected");
        }
        OzonPreparationResult result = new OzonExemplarService().prepare(shop, postingNumber);
        KizFixture fixture = loadKizFixture(arguments.kizFile(), arguments.kizLine());
        Verification verification = verify(shop, candidate(posting), fixture);
        System.out.println("live_result=" + ("ACCEPTED".equals(result.stage()) && verification.pass()
                ? "reconcile_ok" : "reconcile_pending"));
        System.out.println("candidate=" + selectedAlias);
        System.out.println("stage=" + result.stage());
        System.out.println("ship_ready=" + result.shipReady());
        System.out.println("remote_mark_match=" + verification.remoteMarkMatch());
        System.out.println("remote_mark_passed=" + verification.remotePassed());
        System.out.println("local_kiz_consumed=" + verification.localConsumed());
        writeEvidence(arguments.dataDir(), "reconcile-set", evidenceFor(
                selectedAlias, fixture.fingerprint(), result.stage(), result.shipReady(),
                verification, true, false, ""));
        if (!"ACCEPTED".equals(result.stage()) || !verification.pass()) {
            throw new SafeFailure("reconcile_pending");
        }
    }

    private static void ship(Shop shop, Arguments arguments) throws IOException {
        String postingNumber = requireSelectedPosting();
        String selectedAlias = alias(postingNumber);
        if (!selectedAlias.equals(arguments.confirmShip())) {
            throw new SafeFailure("explicit_ship_confirmation_required");
        }
        OzonShipResult result = new OzonShipService().ship(shop, postingNumber, true);
        System.out.println("live_result=ship_ok");
        System.out.println("candidate=" + selectedAlias);
        System.out.println("posting_status=" + safeToken(result.status()));
        System.out.println("reconciled=" + result.reconciledAfterAmbiguousResponse());
        System.out.println("next_command=label");
        writeEvidence(arguments.dataDir(), "ship", Map.of(
                "result", "ship_ok",
                "candidate", selectedAlias,
                "postingStatus", safeToken(result.status()),
                "reconciled", result.reconciledAfterAmbiguousResponse()));
    }

    private static void label(Shop shop, Arguments arguments) throws IOException {
        String postingNumber = requireSelectedPosting();
        OzonPostingDto posting = OzonJson.parsePostingDetail(api(shop).getPosting(postingNumber, true));
        if (!isPostShipStatus(posting.status())) throw new SafeFailure("posting_not_shipped");
        Path exportDir = arguments.dataDir().resolve("exports");
        Files.createDirectories(exportDir);
        Path target = exportDir.resolve("OZON-" + alias(postingNumber) + ".pdf");
        new OzonLabelService().downloadOfficialPdf(shop, postingNumber, target.toFile());
        long size = Files.size(target);
        System.out.println("live_result=label_ok");
        System.out.println("candidate=" + alias(postingNumber));
        System.out.println("label_file=" + target);
        System.out.println("label_bytes=" + size);
        writeEvidence(arguments.dataDir(), "label", Map.of(
                "result", "label_ok",
                "candidate", alias(postingNumber),
                "labelFile", target.getFileName().toString(),
                "labelBytes", size));
    }

    private static Verification verify(Shop shop, Candidate candidate, KizFixture fixture) throws IOException {
        OzonApiClient api = api(shop);
        JsonObject statusRequest = new JsonObject();
        statusRequest.addProperty("posting_number", candidate.posting().postingNumber());
        OzonExemplarRemoteStatus remoteStatus = OzonExemplarJson.status(api.exemplarStatus(statusRequest), 1);
        JsonObject readback = api.createOrGetExemplars(OzonExemplarService.createRequest(candidate.posting()));
        boolean remoteMarkMatch = containsMark(readback, KizService.scannerSafeCode(fixture.rawCode()));
        boolean remotePassed = remoteStatus.accepted();
        boolean localConsumed = localKizState(
                shop.getId(), fixture.rawCode(), candidate.posting().postingNumber(), "CONSUMED");
        boolean oneLinkedKiz = linkedKizCount(shop.getId(), candidate.posting().postingNumber()) == 1;
        return new Verification(remoteMarkMatch, remotePassed, localConsumed, oneLinkedKiz);
    }

    private static void stageInventory(Shop shop, Candidate candidate, KizFixture fixture) {
        if (RETIRED_KIZ_FINGERPRINT.equals(fixture.fingerprint())) throw new SafeFailure("retired_kiz_blocked");
        ShopContext context = new ShopContext(shop.getId(), SHOP_NAME);
        ZnackRepository znack = new ZnackRepository(context);
        znack.upsertProducts(List.of(new Product(
                fixture.gtin(), "Live acceptance fixture", "", "", "", "", "")));
        ExistingKiz existing = existingKiz(fixture.rawCode());
        if (existing == null) {
            long orderId = znack.createDraft(fixture.gtin(), 1);
            if (znack.insertCodes(orderId, fixture.gtin(), new DownloadedCodes(
                    List.of(fixture.rawCode()), "live-acceptance")) != 1) {
                throw new SafeFailure("kiz_inventory_insert_failed");
            }
        } else if (existing.shopId() != shop.getId() || !existing.gtin().equals(fixture.gtin())) {
            throw new SafeFailure("kiz_owned_by_other_inventory");
        } else if (!List.of("AVAILABLE", "CONSUMED").contains(existing.status())) {
            OzonExemplarJob job = new OzonExemplarJobRepository().find(shop.getId(), candidate.posting().postingNumber());
            if (job == null || !"RESERVED".equals(existing.status())) {
                throw new SafeFailure("kiz_not_available");
            }
        }
        new OzonProductGtinMappingRepository().put(shop.getId(), candidate.item().sku(), fixture.gtin());
        int available = new ZnackGtinInventoryService().availableCount(shop.getId(), fixture.gtin());
        OzonExemplarJob job = new OzonExemplarJobRepository().find(shop.getId(), candidate.posting().postingNumber());
        if (available < 1 && (job == null || job.stage() != OzonExemplarJobStage.ACCEPTED)) {
            throw new SafeFailure("kiz_not_available");
        }
        putConfig(KIZ_FINGERPRINT_KEY, fixture.fingerprint());
    }

    private static List<Candidate> eligibleCandidates(int shopId, Arguments arguments) throws IOException {
        String fixtureGtin = loadKizFixture(arguments.kizFile(), arguments.kizLine()).gtin();
        Map<String, String> mappings = new OzonProductGtinMappingRepository().findResolvedBySku(shopId);
        return new OzonPostingRepository().findByStatus(shopId, "awaiting_packaging", 500, 0).stream()
                .map(OzonLiveAcceptanceCli::candidate)
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> fixtureGtin.equals(mappings.get(candidate.item().sku())))
                .sorted(Comparator.comparing(
                        (Candidate value) -> value.posting().shipmentAt(), Comparator.reverseOrder()))
                .toList();
    }

    private static Candidate candidate(OzonPostingDto posting) {
        if (!isSingleUnitCandidate(posting)) return null;
        OzonPostingItemDto item = posting.items().getFirst();
        Set<String> mandatory = Set.copyOf(posting.requirements().mandatoryMarkProductIds());
        Set<String> optional = Set.copyOf(posting.requirements().optionalMarkProductIds());
        String marking = mandatory.contains(item.productId())
                ? "mandatory" : optional.contains(item.productId()) ? "optional" : "default";
        return new Candidate(posting, item, alias(posting.postingNumber()), marking, 1);
    }

    static boolean isSingleUnitCandidate(OzonPostingDto posting) {
        if (!"awaiting_packaging".equalsIgnoreCase(posting.status())
                || posting.requirements().blocksPreparation()
                || !posting.isSinglePackageSupported()
                || posting.items().size() != 1) return false;
        OzonPostingItemDto item = posting.items().getFirst();
        return item.quantity() == 1 && !item.sku().isBlank();
    }

    private static Candidate selectCandidate(List<Candidate> candidates, String requestedAlias) {
        if (requestedAlias != null && !requestedAlias.isBlank()) {
            List<Candidate> selected = candidates.stream()
                    .filter(candidate -> candidate.alias().equals(requestedAlias)).toList();
            if (selected.size() != 1) throw new SafeFailure("candidate_alias_not_found");
            return selected.getFirst();
        }
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static Shop ensureShop(OzonCredentials credentials) {
        ShopRepository repository = new ShopRepository();
        List<Shop> ozon = repository.findAll().stream()
                .filter(shop -> shop.getMarketplace() == Marketplace.OZON).toList();
        if (ozon.isEmpty()) {
            repository.insert(new Shop(SHOP_NAME, Marketplace.OZON, credentials.clientId(), credentials.apiKey()));
            ozon = repository.findAll().stream()
                    .filter(shop -> shop.getMarketplace() == Marketplace.OZON).toList();
        }
        if (ozon.size() != 1) throw new SafeFailure("ambiguous_live_shop");
        Shop shop = ozon.getFirst();
        if (!credentials.clientId().equals(shop.getClientId())) throw new SafeFailure("seller_account_changed");
        repository.update(shop.getId(), new Shop(
                shop.getId(), SHOP_NAME, Marketplace.OZON, credentials.clientId(), credentials.apiKey()));
        return repository.findById(shop.getId());
    }

    private static OzonCredentials loadCredentials(Path envFile) throws IOException {
        requireRegularFile(envFile, 64 * 1024L, "env_file_invalid");
        Map<String, String> allowed = new HashMap<>();
        for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("export ")) trimmed = trimmed.substring(7).strip();
            int separator = trimmed.indexOf('=');
            if (separator < 1) continue;
            String key = trimmed.substring(0, separator).strip();
            if (!Set.of("OZON_CLIENT_ID", "OZON_ACCESS_KEY").contains(key)) continue;
            allowed.put(key, unquote(trimmed.substring(separator + 1).strip()));
        }
        return new OzonCredentials(allowed.get("OZON_CLIENT_ID"), allowed.get("OZON_ACCESS_KEY"));
    }

    private static KizFixture loadKizFixture(Path kizFile, int lineNumber) throws IOException {
        if (lineNumber <= 1) throw new SafeFailure("retired_kiz_line_blocked");
        requireRegularFile(kizFile, 2 * 1024 * 1024L, "kiz_file_invalid");
        List<String> lines = Files.readAllLines(kizFile, StandardCharsets.UTF_8);
        if (lineNumber > lines.size()) throw new SafeFailure("kiz_line_missing");
        String raw = lines.get(lineNumber - 1);
        if (raw.isBlank() || raw.length() > 4096) throw new SafeFailure("kiz_line_invalid");
        String first = lines.isEmpty() ? "" : lines.getFirst();
        if (raw.equals(first)) throw new SafeFailure("retired_kiz_line_blocked");
        String gtin = extractGtin(raw);
        return new KizFixture(raw, gtin, fingerprint(raw));
    }

    private static String extractGtin(String raw) {
        String value = KizService.scannerSafeCode(raw);
        if (value != null && value.startsWith("01") && value.length() >= 16) {
            String gtin = value.substring(2, 16);
            if (gtin.matches("[0-9]{14}")) return gtin;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\(01\\)([0-9]{14})").matcher(value == null ? "" : value);
        if (matcher.find()) return matcher.group(1);
        throw new SafeFailure("kiz_gtin_unreadable");
    }

    private static OzonApiClient api(Shop shop) {
        return new OzonApiClient(shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
    }

    private static boolean containsMark(JsonElement element, String expected) {
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (("mark".equals(entry.getKey()) || "mandatory_mark".equals(entry.getKey()))
                        && entry.getValue().isJsonPrimitive()
                        && expected.equals(KizService.scannerSafeCode(entry.getValue().getAsString()))) return true;
                if (containsMark(entry.getValue(), expected)) return true;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) if (containsMark(child, expected)) return true;
        }
        return false;
    }

    private static int namedArrayItems(JsonElement element, String name) {
        if (element == null || element.isJsonNull()) return 0;
        int count = 0;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (name.equals(entry.getKey()) && entry.getValue().isJsonArray()) {
                    count += entry.getValue().getAsJsonArray().size();
                } else {
                    count += namedArrayItems(entry.getValue(), name);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) count += namedArrayItems(child, name);
        }
        return count;
    }

    private static int booleanOccurrences(JsonElement element, String name, boolean expected) {
        if (element == null || element.isJsonNull()) return 0;
        int count = 0;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (name.equals(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    try {
                        if (entry.getValue().getAsBoolean() == expected) count++;
                    } catch (RuntimeException ignored) {
                        // Malformed booleans are not counted as evidence.
                    }
                }
                count += booleanOccurrences(entry.getValue(), name, expected);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) count += booleanOccurrences(child, name, expected);
        }
        return count;
    }

    private static ExistingKiz existingKiz(String rawCode) {
        try (Connection connection = com.tuandev.fbsbarcode.config.Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT shop_id,gtin,status FROM kiz_codes WHERE raw_code=?")) {
            statement.setString(1, rawCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new ExistingKiz(
                        result.getInt(1), result.getString(2), result.getString(3)) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean localKizState(int shopId, String rawCode, String postingNumber, String status) {
        try (Connection connection = com.tuandev.fbsbarcode.config.Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM kiz_codes k
                        JOIN ozon_exemplars e ON e.kiz_id=k.id
                        WHERE k.shop_id=? AND k.raw_code=? AND k.status=? AND e.posting_number=?
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, rawCode);
            statement.setString(3, status);
            statement.setString(4, postingNumber);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int linkedKizCount(int shopId, String postingNumber) {
        try (Connection connection = com.tuandev.fbsbarcode.config.Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(DISTINCT kiz_id) FROM ozon_exemplars
                        WHERE shop_id=? AND posting_number=? AND kiz_id IS NOT NULL
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, postingNumber);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String labelStatus(int shopId, String postingNumber) {
        OzonLabelRepository.LabelJob job = new OzonLabelRepository().find(shopId, postingNumber);
        return job == null ? "NONE" : safeToken(job.status());
    }

    private static String requireSelectedPosting() {
        String value = getConfig(SELECTED_POSTING_KEY);
        if (value == null || value.isBlank()) throw new SafeFailure("posting_selection_missing");
        return OzonApiClient.requireExternalId(value, "posting number");
    }

    private static void putConfig(String key, String value) {
        try (Connection connection = com.tuandev.fbsbarcode.config.Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO app_config(key,value) VALUES(?,?)
                        ON CONFLICT(key) DO UPDATE SET value=excluded.value
                        """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String getConfig(String key) {
        try (Connection connection = com.tuandev.fbsbarcode.config.Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT value FROM app_config WHERE key=?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> evidenceFor(
            String candidate, String kiz, String stage, boolean shipReady,
            Verification verification, boolean duplicateSafe, boolean shipped, String labelStatus) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", verification.pass() && duplicateSafe ? "prepare_ok" : "prepare_incomplete");
        result.put("candidate", candidate);
        result.put("kizFingerprint", kiz);
        result.put("stage", stage);
        result.put("shipReady", shipReady);
        result.put("remoteMarkMatch", verification.remoteMarkMatch());
        result.put("remoteMarkPassed", verification.remotePassed());
        result.put("localKizConsumed", verification.localConsumed());
        result.put("oneLinkedKiz", verification.oneLinkedKiz());
        result.put("duplicateClickSafe", duplicateSafe);
        result.put("shippingPerformed", shipped);
        result.put("labelStatus", labelStatus);
        return result;
    }

    private static void writeEvidence(Path dataDir, String phase, Map<String, ?> values) throws IOException {
        Path evidenceDir = dataDir.resolve("evidence");
        Files.createDirectories(evidenceDir);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("phase", phase);
        document.put("recordedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        document.putAll(values);
        String body = JSON.toJson(document) + System.lineSeparator();
        Path latest = evidenceDir.resolve("ozon-live-latest.json");
        Path temporary = evidenceDir.resolve("ozon-live-latest.json.tmp");
        Files.writeString(temporary, body, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, latest, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, latest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Path history = evidenceDir.resolve("ozon-live-"
                + Instant.now().toString().replace(':', '-') + "-" + phase + ".json");
        Files.writeString(history, body, StandardCharsets.UTF_8);
    }

    private static void requireRegularFile(Path path, long maximumBytes, String error) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.size(normalized) > maximumBytes) throw new SafeFailure(error);
    }

    private static void secureDirectory(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalized)) {
            throw new SafeFailure("live_data_dir_unsafe");
        }
        Files.createDirectories(normalized);
        try {
            Files.setPosixFilePermissions(normalized, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACLs are handled by the OS-specific installer/runtime.
        }
    }

    private static void secureDatabaseFiles(Path directory) {
        for (String name : List.of("database.db", "database.db-wal", "database.db-shm")) {
            Path file = directory.resolve(name);
            try {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setPosixFilePermissions(file, EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                }
            } catch (IOException | UnsupportedOperationException ignored) {
                // Best effort after the exclusive migration/app-data lock is released.
            }
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String alias(String value) {
        return digest("posting:" + value).substring(0, 12);
    }

    private static String fingerprint(String value) {
        return digest(value).substring(0, 12);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String maskedIdentity(String value) {
        if (value == null || value.isBlank()) return "missing";
        return "client-…" + suffix(value.strip(), Math.min(4, value.strip().length()));
    }

    private static String suffix(String value, int length) {
        String safe = value == null ? "" : value.replaceAll("\\p{Cntrl}", "").strip();
        return safe.length() <= length ? safe : safe.substring(safe.length() - length);
    }

    private static String safeTimestamp(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.matches("[0-9TZ:+.\\-]{1,80}") ? value : "unknown";
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) return "NONE";
        String normalized = value.strip().replaceAll("[^A-Za-z0-9_:-]", "_");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static String safeError(Exception exception) {
        if (exception instanceof OzonApiException apiException) return apiException.kind();
        if (exception instanceof SafeFailure failure) return failure.code();
        if (exception instanceof IllegalArgumentException) return "invalid_live_input";
        if (exception instanceof IOException) return "io_failure";
        if (exception instanceof IllegalStateException) return "live_invariant_failed";
        return "internal_failure";
    }

    private static boolean isPostShipStatus(String status) {
        return status != null && switch (status.toLowerCase(Locale.ROOT)) {
            case "awaiting_deliver", "delivering", "delivered" -> true;
            default -> false;
        };
    }

    private record Candidate(
            OzonPostingDto posting, OzonPostingItemDto item, String alias, String marking, int quantity) {
    }

    private record KizFixture(String rawCode, String gtin, String fingerprint) {
    }

    private record ExistingKiz(int shopId, String gtin, String status) {
    }

    private record Verification(
            boolean remoteMarkMatch, boolean remotePassed, boolean localConsumed, boolean oneLinkedKiz) {
        boolean pass() {
            return remoteMarkMatch && remotePassed && localConsumed && oneLinkedKiz;
        }
    }

    private record Arguments(
            String command, Path dataDir, Path envFile, Path kizFile, int kizLine,
            String postingAlias, String confirmShip, String confirmReconcile) {
        static Arguments parse(String[] args) {
            if (args == null || args.length == 0) throw new SafeFailure("command_required");
            String command = args[0].strip().toLowerCase(Locale.ROOT);
            Map<String, String> options = new HashMap<>();
            for (int index = 1; index < args.length; index++) {
                String value = args[index];
                if (!value.startsWith("--") || !value.contains("=")) throw new SafeFailure("invalid_option");
                int separator = value.indexOf('=');
                options.put(value.substring(2, separator), value.substring(separator + 1));
            }
            Path defaultDataDir = Path.of(System.getProperty("user.home", "."), "WCode-live-acceptance");
            Path dataDir = Path.of(options.getOrDefault(
                    "data-dir", defaultDataDir.toString())).toAbsolutePath().normalize();
            Path env = Path.of(options.getOrDefault("env", ".env")).toAbsolutePath().normalize();
            Path kiz = Path.of(options.getOrDefault("kiz", "kiz.txt")).toAbsolutePath().normalize();
            int kizLine;
            try {
                kizLine = Integer.parseInt(options.getOrDefault("kiz-line", String.valueOf(DEFAULT_KIZ_LINE)));
            } catch (NumberFormatException exception) {
                throw new SafeFailure("invalid_kiz_line");
            }
            return new Arguments(command, dataDir, env, kiz, kizLine,
                    options.getOrDefault("posting-alias", ""), options.getOrDefault("confirm-ship", ""),
                    options.getOrDefault("confirm-reconcile", ""));
        }
    }

    private static final class SafeFailure extends RuntimeException {
        private final String code;

        private SafeFailure(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
