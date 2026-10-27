package com.vncode.app.integration.ozon;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AtomicFilePublisher;
import com.vncode.app.features.kiz.KizSourceSelection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.function.IntConsumer;

/**
 * Idempotent Ozon print orchestration: reserve KIZ locally, preserve official shipping pages,
 * replace redundant product labels with combined KIZ labels and create a separate picking list.
 * KIZ is never submitted to Ozon.
 */
public final class OzonPrintBundleService {
    private final OzonPostingRepository postings;
    private final OzonExemplarJobRepository jobs;
    private final OzonProductKizPolicyRepository policies;
    private final Preparation preparation;
    private final OfficialLabelDownloader labels;
    private final OzonKizLabelAppender kizLabels;
    private final OzonPickingListPdfExporter pickingLists;

    public OzonPrintBundleService() {
        this(
                new OzonPostingRepository(),
                new OzonExemplarJobRepository(),
                defaultPreparation(),
                defaultLabels());
    }

    public OzonPrintBundleService(KizSourceSelection sources){
        this(new OzonPostingRepository(),new OzonExemplarJobRepository(),defaultPreparation(new OzonExemplarService(),Objects.requireNonNull(sources)),defaultLabels());
    }
    private static OfficialLabelDownloader defaultLabels() {
        var service=new OzonLabelService();
        return new OfficialLabelDownloader() {
            public File download(Shop shop,String number,File target)throws IOException{return service.downloadOfficialPdf(shop,number,target);}
            public OfficialLabelDownloader forBatch(Shop shop,List<String> numbers){return new OzonBatchLabelDownloader(service,shop,numbers);}
        };
    }

    private static Preparation defaultPreparation() {
        return defaultPreparation(new OzonExemplarService());
    }

    static Preparation defaultPreparation(OzonExemplarService service) {return defaultPreparation(service,null);}
    static Preparation defaultPreparation(OzonExemplarService service,KizSourceSelection sources) {
        return new Preparation() {
            @Override public OzonPreparationResult prepare(Shop shop, String postingNumber) throws IOException {
                return service.openPrintBatch(shop,sources).stageForPrint(postingNumber);
            }
            @Override public boolean refreshEveryNewPrint() { return true; }
            @Override public boolean validateShopOwner() { return true; }
            @Override public Preparation forBatch(Shop shop) {
                OzonExemplarService.PrintBatch batch = service.openPrintBatch(shop,sources);
                return new Preparation() {
                    private final Map<String,OzonPreparationResult> prepared=new LinkedHashMap<>();
                    @Override public OzonPreparationResult prepare(Shop selected, String number) throws IOException {
                        MarketplaceGuard.requireOzon(selected);
                        if (selected.getId() != shop.getId()) throw new IOException("Ozon print shop changed.");
                        OzonPreparationResult result=prepared.remove(number);
                        if(result!=null)return result;
                        return batch.stageForPrint(number);
                    }
                    @Override public void prefetch(Shop selected,List<String> numbers)throws IOException {
                        MarketplaceGuard.requireOzon(selected);
                        if(selected.getId()!=shop.getId()||numbers.size()>2||!prepared.isEmpty())throw new IOException("Ozon print window changed.");
                        if(numbers.isEmpty())return;
                        var result=batch.stageForPrintBatch(numbers);
                        if(!result.failed().isEmpty())throw new IOException("An Ozon posting in the print window needs review.");
                        for(var value:result.results())prepared.put(value.postingNumber(),value);
                    }
                    @Override public boolean refreshEveryNewPrint() { return true; }
                    @Override public boolean validateShopOwner() { return true; }
                };
            }
        };
    }

    OzonPrintBundleService(
            OzonPostingRepository postings,
            OzonExemplarJobRepository jobs,
            Preparation preparation,
            OfficialLabelDownloader labels) {
        this(postings, jobs, preparation, labels, new OzonProductKizPolicyRepository(),
                new OzonKizLabelAppender(), new OzonPickingListPdfExporter());
    }

    OzonPrintBundleService(
            OzonPostingRepository postings,
            OzonExemplarJobRepository jobs,
            Preparation preparation,
            OfficialLabelDownloader labels,
            OzonKizLabelAppender kizLabels,
            OzonPickingListPdfExporter pickingLists) {
        this(postings, jobs, preparation, labels, new OzonProductKizPolicyRepository(), kizLabels, pickingLists);
    }

    OzonPrintBundleService(
            OzonPostingRepository postings,
            OzonExemplarJobRepository jobs,
            Preparation preparation,
            OfficialLabelDownloader labels,
            OzonProductKizPolicyRepository policies,
            OzonKizLabelAppender kizLabels,
            OzonPickingListPdfExporter pickingLists) {
        this.postings = Objects.requireNonNull(postings, "postings");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.labels = Objects.requireNonNull(labels, "labels");
        this.kizLabels = Objects.requireNonNull(kizLabels, "kizLabels");
        this.pickingLists = Objects.requireNonNull(pickingLists, "pickingLists");
    }

    public ExportResult export(Shop shop, String postingNumber, File labelTarget, File pickingTarget)
            throws IOException {
        MarketplaceGuard.requireOzon(shop);
        requirePdfTarget(labelTarget, "label bundle");
        requirePdfTarget(pickingTarget, "picking list");
        if (labelTarget.toPath().toAbsolutePath().normalize()
                .equals(pickingTarget.toPath().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Ozon label bundle and picking list must use different files.");
        }
        return exportInternal(shop, postingNumber, labelTarget, pickingTarget, true, null);
    }

    private ExportResult exportLabelOnly(
            Shop shop, String postingNumber, File labelTarget, PrintContext context) throws IOException {
        requirePdfTarget(labelTarget, "label bundle");
        return exportInternal(shop, postingNumber, labelTarget, null, false, context);
    }

    private ExportResult exportInternal(
            Shop shop,
            String postingNumber,
            File labelTarget,
            File pickingTarget,
            boolean consumeAfterPublish,
            PrintContext context)
            throws IOException {
        return exportInternal(shop,postingNumber,labelTarget,pickingTarget,consumeAfterPublish,context,null);
    }
    private ExportResult exportInternal(Shop shop,String postingNumber,File labelTarget,File pickingTarget,
            boolean consumeAfterPublish,PrintContext context,PdfDocument destination)throws IOException {
        String safePosting = OzonApiClient.requireExternalId(postingNumber, "posting number");
        OzonPostingDto posting = context == null ? postings.find(shop.getId(), safePosting)
                : context.postings.get(safePosting);
        if (posting == null) throw new IOException("The selected Ozon posting is not available locally. Refresh first.");

        OzonExemplarJob job = context == null ? jobs.find(shop.getId(), safePosting)
                : context.jobs.get(safePosting);
        // ACCEPTED stores a frozen already-printed binding; only that reprint is a recovery path.
        boolean newPrint = !accepted(job);
        if (newPrint) LicenseRuntime.require(Permission.PRINT_NEW);
        Set<String> exemptions = context == null ? policies.findExemptSkus(shop.getId()) : context.exemptions;
        Preparation operation = context == null ? preparation : context.preparation;
        boolean requiresKiz = OzonRequirementGuard.requiresAny(posting, exemptions);
        if ((newPrint && operation.refreshEveryNewPrint()) || (requiresKiz && !printable(job))) {
            requireNotInterrupted();
            LicenseRuntime.require(Permission.PRINT_NEW);
            OzonPreparationResult result = operation.prepare(shop, safePosting);
            if (!"ACCEPTED".equals(result.stage()) && !"VALIDATED".equals(result.stage())
                    && !"NOT_REQUIRED".equals(result.stage())) {
                throw new IOException("Ozon KIZ is not ready for printing yet (stage "
                        + safeStage(result.stage()) + ").");
            }
            posting = Objects.requireNonNullElse(postings.find(shop.getId(), safePosting), posting);
            job = jobs.find(shop.getId(), safePosting);
            if ("NOT_REQUIRED".equals(result.stage()) && OzonRequirementGuard.requiresAny(posting, exemptions)) {
                throw new IOException("An Ozon item requiring KIZ cannot be omitted from the print bundle.");
            }
            if (context != null) {
                context.postings.put(safePosting, posting);
                if (job != null) context.jobs.put(safePosting, job);
            }
            if (("ACCEPTED".equals(result.stage()) || "VALIDATED".equals(result.stage())) && !printable(job)) {
                throw new IOException("Ozon reported printable KIZ but the durable local job is incomplete.");
            }
        }

        List<OzonExemplarJobRepository.KizBinding> bindings = printable(job)
                ? jobs.bindings(job.id()) : List.of();
        List<OzonExemplarJobRepository.ExemplarSummary> summaries = printable(job)
                ? jobs.summaries(job.id()) : List.of();
        validatePrintableJob(job, bindings, summaries);

        File officialStaging = null;
        File labelStaging = null;
        File pickingStaging = null;
        try {
            if(destination==null)labelStaging = AtomicFilePublisher.stagingFile(labelTarget, ".bundle.pdf");
            if (pickingTarget != null) {
                pickingStaging = AtomicFilePublisher.stagingFile(pickingTarget, ".picking.pdf");
            }
            if (newPrint) LicenseRuntime.require(Permission.PRINT_NEW);
            Map<String, OzonProductDto> products = context == null ? catalogFor(shop, List.of(posting))
                    : context.products;
            OfficialLabelDownloader downloader=context==null?labels:context.labels;
            VerifiedCarrierPage carrier=destination==null?null:downloader.verifiedCarrierPage(shop,safePosting);
            int officialPages;
            if(carrier!=null){
                int first=destination.getNumberOfPages()+1;
                carrier.source().copyPagesTo(carrier.page(),carrier.page(),destination);
                if(kizLabels.append(destination,shop,posting,bindings,products)!=bindings.size())throw new IOException("The Ozon KIZ label page count is incomplete.");
                for(int page=first;page<=destination.getNumberOfPages();page++)destination.getPage(page).flush();
                officialPages=1;
            }else{
                officialStaging=AtomicFilePublisher.stagingFile(labelTarget,".official.pdf");
                downloader.download(shop,safePosting,officialStaging);
                officialPages=destination==null?compose(officialStaging,labelStaging,shop,posting,bindings,products):composeInto(officialStaging,destination,shop,posting,bindings,products);
            }
            if (pickingStaging != null) pickingLists.export(pickingStaging, shop, posting);
            File finishedLabels=labelStaging,finishedPicking=pickingStaging;OzonExemplarJob finishedJob=job;
            if(destination==null)ownedPublish(shop,operation.validateShopOwner(),newPrint,()->{
                AtomicFilePublisher.publish(finishedLabels,labelTarget);
                if(consumeAfterPublish&&!bindings.isEmpty())consumePrinted(finishedJob);
                if(finishedPicking!=null)AtomicFilePublisher.publish(finishedPicking,pickingTarget);
                if(consumeAfterPublish)new OzonPrintReceiptRepository().record(shop,List.of(safePosting));
            });
            labelStaging=null;pickingStaging=null;
            return new ExportResult(
                    labelTarget, pickingTarget, officialPages, bindings.size(), officialPages + bindings.size());
        } finally {
            AtomicFilePublisher.deleteQuietly(officialStaging);
            AtomicFilePublisher.deleteQuietly(labelStaging);
            AtomicFilePublisher.deleteQuietly(pickingStaging);
        }
    }

    /**
     * Exports every supplied posting into one label PDF and one picking PDF. Individual posting
     * files are built first and the two final files are only published after the entire batch has
     * completed, so a failed posting cannot leave a misleading partial "print all" result.
     */
    public BatchExportResult exportAll(
            Shop shop, List<String> postingNumbers, File labelTarget, File pickingTarget) throws IOException {
        return exportAll(shop, postingNumbers, labelTarget, pickingTarget, null);
    }

    public BatchExportResult exportAll(
            Shop shop, List<String> postingNumbers, File labelTarget, File pickingTarget,
            IntConsumer completed) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        // Freeze credentials/scope even if the user switches or edits the visible shop while working.
        Shop frozenShop = new Shop(shop.getId(), shop.getName(), shop.getMarketplace(), shop.getClientId(), shop.getApiKey());
        requirePdfTarget(labelTarget, "label batch");
        requirePdfTarget(pickingTarget, "picking batch");
        if (postingNumbers == null || postingNumbers.size() > 10_000) {
            throw new IllegalArgumentException("Invalid Ozon print batch size.");
        }
        List<String> safePostings = postingNumbers.stream()
                .map(value -> OzonApiClient.requireExternalId(value, "posting number")).distinct().toList();
        if (safePostings.isEmpty()) throw new IllegalArgumentException("At least one Ozon posting is required.");
        if (labelTarget.toPath().toAbsolutePath().normalize()
                .equals(pickingTarget.toPath().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Ozon label batch and picking batch must use different files.");
        }
        Map<String, OzonPostingDto> selected = postings.findMany(frozenShop.getId(), safePostings);
        if (selected.size() != safePostings.size()) {
            throw new IOException("An Ozon posting from the confirmed selection is not available in this shop.");
        }
        Map<String, OzonExemplarJob> selectedJobs = jobs.findMany(frozenShop.getId(), safePostings);
        boolean containsNewPrint = safePostings.stream().anyMatch(number -> !accepted(selectedJobs.get(number)));
        if (containsNewPrint) LicenseRuntime.require(Permission.PRINT_NEW);
        PrintContext context = new PrintContext(selected, selectedJobs, policies.findExemptSkus(frozenShop.getId()),
                catalogFor(frozenShop, selected.values()), preparation.forBatch(frozenShop));
        context.labels=labels.forBatch(frozenShop,safePostings);

        // Only the current posting's PDF part exists on disk; completed parts stream into staging.
        Path temporaryDirectory = Files.createTempDirectory("vncode-ozon-print-");
        File labelPart = temporaryDirectory.resolve("current-labels.pdf").toFile();
        File labelStaging = null;
        File pickingStaging = null;
        int totalPages = 0;
        int kizPages = 0;
        try (var databaseSession = Database.openBatchSession()) {
            labelStaging = AtomicFilePublisher.stagingFile(labelTarget, ".batch.pdf");
            pickingStaging = AtomicFilePublisher.stagingFile(pickingTarget, ".batch-picking.pdf");
            try (PdfDocument destination = new PdfDocument(new PdfWriter(labelStaging))) {
                for (int index = 0; index < safePostings.size(); index++) {
                    requireNotInterrupted();
                    if(index%2==0&&context.preparation.refreshEveryNewPrint()) {
                        List<String> window=safePostings.subList(index,Math.min(index+2,safePostings.size())).stream()
                                .filter(number->!accepted(context.jobs.get(number))).toList();
                        context.preparation.prefetch(frozenShop,window);
                    }
                    ExportResult result = exportInternal(frozenShop,safePostings.get(index),labelPart,null,false,context,destination);
                    totalPages += result.totalPages();
                    kizPages += result.kizPages();
                    if (completed != null) completed.accept(index + 1);
                }
            }
            requireNotInterrupted();
            List<OzonPostingDto> current = safePostings.stream().map(context.postings::get).toList();
            pickingLists.exportBatch(pickingStaging, frozenShop, current);
            requireNotInterrupted();
            File finishedLabels=labelStaging,finishedPicking=pickingStaging;
            ownedPublish(frozenShop,context.preparation.validateShopOwner(),containsNewPrint,()->{
                AtomicFilePublisher.publish(finishedLabels,labelTarget);
                jobs.consumePrintedBatch(frozenShop.getId(),safePostings);
                AtomicFilePublisher.publish(finishedPicking,pickingTarget);
                new OzonPrintReceiptRepository().record(frozenShop,safePostings);
            });
            labelStaging=null;pickingStaging=null;
            return new BatchExportResult(labelTarget, pickingTarget, safePostings.size(), totalPages, kizPages);
        } finally {
            context.labels.close();
            AtomicFilePublisher.deleteQuietly(labelStaging);
            AtomicFilePublisher.deleteQuietly(pickingStaging);
            AtomicFilePublisher.deleteQuietly(labelPart);
            try { Files.deleteIfExists(temporaryDirectory); } catch (IOException ignored) { }
        }
    }

    private static void requireNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Ozon print export was interrupted.");
    }

    @FunctionalInterface private interface PublishAction {void run()throws IOException;}
    private static void ownedPublish(Shop shop,boolean strict,boolean newPrint,PublishAction publish)throws IOException {
        if(strict) {
            com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                if(newPrint)LicenseRuntime.require(Permission.PRINT_NEW);
                publish.run();return null;
            });
        } else {if(newPrint)LicenseRuntime.require(Permission.PRINT_NEW);publish.run();}
    }

    private static Map<String, OzonProductDto> catalogFor(Shop shop, java.util.Collection<OzonPostingDto> postings) {
        List<String> skus = postings.stream().flatMap(posting -> posting.items().stream())
                .map(OzonPostingItemDto::sku).filter(value -> !value.isBlank()).distinct().toList();
        Map<String, OzonProductDto> products = new LinkedHashMap<>();
        OzonCatalogRepository catalog = new OzonCatalogRepository();
        for (int start = 0; start < skus.size(); start += 500) {
            products.putAll(catalog.findBySkusForPrinting(shop.getId(), skus.subList(start, Math.min(start + 500, skus.size()))));
        }
        return Map.copyOf(products);
    }

    private static final class PrintContext {
        final Map<String, OzonPostingDto> postings;
        final Map<String, OzonExemplarJob> jobs;
        final Set<String> exemptions;
        final Map<String, OzonProductDto> products;
        final Preparation preparation;
        OfficialLabelDownloader labels;
        PrintContext(Map<String, OzonPostingDto> postings, Map<String, OzonExemplarJob> jobs,
                Set<String> exemptions, Map<String, OzonProductDto> products, Preparation preparation) {
            this.postings = new LinkedHashMap<>(postings);
            this.jobs = new LinkedHashMap<>(jobs);
            this.exemptions = Set.copyOf(exemptions);
            this.products = products;
            this.preparation = preparation;
        }
    }

    private int compose(
            File official,
            File target,
            Shop shop,
            OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings,
            Map<String, OzonProductDto> products) throws IOException {
        try(var destination=new PdfDocument(new PdfWriter(target))) {
            return composeInto(official,destination,shop,posting,bindings,products);
        }
    }
    private int composeInto(File official,PdfDocument destination,Shop shop,OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings,Map<String,OzonProductDto> products)throws IOException {
        if(!Files.isRegularFile(official.toPath()))throw new IOException("OZON_LABEL_FILE_MISSING");
        try(PdfDocument source=new PdfDocument(new PdfReader(official))) {
            int firstPage=destination.getNumberOfPages()+1;
            if (source.getNumberOfPages() < 1) {
                throw new IOException("The official Ozon shipping label PDF has no pages.");
            }
            List<Integer> retainedPages = OzonOfficialLabelSelector.retainedPages(
                    official, source, posting, bindings, products);
            int bindingIndex = 0;
            int appended = 0;
            // Shipping pages retain their exact content and dimensions. A product-only page
            // already covered by the combined labels is omitted instead of printing it twice.
            for (int page : retainedPages) {
                source.copyPagesTo(page, page, destination);
                if (bindingIndex < bindings.size()) {
                    appended += kizLabels.append(
                            destination, shop, posting, List.of(bindings.get(bindingIndex++)), products);
                }
            }
            if (bindingIndex < bindings.size()) {
                appended += kizLabels.append(
                        destination, shop, posting, bindings.subList(bindingIndex, bindings.size()), products);
            }
            if (appended != bindings.size()) {
                throw new IOException("The Ozon KIZ label page count is incomplete.");
            }
            destination.flushCopiedObjects(source);
            for(int page=firstPage;page<=destination.getNumberOfPages();page++)destination.getPage(page).flush();
            return retainedPages.size();
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("The Ozon print bundle could not be composed.", exception);
        }
    }

    private static void validatePrintableJob(
            OzonExemplarJob job,
            List<OzonExemplarJobRepository.KizBinding> bindings,
            List<OzonExemplarJobRepository.ExemplarSummary> summaries) throws IOException {
        if (!printable(job)) return;
        if (bindings.isEmpty() || summaries.size() != bindings.size()) {
            throw new IOException("The validated Ozon KIZ job is incomplete and cannot be printed.");
        }
        boolean allBound = summaries.stream().allMatch(value -> value.kizId() != null);
        if (!allBound) throw new IOException("Only locally bound Ozon KIZ exemplars can be printed.");
        if (accepted(job)) {
            boolean allCompleted = summaries.stream().allMatch(value ->
                    "passed".equalsIgnoreCase(value.checkStatus())
                            || "printed".equalsIgnoreCase(value.checkStatus()));
            if (!allCompleted) throw new IOException("Only completed Ozon KIZ labels can be reprinted.");
        }
    }

    private void consumePrinted(OzonExemplarJob job) throws IOException {
        if (job == null) throw new IOException("The durable Ozon KIZ print job is missing.");
        try {
            jobs.consumePrinted(job);
        } catch (RuntimeException exception) {
            throw new IOException(
                    "The Ozon label PDF was created, but VN code could not mark its KIZ as printed.", exception);
        }
    }

    private static boolean accepted(OzonExemplarJob job) {
        return job != null && job.stage() == OzonExemplarJobStage.ACCEPTED;
    }

    private static boolean printable(OzonExemplarJob job) {
        return job != null && (job.stage() == OzonExemplarJobStage.VALIDATED || accepted(job));
    }

    private static void requirePdfTarget(File target, String label) {
        if (target == null || !target.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Ozon " + label + " target must be a PDF file.");
        }
    }

    private static String safeStage(String value) {
        return value != null && value.matches("[A-Z_]{1,64}") ? value : "UNKNOWN";
    }

    @FunctionalInterface
    interface Preparation {
        OzonPreparationResult prepare(Shop shop, String postingNumber) throws IOException;
        default boolean refreshEveryNewPrint() { return false; }
        default Preparation forBatch(Shop shop) { return this; }
        default boolean validateShopOwner(){return false;}
        default void prefetch(Shop shop,List<String> numbers)throws IOException {}
    }

    @FunctionalInterface
    interface OfficialLabelDownloader extends AutoCloseable {
        File download(Shop shop, String postingNumber, File target) throws IOException;
        default OfficialLabelDownloader forBatch(Shop shop,List<String> numbers)throws IOException{return this;}
        /** Borrowed page from a shared PDF whose posting identity has already been verified. */
        default VerifiedCarrierPage verifiedCarrierPage(Shop shop,String postingNumber)throws IOException{return null;}
        @Override default void close(){}
    }

    record VerifiedCarrierPage(PdfDocument source,int page) {
        VerifiedCarrierPage {Objects.requireNonNull(source);if(page<1||page>source.getNumberOfPages())throw new IllegalArgumentException("Invalid verified carrier page");}
    }

    public record ExportResult(
            File labelFile,
            File pickingFile,
            int officialPages,
            int kizPages,
            int totalPages) {
    }

    public record BatchExportResult(
            File labelFile,
            File pickingFile,
            int postingCount,
            int totalPages,
            int kizPages) {
    }
}
