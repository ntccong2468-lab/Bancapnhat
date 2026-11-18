package com.vncode.app.integration.ozon;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.vncode.app.features.kiz.status.Kn3PrintProjection;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AtomicFilePublisher;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Local PDF fallback. Never downloads carrier labels, prepares/submits marks, ships or purchases. */
public final class OzonKizOnlyPrintService {
    public enum Issue { POSTING_MISSING, KIZ_NOT_PREPARED, BINDING_CHANGED, PAID_NOT_CONFIRMED, INVALID_KIZ }
    private static final int MAX_POSTINGS = 10_000;
    private static final int MAX_LABELS = 100_000;
    private final OzonPostingRepository postings = new OzonPostingRepository();
    private final OzonExemplarJobRepository jobs = new OzonExemplarJobRepository();
    private final OzonKizOnlyLabelRenderer renderer = new OzonKizOnlyLabelRenderer();
    private final Runnable afterRender;
    private final Publisher publisher;

    @FunctionalInterface interface Publisher { void publish(File staging, File target) throws IOException; }
    public OzonKizOnlyPrintService() { this(() -> {}, AtomicFilePublisher::publish); }
    OzonKizOnlyPrintService(Runnable afterRender, Publisher publisher) {
        this.afterRender = Objects.requireNonNull(afterRender);
        this.publisher = Objects.requireNonNull(publisher);
    }

    /** Cancellation is cooperative. Once publication starts, report its real result instead of cancelled. */
    public static final class Cancellation {
        private boolean requested;
        private boolean publishing;
        public synchronized boolean request() {
            if (publishing) return false;
            requested = true; return true;
        }
        public synchronized boolean requested() { return requested; }
        private synchronized void check() throws IOException {
            interrupted();
            if (requested) throw new IOException("OZON_KIZ_ONLY_CANCELLED");
        }
        private synchronized void beginPublication() throws IOException {
            check(); publishing = true;
        }
    }

    private record Entry(OzonPostingDto posting, OzonExemplarJob job,
            List<OzonExemplarJobRepository.KizBinding> bindings, List<String> codes,
            Map<String, OzonProductDto> products, OzonExemplarJobRepository.PrintProof proof) {
        @Override public String toString() { return "OzonKizOnlyEntry[redacted]"; }
    }

    /** Raw codes remain private and are never exposed through diagnostics or toString. */
    public static final class Plan {
        private final int shopId;
        private final String owner;
        private final java.nio.file.Path dataDirectory;
        private final List<Entry> entries;
        private final List<String> requested;
        private final Map<String, Issue> failures;
        private final boolean orderingUncertain;
        private Plan(Shop shop, List<Entry> entries, List<String> requested,
                Map<String, Issue> failures, boolean orderingUncertain) {
            this.shopId = shop.getId(); this.owner = ListingIdentity.fingerprint(shop);
            this.dataDirectory = com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize();
            this.entries = List.copyOf(entries); this.requested = List.copyOf(requested);
            this.failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
            this.orderingUncertain = orderingUncertain;
        }
        public List<String> postingNumbers() { return entries.stream().map(e -> e.posting().postingNumber()).toList(); }
        public int requestedCount() { return requested.size(); }
        public int labelCount() { return entries.stream().mapToInt(e -> e.bindings().size()).sum(); }
        public Map<String, Issue> failures() { return failures; }
        public boolean orderingUncertain() { return orderingUncertain; }
        @Override public String toString() { return "OzonKizOnlyPlan[" + entries.size() + " postings,redacted]"; }
    }

    public record Result(File pdf, List<String> postingNumbers, int labels,
            Map<String, Issue> failures, boolean orderingUncertain) {
        public Result { postingNumbers = List.copyOf(postingNumbers); failures = Map.copyOf(failures); }
    }

    /** A read-only review; an unprepared posting is reported instead of causing any API action. */
    public Plan inspect(Shop shop, List<String> postingNumbers) throws IOException {
        requireOwner(shop);
        var dataDirectory = com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize();
        List<String> requested = selection(postingNumbers);
        var currentPostings = postings.findMany(shop.getId(), requested);
        var currentJobs = jobs.findMany(shop.getId(), requested);
        var ordered = currentPostings.values().stream().sorted(Comparator
                .comparing((OzonPostingDto p) -> time(p.inProcessAt()))
                .thenComparing(OzonPostingDto::postingNumber)).toList();
        var failures = new LinkedHashMap<String, Issue>();
        var exemptions = new OzonProductKizPolicyRepository().findExemptSkus(shop.getId());
        requested.stream().filter(n -> !currentPostings.containsKey(n)).forEach(n -> failures.put(n, Issue.POSTING_MISSING));
        var entries = new ArrayList<Entry>(); int labels = 0;
        var seenTimes = new HashSet<Instant>(); boolean uncertain = false;
        for (var posting : ordered) {
            interrupted();
            Instant at = time(posting.inProcessAt());
            if (at.equals(Instant.MAX) || !seenTimes.add(at)) uncertain = true;
            var job = currentJobs.get(posting.postingNumber());
            if (job == null || !Set.of(OzonExemplarJobStage.VALIDATED, OzonExemplarJobStage.ACCEPTED).contains(job.stage())) {
                failures.put(posting.postingNumber(), Issue.KIZ_NOT_PREPARED); continue;
            }
            var bindings = jobs.bindings(job.id());
            var summaries = jobs.summaries(job.id());
            if (!validBindings(shop, posting, job, bindings, exemptions) || summaries.size() != bindings.size()
                    || summaries.stream().anyMatch(s -> s.kizId() == null)
                    || job.stage() == OzonExemplarJobStage.ACCEPTED && summaries.stream().anyMatch(s ->
                        !Set.of("passed", "printed").contains(Objects.toString(s.checkStatus(), "").toLowerCase(Locale.ROOT)))) {
                failures.put(posting.postingNumber(), Issue.BINDING_CHANGED); continue;
            }
            if (OzonPurchasedKizAssignment.required(shop, job, null) && !OzonPurchasedKizAssignment.acceptedProof(shop, job)) {
                failures.put(posting.postingNumber(), Issue.PAID_NOT_CONFIRMED); continue;
            }
            List<String> codes;
            try { codes = projected(shop, posting, bindings); for (String code : codes) renderer.validate(code); }
            catch (IOException invalid) { failures.put(posting.postingNumber(), Issue.INVALID_KIZ); continue; }
            var products = new OzonCatalogRepository().findBySkusForPrinting(shop.getId(),
                    posting.items().stream().map(OzonPostingItemDto::sku).distinct().toList());
            if (bindings.stream().anyMatch(b -> product(posting, b, products) == null)) {
                failures.put(posting.postingNumber(), Issue.BINDING_CHANGED); continue;
            }
            labels = Math.addExact(labels, bindings.size());
            if (labels > MAX_LABELS) throw new IOException("OZON_KIZ_ONLY_LIMIT");
            entries.add(new Entry(posting, job, List.copyOf(bindings), codes, Map.copyOf(products),
                    OzonExemplarJobRepository.printProof(job, bindings)));
        }
        requireOwner(shop);
        if (!dataDirectory.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize()))
            throw new IOException("OZON_KIZ_ONLY_OWNER_CHANGED");
        return new Plan(shop, entries, requested, failures, uncertain);
    }

    public Result export(Shop shop, Plan plan, File target) throws IOException {
        return export(shop, plan, target, new Cancellation());
    }

    public Result export(Shop shop, Plan plan, File target, Cancellation cancellation) throws IOException {
        Objects.requireNonNull(cancellation, "cancellation").check();
        Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(target, "target"); requirePlanOwner(shop, plan);
        if (plan.entries.isEmpty()) throw new IOException("OZON_KIZ_ONLY_NOT_READY");
        if (!target.getName().toLowerCase(Locale.ROOT).endsWith(".pdf") || Files.isDirectory(target.toPath()))
            throw new IOException("OZON_KIZ_ONLY_INVALID_TARGET");
        boolean newPrint = plan.entries.stream().anyMatch(e -> e.job().stage() != OzonExemplarJobStage.ACCEPTED);
        if (newPrint) LicenseRuntime.require(Permission.PRINT_NEW);
        File staging = AtomicFilePublisher.stagingFile(target, ".kiz-only.pdf");
        boolean[] published = {false};
        try {
            try (var document = new PdfDocument(new PdfWriter(staging))) {
                int sequence = 0;
                for (var entry : plan.entries) {
                    cancellation.check(); sequence++;
                    for (int i = 0; i < entry.bindings().size(); i++) {
                        var binding = entry.bindings().get(i);
                        renderer.append(document, item(entry.posting(), binding), product(entry.posting(), binding, entry.products()),
                                entry.codes().get(i), entry.posting().postingNumber(), sequence, i + 1, entry.bindings().size());
                        document.getLastPage().flush();
                    }
                }
            }
            afterRender.run(); cancellation.check();
            ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
                requirePlanOwner(shop, plan);
                if (newPrint) LicenseRuntime.require(Permission.PRINT_NEW);
                var proofs = new LinkedHashMap<String, OzonExemplarJobRepository.PrintProof>();
                for (var entry : plan.entries) proofs.put(entry.posting().postingNumber(), entry.proof());
                jobs.consumePrintedKizBatch(shop.getId(), proofs, () -> {
                    try {
                        cancellation.check(); requirePlanOwner(shop, plan);
                        if (newPrint) LicenseRuntime.require(Permission.PRINT_NEW);
                        for (var entry : plan.entries) requireUnchanged(shop, entry);
                        cancellation.beginPublication();
                        publisher.publish(staging, target); published[0] = true;
                    } catch (IOException failure) { throw new UncheckedIOException(failure); }
                });
                return null;
            });
            return new Result(target, plan.postingNumbers(), plan.labelCount(), plan.failures(), plan.orderingUncertain());
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
        catch (RuntimeException failure) {
            if (failure instanceof com.vncode.app.features.license.LicenseGate.LicenseDeniedException) throw failure;
            throw new IOException(published[0] ? "OZON_KIZ_ONLY_PUBLISHED_RECONCILIATION_REQUIRED" : "OZON_KIZ_ONLY_BINDING_CHANGED", failure);
        } finally { AtomicFilePublisher.deleteQuietly(staging); }
    }

    private void requireUnchanged(Shop shop, Entry entry) throws IOException {
        var posting = postings.find(shop.getId(), entry.posting().postingNumber());
        var job = jobs.find(shop.getId(), entry.posting().postingNumber());
        if (posting == null || job == null || job.id() != entry.job().id()
                || !posting.items().equals(entry.posting().items()) || !posting.inProcessAt().equals(entry.posting().inProcessAt())
                || !projected(shop, posting, entry.bindings()).equals(entry.codes()))
            throw new IOException("OZON_KIZ_ONLY_BINDING_CHANGED");
        if (OzonPurchasedKizAssignment.required(shop, job, null) && !OzonPurchasedKizAssignment.acceptedProof(shop, job))
            throw new IOException("OZON_KIZ_ONLY_PAID_NOT_CONFIRMED");
    }

    private static boolean validBindings(Shop shop, OzonPostingDto posting, OzonExemplarJob job,
            List<OzonExemplarJobRepository.KizBinding> bindings, Set<String> exemptions) {
        if (job.shopId() != shop.getId() || !job.postingNumber().equals(posting.postingNumber()) || bindings.isEmpty()) return false;
        var slots = new HashSet<String>(); var codes = new HashSet<Long>();
        for (var binding : bindings) {
            var item = item(posting, binding);
            if (item == null || !item.productId().equals(binding.productId()) || binding.rawCode() == null || binding.rawCode().isBlank()
                    || binding.exemplarIndex() < 0 || binding.exemplarIndex() >= item.quantity()
                    || !slots.add(binding.itemIndex() + ":" + binding.exemplarIndex()) || !codes.add(binding.kizId())) return false;
        }
        // A partially retained set of slots must never be presented as all labels for this product.
        for (var item : posting.items()) {
            long count = bindings.stream().filter(b -> b.itemIndex() == item.itemIndex()).count();
            if (count > 0 && count != item.quantity()) return false;
            boolean required = posting.requirements().mandatoryMarkProductIds().contains(item.productId()) || !exemptions.contains(item.sku());
            if (required && count != item.quantity()) return false;
        }
        return true;
    }

    private static List<String> projected(Shop shop, OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings) throws IOException {
        return Kn3PrintProjection.ozonReservedCodes(shop, posting.postingNumber(), bindings.stream()
                .map(b -> new Kn3PrintProjection.Slot(b.productId(), b.exemplarId(), b.rawCode())).toList());
    }
    private static OzonPostingItemDto item(OzonPostingDto posting, OzonExemplarJobRepository.KizBinding binding) {
        return posting.items().stream().filter(i -> i.itemIndex() == binding.itemIndex()).findFirst().orElse(null);
    }
    private static OzonProductDto product(OzonPostingDto posting, OzonExemplarJobRepository.KizBinding binding,
            Map<String, OzonProductDto> products) {
        var item = item(posting, binding); if (item == null) return null;
        var product = products.get(item.sku());
        return product != null && product.productId().equals(item.productId()) ? product : null;
    }
    private static List<String> selection(List<String> numbers) {
        if (numbers == null || numbers.isEmpty() || numbers.size() > MAX_POSTINGS) throw new IllegalArgumentException("OZON_KIZ_ONLY_LIMIT");
        var safe = numbers.stream().map(n -> OzonApiClient.requireExternalId(n, "posting number")).toList();
        if (new HashSet<>(safe).size() != safe.size()) throw new IllegalArgumentException("OZON_KIZ_ONLY_DUPLICATE_SELECTION");
        return safe;
    }
    private static Instant time(String value) {
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException | NullPointerException missing) { return Instant.MAX; }
    }
    private static void requireOwner(Shop shop) { MarketplaceGuard.requireOzon(shop); ListingIdentity.require(shop); }
    private static void requirePlanOwner(Shop shop, Plan plan) throws IOException {
        requireOwner(shop);
        if (shop.getId() != plan.shopId || !ListingIdentity.fingerprint(shop).equals(plan.owner)
                || !com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize().equals(plan.dataDirectory))
            throw new IOException("OZON_KIZ_ONLY_OWNER_CHANGED");
    }
    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("OZON_KIZ_ONLY_CANCELLED");
    }
}
