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
 * exclude standalone product labels, append combined KIZ labels and create a separate picking list.
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
            public File download(Shop shop,String number,File target)throws IOException{
                OzonPrintTiming.isolatedLabel();long started=OzonPrintTiming.tick();
                try { return service.downloadOfficialPdf(shop,number,target); }
                finally { OzonPrintTiming.add(OzonPrintTiming.Stage.LABEL_NETWORK,started); }
            }
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
                    private final Map<String,String> failures=new LinkedHashMap<>();
                    private final Map<String,OzonApiException> apiFailures=new LinkedHashMap<>();
                    @Override public OzonPreparationResult prepare(Shop selected, String number) throws IOException {
                        MarketplaceGuard.requireOzon(selected);
                        if (selected.getId() != shop.getId()) throw new IOException("Ozon print shop changed.");
                        OzonApiException apiFailure=apiFailures.remove(number);
                        String failure=failures.remove(number);
                        if(apiFailure!=null)throw apiFailure;
                        if(failure!=null)throw new IOException(failure);
                        OzonPreparationResult result=prepared.remove(number);
                        if(result!=null)return result;
                        return batch.stageForPrint(number);
                    }
                    @Override public void prefetch(Shop selected,List<String> numbers)throws IOException {
                        MarketplaceGuard.requireOzon(selected);
                        if(selected.getId()!=shop.getId()||numbers.size()>OzonExemplarService.PRINT_BATCH_SIZE||!prepared.isEmpty()||!failures.isEmpty())throw new IOException("Ozon print window changed.");
                        if(numbers.isEmpty())return;
                        var result=batch.stageForPrintBatch(numbers);
                        for(var value:result.results())prepared.put(value.postingNumber(),value);
                        failures.putAll(result.failed());
                        apiFailures.putAll(result.apiFailures());
                    }
                    @Override public void discard(String number){prepared.remove(number);failures.remove(number);apiFailures.remove(number);}
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
        var prepared=preparePrint(shop,postingNumber,context);
        return composePrepared(shop,labelTarget,pickingTarget,consumeAfterPublish,context,destination,prepared);
    }
    private record PreparedPrint(OzonPostingDto posting,List<OzonExemplarJobRepository.KizBinding> bindings,
            OzonExemplarJobRepository.PrintProof proof,boolean newPrint,Preparation operation){}
    private PreparedPrint preparePrint(Shop shop,String postingNumber,PrintContext context)throws IOException {
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
            long prepareStarted = OzonPrintTiming.tick();
            OzonPreparationResult result;
            try { result = operation.prepare(shop, safePosting); }
            finally { OzonPrintTiming.add(OzonPrintTiming.Stage.PREPARATION, prepareStarted); }
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
        var proof=bindings.isEmpty()?null:OzonExemplarJobRepository.printProof(job,bindings);
        if(context!=null&&proof!=null)context.printProofs.put(safePosting,proof);
        return new PreparedPrint(posting,bindings,proof,newPrint,operation);

    }
    private ExportResult composePrepared(Shop shop,File labelTarget,File pickingTarget,boolean consumeAfterPublish,
            PrintContext context,PdfDocument destination,PreparedPrint prepared)throws IOException {
        var posting=prepared.posting();var bindings=prepared.bindings();var proof=prepared.proof();
        boolean newPrint=prepared.newPrint();var operation=prepared.operation();String safePosting=posting.postingNumber();

        File officialStaging = null;
        File labelStaging = null;
        File pickingStaging = null;
        Map<String,String> carrierNumbers=context==null?new LinkedHashMap<>():context.carrierNumbers;
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
                long composeStarted=OzonPrintTiming.tick();
                try {
                    int first=destination.getNumberOfPages()+1;
                    carrierNumbers.putAll(OzonCarrierLabelNumbers.read(shop,null,carrier.source(),List.of(carrier.page()),Map.of(safePosting,posting)));
                    carrier.source().copyPagesTo(carrier.page(),carrier.page(),destination);
                    if(kizLabels.append(destination,shop,posting,bindings,products)!=bindings.size())throw new IOException("The Ozon KIZ label page count is incomplete.");
                    for(int page=first;page<=destination.getNumberOfPages();page++)destination.getPage(page).flush();
                } finally { OzonPrintTiming.add(OzonPrintTiming.Stage.COMPOSITION,composeStarted); }
                officialPages=1;
            }else{
                officialStaging=AtomicFilePublisher.stagingFile(labelTarget,".official.pdf");
                downloader.download(shop,safePosting,officialStaging);
                long composeStarted=OzonPrintTiming.tick();
                try { officialPages=destination==null?compose(officialStaging,labelStaging,shop,posting,bindings,products,carrierNumbers):composeInto(officialStaging,destination,shop,posting,bindings,products,carrierNumbers); }
                finally { OzonPrintTiming.add(OzonPrintTiming.Stage.COMPOSITION,composeStarted); }
            }
            if (pickingStaging != null) {
                long pickingStarted=OzonPrintTiming.tick();
                try { pickingLists.exportBatch(pickingStaging, shop, List.of(posting),carrierNumbers); }
                finally { OzonPrintTiming.add(OzonPrintTiming.Stage.PICKING,pickingStarted); }
            }
            File finishedLabels=labelStaging,finishedPicking=pickingStaging;
            if(destination==null)ownedPublish(shop,operation.validateShopOwner(),newPrint,()->{
                if(consumeAfterPublish)publishPrintedBundle(shop,List.of(safePosting),
                        proof==null?Map.of():Map.of(safePosting,proof),finishedPicking,pickingTarget,finishedLabels,labelTarget,operation.validateShopOwner());
                else AtomicFilePublisher.publish(finishedLabels,labelTarget);
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
        if(Files.exists(labelTarget.toPath())&&Files.exists(pickingTarget.toPath())&&Files.isSameFile(labelTarget.toPath(),pickingTarget.toPath()))throw new IllegalArgumentException("Ozon label batch and picking batch must use different files.");
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
                    if(index%OzonExemplarService.PRINT_BATCH_SIZE==0&&context.preparation.refreshEveryNewPrint()) {
                        List<String> window=safePostings.subList(index,Math.min(index+OzonExemplarService.PRINT_BATCH_SIZE,safePostings.size())).stream()
                                .filter(number->!accepted(context.jobs.get(number))).toList();
                        long prepareStarted=OzonPrintTiming.tick();
                        try { context.preparation.prefetch(frozenShop,window); }
                        finally { OzonPrintTiming.add(OzonPrintTiming.Stage.PREPARATION,prepareStarted); }
                    }
                    ExportResult result = exportInternal(frozenShop,safePostings.get(index),labelPart,null,false,context,destination);
                    totalPages += result.totalPages();
                    kizPages += result.kizPages();
                    if (completed != null) completed.accept(index + 1);
                }
            }
            requireNotInterrupted();
            List<OzonPostingDto> current = safePostings.stream().map(context.postings::get).toList();
            long pickingStarted=OzonPrintTiming.tick();
            try { pickingLists.exportBatch(pickingStaging, frozenShop, current,context.carrierNumbers); }
            finally { OzonPrintTiming.add(OzonPrintTiming.Stage.PICKING,pickingStarted); }
            requireNotInterrupted();
            File finishedLabels=labelStaging,finishedPicking=pickingStaging;
            var frozenProofs=Map.copyOf(context.printProofs);
            ownedPublish(frozenShop,context.preparation.validateShopOwner(),containsNewPrint,()->{
                publishPrintedBundle(frozenShop,safePostings,frozenProofs,finishedPicking,pickingTarget,finishedLabels,labelTarget,context.preparation.validateShopOwner());
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

    /**
     * Export individually verified postings while preserving failures for review. Each posting is
     * composed in an isolated in-memory document before its pages enter the final file, so a
     * failed posting cannot leak a partial label or consume its reserved KIZ.
     */
    public AvailableBatchExportResult exportAvailable(Shop shop,List<String> postingNumbers,
            File labelTarget,File pickingTarget,IntConsumer completed)throws IOException {
        return exportAvailableInternal(shop,postingNumbers,labelTarget,pickingTarget,completed,Map.of(),false);
    }
    /** Try every selected carrier label, keeping unavailable KIZ out of the PDF and its consumption. */
    public AvailableBatchExportResult exportRequested(Shop shop,List<String> postingNumbers,
            File labelTarget,File pickingTarget,IntConsumer completed,Map<String,String> carrierOnlyReasons)throws IOException {
        return exportAvailableInternal(shop,postingNumbers,labelTarget,pickingTarget,completed,carrierOnlyReasons,true);
    }
    private AvailableBatchExportResult exportAvailableInternal(Shop shop,List<String> postingNumbers,
            File labelTarget,File pickingTarget,IntConsumer completed,Map<String,String> carrierOnlyReasons,boolean allowCarrierFallback)throws IOException {
        MarketplaceGuard.requireOzon(shop);
        Shop frozen=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        requirePdfTarget(labelTarget,"label batch");requirePdfTarget(pickingTarget,"picking batch");
        if(postingNumbers==null||postingNumbers.size()>10_000)throw new IllegalArgumentException("Invalid Ozon print batch size.");
        List<String> selected=postingNumbers.stream().map(value->OzonApiClient.requireExternalId(value,"posting number")).distinct().toList();
        if(selected.isEmpty())throw new IllegalArgumentException("At least one Ozon posting is required.");
        if(labelTarget.toPath().toAbsolutePath().normalize().equals(pickingTarget.toPath().toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Ozon label batch and picking batch must use different files.");
        if(Files.exists(labelTarget.toPath())&&Files.exists(pickingTarget.toPath())&&Files.isSameFile(labelTarget.toPath(),pickingTarget.toPath()))throw new IllegalArgumentException("Ozon label batch and picking batch must use different files.");
        var carrierOnly=new LinkedHashMap<>(carrierOnlyReasons);
        if(!selected.containsAll(carrierOnly.keySet()))throw new IllegalArgumentException("OZON_PRINT_REPORT_SELECTION_CHANGED");
        var selectedPostings=postings.findMany(frozen.getId(),selected);
        var selectedJobs=jobs.findMany(frozen.getId(),selected);
        // Readiness is an earlier stock estimate, not a request to omit an existing label.
        // Revalidate held/printed bindings below instead of silently forcing carrier-only output.
        if(allowCarrierFallback&&!carrierOnly.isEmpty()){
            var pendingJobs=carrierOnly.keySet().stream().map(selectedJobs::get)
                    .filter(OzonPrintBundleService::awaitingPrintRevalidation).map(OzonExemplarJob::id).toList();
            var held=jobs.bindingCounts(frozen.getId(),pendingJobs);
            carrierOnly.keySet().removeIf(number->{
                var job=selectedJobs.get(number);
                return printable(job)||awaitingPrintRevalidation(job)&&held.getOrDefault(job.id(),0)>0;
            });
        }
        boolean containsNewPrint=selected.stream().anyMatch(number->!accepted(selectedJobs.get(number)));
        if(containsNewPrint)LicenseRuntime.require(Permission.PRINT_NEW);
        var context=new PrintContext(selectedPostings,selectedJobs,policies.findExemptSkus(frozen.getId()),
                catalogFor(frozen,selectedPostings.values()),preparation.forBatch(frozen));
        context.labels=labels.forBatch(frozen,allowCarrierFallback?selected.stream().filter(context.postings::containsKey).toList():selected);
        if(allowCarrierFallback&&context.labels.supportsWholeBatches())
            return exportWholeRequested(frozen,selected,labelTarget,pickingTarget,completed,carrierOnly,context,containsNewPrint);
        var successful=new ArrayList<String>();var successfulWithKiz=new LinkedHashMap<String,OzonExemplarJobRepository.PrintProof>();var failures=new LinkedHashMap<String,String>();var waiting=new java.util.LinkedHashSet<String>();
        File labelStaging=null,pickingStaging=null;
        int totalPages=0,kizPages=0;
        try(var databaseSession=Database.openBatchSession()) {
            labelStaging=AtomicFilePublisher.stagingFile(labelTarget,".available.pdf");
            pickingStaging=AtomicFilePublisher.stagingFile(pickingTarget,".available-picking.pdf");
            try(var destination=new PdfDocument(new PdfWriter(labelStaging))) {
                for(int index=0;index<selected.size();index++) {
                    requireNotInterrupted();String number=selected.get(index);
                    if(index%OzonExemplarService.PRINT_BATCH_SIZE==0&&context.preparation.refreshEveryNewPrint()) {
                        var window=selected.subList(index,Math.min(index+OzonExemplarService.PRINT_BATCH_SIZE,selected.size())).stream()
                                .filter(n->context.postings.containsKey(n)&&!carrierOnly.containsKey(n)&&!accepted(context.jobs.get(n))).toList();
                        long started=OzonPrintTiming.tick();
                        try{context.preparation.prefetch(frozen,window);}
                        catch(IOException failure){
                            if(!allowCarrierFallback||deferredLabelFailure(failure))throw failure;
                            for(String pending:window)carrierOnly.put(pending,com.vncode.app.shared.FriendlyErrorService.format(failure));
                        }finally{OzonPrintTiming.add(OzonPrintTiming.Stage.PREPARATION,started);}
                    }
                    byte[] partBytes=null;ExportResult result;VerifiedCarrierPage direct=null;
                    try {
                        if(!context.postings.containsKey(number))throw new IOException("OZON_POSTING_NOT_AVAILABLE_LOCALLY");
                        PreparedPrint prepared=null;
                        if(!carrierOnly.containsKey(number))try{prepared=preparePrint(frozen,number,context);}
                        catch(IOException failure){
                            if(!allowCarrierFallback||deferredLabelFailure(failure))throw failure;
                            carrierOnly.put(number,com.vncode.app.shared.FriendlyErrorService.format(failure));
                        }
                        if(prepared==null||prepared.bindings().isEmpty())direct=context.labels.verifiedCarrierPage(frozen,number);
                        if(direct!=null)result=new ExportResult(labelTarget,null,1,0,1);
                        else {
                            var bytes=new java.io.ByteArrayOutputStream();
                            try(var part=new PdfDocument(new PdfWriter(bytes))) {
                                result=prepared==null?composeCarrierOnly(frozen,number,labelTarget,context,part):
                                        composePrepared(frozen,labelTarget,null,false,context,part,prepared);
                            }
                            partBytes=bytes.toByteArray();
                        }
                    } catch(IOException error) {
                        requireNotInterrupted();
                        failures.put(number,com.vncode.app.shared.FriendlyErrorService.format(error));
                        if(deferredLabelFailure(error)) {
                            if(error instanceof OzonApiException apiError&&apiError.statusCode()==429)waiting.add(number);
                            for(int next=index+1;next<selected.size();next++){String pending=selected.get(next);failures.put(pending,com.vncode.app.shared.FriendlyErrorService.format(error));if(error instanceof OzonApiException apiError&&apiError.statusCode()==429)waiting.add(pending);}
                            break;
                        }
                        continue;
                    } finally {
                        context.preparation.discard(number);
                        if(completed!=null)completed.accept(index+1);
                    }
                    // Copy failures abort the whole file; never continue after a partially copied page.
                    long copiedAt=OzonPrintTiming.tick();
                    try {
                        if(direct!=null){
                            context.carrierNumbers.putAll(OzonCarrierLabelNumbers.read(frozen,null,direct.source(),List.of(direct.page()),Map.of(number,context.postings.get(number))));
                            direct.source().copyPagesTo(direct.page(),direct.page(),destination);
                            destination.getLastPage().flush();
                        }else try(var source=new PdfDocument(new PdfReader(new java.io.ByteArrayInputStream(partBytes)))) {
                            if(source.getNumberOfPages()!=result.totalPages())throw new IOException("OZON_LABEL_PAGE_COUNT_MISMATCH");
                            source.copyPagesTo(1,source.getNumberOfPages(),destination);destination.flushCopiedObjects(source);
                        }
                    }finally{OzonPrintTiming.add(OzonPrintTiming.Stage.COMPOSITION,copiedAt);}
                    successful.add(number);if(result.kizPages()>0){var proof=context.printProofs.get(number);if(proof==null)throw new IOException("OZON_PRINT_KIZ_PROOF_MISSING");successfulWithKiz.put(number,proof);}totalPages+=result.totalPages();kizPages+=result.kizPages();
                }
            }
            if(successful.isEmpty())return new AvailableBatchExportResult(null,null,List.of(),Map.copyOf(failures),Set.copyOf(waiting),0,0,Map.of());
            requireNotInterrupted();
            var printed=successful.stream().map(context.postings::get).toList();
            long pickingStarted=OzonPrintTiming.tick();
            try{pickingLists.exportBatch(pickingStaging,frozen,printed,context.carrierNumbers);}
            finally{OzonPrintTiming.add(OzonPrintTiming.Stage.PICKING,pickingStarted);}
            File finishedLabels=labelStaging,finishedPicking=pickingStaging;
            List<String> frozenSuccess=List.copyOf(successful);var frozenKizSuccess=Map.copyOf(successfulWithKiz);
            ownedPublish(frozen,context.preparation.validateShopOwner(),containsNewPrint,()->{
                publishPrintedBundle(frozen,frozenSuccess,frozenKizSuccess,finishedPicking,pickingTarget,finishedLabels,labelTarget,context.preparation.validateShopOwner());
            });
            labelStaging=null;pickingStaging=null;
            carrierOnly.keySet().retainAll(successful);
            return new AvailableBatchExportResult(labelTarget,pickingTarget,List.copyOf(successful),Map.copyOf(failures),Set.copyOf(waiting),totalPages,kizPages,Map.copyOf(carrierOnly),remoteKizWarnings(frozen,successfulWithKiz.keySet()));
        } finally {
            context.labels.close();AtomicFilePublisher.deleteQuietly(labelStaging);AtomicFilePublisher.deleteQuietly(pickingStaging);
        }
    }

    /** Download each task once, then sort exact posting identities using WB preferences.
     * Each unchanged carrier label is followed by that posting's combined KIZ label. */
    private AvailableBatchExportResult exportWholeRequested(Shop shop,List<String> selected,File labelTarget,File pickingTarget,
            IntConsumer completed,Map<String,String> initialCarrierOnly,PrintContext context,boolean containsNewPrint)throws IOException {
        var carrierOnly=new LinkedHashMap<>(initialCarrierOnly);var prepared=new LinkedHashMap<String,PreparedPrint>();
        var failures=new LinkedHashMap<String,String>();var waiting=new java.util.LinkedHashSet<String>();
        var successful=new java.util.LinkedHashSet<String>();var processed=new java.util.HashSet<String>();
        var proofs=new LinkedHashMap<String,OzonExemplarJobRepository.PrintProof>();File labelStaging=null,pickingStaging=null;
        int totalPages=0,kizPages=0;
        try(var session=Database.openBatchSession()){
            var available=sortedForPrinting(selected,context);
            for(String number:selected)if(!context.postings.containsKey(number))failures.put(number,"OZON_POSTING_NOT_AVAILABLE_LOCALLY");
            for(int start=0;start<available.size();start+=OzonExemplarService.PRINT_BATCH_SIZE){
                requireNotInterrupted();var window=available.subList(start,Math.min(start+OzonExemplarService.PRINT_BATCH_SIZE,available.size()));
                var needsRefresh=window.stream().filter(n->!carrierOnly.containsKey(n)&&!accepted(context.jobs.get(n))).toList();
                long started=OzonPrintTiming.tick();
                try{
                    if(context.preparation.refreshEveryNewPrint())context.preparation.prefetch(shop,needsRefresh);
                }catch(IOException failure){
                    if(deferredLabelFailure(failure)){
                        for(String number:selected){failures.put(number,com.vncode.app.shared.FriendlyErrorService.format(failure));if(failure instanceof OzonApiException api&&api.statusCode()==429)waiting.add(number);}
                        return new AvailableBatchExportResult(null,null,List.of(),Map.copyOf(failures),Set.copyOf(waiting),0,0,Map.of());
                    }
                    for(String number:needsRefresh)carrierOnly.put(number,com.vncode.app.shared.FriendlyErrorService.format(failure));
                }finally{OzonPrintTiming.add(OzonPrintTiming.Stage.PREPARATION,started);}
                for(String number:window)try{
                    if(!carrierOnly.containsKey(number))prepared.put(number,preparePrint(shop,number,context));
                }catch(IOException failure){
                    if(deferredLabelFailure(failure)){
                        for(String pending:selected){failures.put(pending,com.vncode.app.shared.FriendlyErrorService.format(failure));if(failure instanceof OzonApiException api&&api.statusCode()==429)waiting.add(pending);}
                        return new AvailableBatchExportResult(null,null,List.of(),Map.copyOf(failures),Set.copyOf(waiting),0,0,Map.of());
                    }
                    carrierOnly.put(number,com.vncode.app.shared.FriendlyErrorService.format(failure));
                }finally{context.preparation.discard(number);}
            }
            labelStaging=AtomicFilePublisher.stagingFile(labelTarget,".whole-batch.pdf");
            pickingStaging=AtomicFilePublisher.stagingFile(pickingTarget,".whole-picking.pdf");
            try(var destination=new PdfDocument(new PdfWriter(labelStaging))){
                var groups=new LinkedHashMap<String,CarrierGroup>();
                for(String number:available){
                    if(processed.contains(number))continue;requireNotInterrupted();WholeCarrierBatch batch;
                    try{batch=context.labels.wholeBatch(shop,number);}
                    catch(IOException failure){
                        failures.put(number,com.vncode.app.shared.FriendlyErrorService.format(failure));processed.add(number);
                        if(deferredLabelFailure(failure)){
                            for(String pending:available)if(!processed.contains(pending)){failures.put(pending,com.vncode.app.shared.FriendlyErrorService.format(failure));if(failure instanceof OzonApiException api&&api.statusCode()==429)waiting.add(pending);}
                            if(failure instanceof OzonApiException api&&api.statusCode()==429)waiting.add(number);break;
                        }
                        continue;
                    }
                    if(batch==null||batch.members().isEmpty()||new java.util.HashSet<>(batch.members()).size()!=batch.members().size()
                            ||!available.containsAll(batch.members())||!java.util.Collections.disjoint(processed,batch.members())
                            ||!batch.members().containsAll(batch.unprinted()))throw new IOException("OZON_LABEL_BATCH_SELECTION_CHANGED");
                    var printed=batch.members().stream().filter(n->!batch.unprinted().contains(n)).toList();
                    if(!printed.isEmpty()&&(batch.source()==null||batch.pages().isEmpty()))throw new IOException("OZON_LABEL_BATCH_INVALID_PDF");
                    long copied=OzonPrintTiming.tick();
                    List<Integer> shippingPages=List.of();
                    try{if(batch.source()!=null){
                        shippingPages=OzonOfficialLabelSelector.shippingPages(batch.file(),batch.source(),batch.pages(),
                                printed.stream().map(context.postings::get).toList(),context.products);
                        if(!printed.isEmpty()&&shippingPages.isEmpty())throw new IOException("OZON_LABEL_BATCH_INVALID_PDF");
                        var labelledPostings=new LinkedHashMap<String,OzonPostingDto>();
                        printed.forEach(n->labelledPostings.put(n,context.postings.get(n)));
                        var index=OzonCarrierLabelNumbers.index(shop,batch.file(),batch.source(),shippingPages,labelledPostings);
                        context.carrierNumbers.putAll(index.numbers());
                        if(index.unidentifiedPages().isEmpty()&&index.pagesByPosting().keySet().containsAll(printed)){
                            for(String member:printed)groups.put(member,new CarrierGroup(batch.source(),index.pagesByPosting().get(member),List.of(member),true));
                        }else{
                            var unknown=printed.stream().filter(member->!index.pagesByPosting().containsKey(member)).toList();
                            if(!unknown.isEmpty()&&!index.unidentifiedPages().isEmpty()&&!index.pagesByPosting().isEmpty()){
                                // One unreadable order must not remove labels from the rest of the task.
                                // Only explicit identities are paired; unknown pages stay together, unpaired.
                                for(String member:printed)if(index.pagesByPosting().containsKey(member))
                                    groups.put(member,new CarrierGroup(batch.source(),index.pagesByPosting().get(member),List.of(member),true));
                                var group=new CarrierGroup(batch.source(),index.unidentifiedPages(),unknown,false);
                                for(String member:unknown){groups.put(member,group);carrierOnly.put(member,"OZON_LABEL_BATCH_IDENTITY_UNVERIFIED");}
                            }else{
                                // Preserve all official carrier pages, but never guess where to attach a KIZ.
                                var group=new CarrierGroup(batch.source(),shippingPages,printed,false);
                                for(String member:printed){groups.put(member,group);carrierOnly.put(member,"OZON_LABEL_BATCH_IDENTITY_UNVERIFIED");}
                            }
                        }
                    }}
                    finally{OzonPrintTiming.add(OzonPrintTiming.Stage.COMPOSITION,copied);}
                    totalPages+=shippingPages.size();successful.addAll(printed);processed.addAll(batch.members());
                    for(String rejected:batch.unprinted())failures.put(rejected,"OZON_LABEL_UNPRINTED");
                }
                int done=0;var copiedGroups=new java.util.HashSet<CarrierGroup>();
                for(String number:available){
                    requireNotInterrupted();var group=groups.get(number);
                    if(group==null||!copiedGroups.add(group))continue;
                    var plan=group.identified()?prepared.get(number):null;
                    byte[] bytes=null;int appended=0;long composed=OzonPrintTiming.tick();
                    if(plan!=null&&!plan.bindings().isEmpty()){
                        try{
                            var buffer=new java.io.ByteArrayOutputStream();
                            try(var part=new PdfDocument(new PdfWriter(buffer))){appended=kizLabels.append(part,shop,plan.posting(),plan.bindings(),context.products);}
                            if(appended!=plan.bindings().size())throw new IOException("OZON_KIZ_LABEL_COUNT_MISMATCH");bytes=buffer.toByteArray();
                        }catch(IOException failure){carrierOnly.put(number,com.vncode.app.shared.FriendlyErrorService.format(failure));}
                    }
                    // Render a complete posting's KIZ pages before touching the final PDF.
                    // Copy originals unchanged; each combined label remains next to its own carrier.
                    try(var part=bytes==null?null:new PdfDocument(new PdfReader(new java.io.ByteArrayInputStream(bytes)))){
                        if(part!=null&&part.getNumberOfPages()!=appended)throw new IOException("OZON_KIZ_LABEL_COUNT_MISMATCH");
                        int kizPage=1;
                        for(int page:group.pages()){
                            group.source().copyPagesTo(page,page,destination);destination.getLastPage().flush();
                            if(part!=null&&kizPage<=appended){part.copyPagesTo(kizPage,kizPage,destination);kizPage++;destination.getLastPage().flush();}
                        }
                        if(part!=null){
                            if(kizPage<=appended)part.copyPagesTo(kizPage,appended,destination);
                            destination.flushCopiedObjects(part);proofs.put(number,plan.proof());
                            kizPages+=appended;totalPages+=appended;
                        }
                    }finally{OzonPrintTiming.add(OzonPrintTiming.Stage.COMPOSITION,composed);}
                    done+=group.members().size();if(completed!=null)completed.accept(done);
                }
                for(var source:copiedGroups.stream().map(CarrierGroup::source).distinct().toList())destination.flushCopiedObjects(source);
                if(completed!=null&&done<selected.size())completed.accept(selected.size());
            }
            if(successful.isEmpty())return new AvailableBatchExportResult(null,null,List.of(),Map.copyOf(failures),Set.copyOf(waiting),0,0,Map.of());
            var orderedSuccess=available.stream().filter(successful::contains).toList();
            long pickingAt=OzonPrintTiming.tick();
            try{pickingLists.exportBatch(pickingStaging,shop,orderedSuccess.stream().map(context.postings::get).toList(),context.carrierNumbers);}
            finally{OzonPrintTiming.add(OzonPrintTiming.Stage.PICKING,pickingAt);}
            requireNotInterrupted();var labelsFile=labelStaging;var pickingFile=pickingStaging;
            ownedPublish(shop,context.preparation.validateShopOwner(),containsNewPrint,()->
                    publishPrintedBundle(shop,orderedSuccess,Map.copyOf(proofs),pickingFile,pickingTarget,labelsFile,labelTarget,context.preparation.validateShopOwner()));
            labelStaging=null;pickingStaging=null;carrierOnly.keySet().retainAll(successful);
            return new AvailableBatchExportResult(labelTarget,pickingTarget,orderedSuccess,Map.copyOf(failures),Set.copyOf(waiting),totalPages,kizPages,Map.copyOf(carrierOnly),remoteKizWarnings(shop,proofs.keySet()));
        }finally{context.labels.close();AtomicFilePublisher.deleteQuietly(labelStaging);AtomicFilePublisher.deleteQuietly(pickingStaging);}
    }

    private Map<String,String> remoteKizWarnings(Shop shop,Set<String> printedWithKiz)throws IOException{
        var warnings=new LinkedHashMap<String,String>();
        for(String number:printedWithKiz)if("rejected".equals(jobs.latestActionStatus(shop.getId(),"accept_kiz",number)))
            warnings.put(number,"OZON_ACCEPT_KIZ_REJECTED");
        try(var connection=Database.getConnection()){
            new com.vncode.app.features.kiz.status.Kn3Repository().read(connection,shop.getId(),shop.getMarketplace(),printedWithKiz)
                    .forEach((number,marks)->{if(marks.stream().anyMatch(mark->mark.state()==com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED))
                        warnings.put(number,"OZON_ACCEPT_KIZ_REJECTED");});
        }catch(java.sql.SQLException failure){throw new IOException("FBS_KN3_STORAGE",failure);}
        return Map.copyOf(warnings);
    }

    private record CarrierGroup(PdfDocument source,List<Integer> pages,List<String> members,boolean identified){}

    private static List<String> sortedForPrinting(List<String> selected,PrintContext context){
        var options=new com.vncode.app.features.supply.OrderSortPreferenceService().load();
        var variants=new LinkedHashMap<String,com.vncode.app.features.ozon.OzonProductVariant>();
        for(String number:selected){
            var posting=context.postings.get(number);if(posting==null)continue;
            var item=posting.items().isEmpty()?null:posting.items().getFirst();
            variants.put(number,com.vncode.app.features.ozon.OzonProductVariant.from(item,item==null?null:context.products.get(item.sku())));
        }
        return selected.stream().filter(variants::containsKey).sorted(java.util.Comparator
                .<String,com.vncode.app.features.ozon.OzonProductVariant>comparing(variants::get,com.vncode.app.features.ozon.OzonProductVariant.comparator(options))
                .thenComparing(com.vncode.app.shared.NaturalOrderComparator::compareIgnoreCase)).toList();
    }

    private static boolean deferredLabelFailure(IOException failure){
        return failure instanceof OzonApiException api&&(api.statusCode()==401||api.statusCode()==403||api.statusCode()==429);
    }
    private ExportResult composeCarrierOnly(Shop shop,String number,File target,PrintContext context,PdfDocument destination)throws IOException {
        var staging=AtomicFilePublisher.stagingFile(target,".carrier.pdf");
        try{
            context.labels.download(shop,number,staging);
            int pages=composeInto(staging,destination,shop,context.postings.get(number),List.of(),context.products,context.carrierNumbers);
            return new ExportResult(target,null,pages,0,pages);
        }finally{AtomicFilePublisher.deleteQuietly(staging);}
    }

    private static void requireNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Ozon print export was interrupted.");
    }

    /** Check frozen KIZ bindings inside the write transaction before publishing either PDF. */
    private void publishPrintedBundle(Shop shop,List<String> postings,
            Map<String,OzonExemplarJobRepository.PrintProof> proofs,File pickingStage,File pickingTarget,
            File labelStage,File labelTarget,boolean validateOwner)throws IOException {
        boolean[] published={false};
        try {
            if(proofs.isEmpty()){
                publishPdfPair(pickingStage,pickingTarget,labelStage,labelTarget);published[0]=true;
            }else jobs.consumePrintedKizBatch(shop.getId(),proofs,()->{
                if(validateOwner)com.vncode.app.integration.listing.ListingIdentity.require(shop);
                try{publishPdfPair(pickingStage,pickingTarget,labelStage,labelTarget);published[0]=true;}
                catch(IOException failed){throw new java.io.UncheckedIOException(failed);}
            });
            new OzonPrintReceiptRepository().record(shop,postings);
        } catch(java.io.UncheckedIOException fileFailure){
            throw fileFailure.getCause();
        } catch(RuntimeException storageFailure) {
            throw new IOException(published[0]?"OZON_PDF_PUBLISHED_RECONCILIATION_REQUIRED":"OZON_PRINT_KIZ_PROOF_CHANGED",storageFailure);
        }
    }

    private static void publishPdfPair(File pickingStage,File pickingTarget,File labelStage,File labelTarget)throws IOException {
        publishPdfPair(pickingStage,pickingTarget,labelStage,labelTarget,AtomicFilePublisher::publish);
    }

    @FunctionalInterface interface PdfFilePublisher {void publish(File staging,File target)throws IOException;}
    static void publishPdfPair(File pickingStage,File pickingTarget,File labelStage,File labelTarget,PdfFilePublisher publisher)throws IOException {
        Path picking=pickingTarget.toPath().toAbsolutePath().normalize(),label=labelTarget.toPath().toAbsolutePath().normalize();
        if(Files.isDirectory(picking)||Files.isDirectory(label))throw new IOException("OZON_PDF_TARGET_IS_DIRECTORY");
        if(picking.equals(label)
                || (Files.exists(picking)&&Files.exists(label)&&Files.isSameFile(picking,label))
                || (picking.getFileName().equals(label.getFileName())&&Files.isSameFile(picking.getParent(),label.getParent())))
            throw new IOException("OZON_PDF_TARGETS_MUST_DIFFER");
        File pickingBackup=null,labelBackup=null;
        boolean pickingExisted=Files.exists(picking),labelExisted=Files.exists(label);
        boolean keepPickingBackup=false,keepLabelBackup=false;
        try {
            if(pickingExisted){pickingBackup=AtomicFilePublisher.stagingFile(pickingTarget,".previous.pdf");Files.copy(picking,pickingBackup.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            if(labelExisted){labelBackup=AtomicFilePublisher.stagingFile(labelTarget,".previous.pdf");Files.copy(label,labelBackup.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            try {publisher.publish(pickingStage,pickingTarget);}
            catch(IOException failure){
                // A failed non-atomic move may already have changed the destination.
                if(pickingBackup!=null&&Files.exists(pickingBackup.toPath())){
                    keepPickingBackup=true;
                    throw new IOException("OZON_PDF_PUBLISH_FAILED: Previous picking PDF retained at "
                            +pickingBackup.toPath().toAbsolutePath(),failure);
                }
                if(!pickingExisted)try{Files.deleteIfExists(picking);}catch(IOException cleanupFailure){failure.addSuppressed(cleanupFailure);}
                throw failure;
            }
            try { publisher.publish(labelStage,labelTarget); }
            catch(IOException failure) {
                StringBuilder recovery=new StringBuilder();boolean rollbackFailed=false;
                try { if(pickingExisted)publisher.publish(pickingBackup,pickingTarget);else Files.deleteIfExists(picking); }
                catch(IOException rollbackFailure){
                    failure.addSuppressed(rollbackFailure);rollbackFailed=true;
                    if(pickingBackup!=null&&Files.exists(pickingBackup.toPath())){
                        keepPickingBackup=true;recovery.append(" Picking backup: ").append(pickingBackup.toPath().toAbsolutePath());
                    }
                }
                try { if(labelExisted)publisher.publish(labelBackup,labelTarget);else Files.deleteIfExists(label); }
                catch(IOException rollbackFailure){
                    failure.addSuppressed(rollbackFailure);rollbackFailed=true;
                    if(labelBackup!=null&&Files.exists(labelBackup.toPath())){
                        keepLabelBackup=true;recovery.append(" Label backup: ").append(labelBackup.toPath().toAbsolutePath());
                    }
                }
                if(rollbackFailed)throw new IOException("OZON_PDF_RESTORE_FAILED:"+recovery,failure);
                throw failure;
            }
        } finally {
            if(pickingBackup!=null&&!keepPickingBackup)AtomicFilePublisher.deleteQuietly(pickingBackup);
            if(labelBackup!=null&&!keepLabelBackup)AtomicFilePublisher.deleteQuietly(labelBackup);
        }
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
        final Map<String,OzonExemplarJobRepository.PrintProof> printProofs=new LinkedHashMap<>();
        final Map<String,String> carrierNumbers=new LinkedHashMap<>();
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
            Map<String, OzonProductDto> products,Map<String,String> carrierNumbers) throws IOException {
        try(var destination=new PdfDocument(new PdfWriter(target))) {
            return composeInto(official,destination,shop,posting,bindings,products,carrierNumbers);
        }
    }
    private int composeInto(File official,PdfDocument destination,Shop shop,OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings,Map<String,OzonProductDto> products,Map<String,String> carrierNumbers)throws IOException {
        if(!Files.isRegularFile(official.toPath()))throw new IOException("OZON_LABEL_FILE_MISSING");
        try(PdfDocument source=new PdfDocument(new PdfReader(official))) {
            int firstPage=destination.getNumberOfPages()+1;
            if (source.getNumberOfPages() < 1) {
                throw new IOException("The official Ozon shipping label PDF has no pages.");
            }
            List<Integer> retainedPages = OzonOfficialLabelSelector.shippingPages(
                    official, source, java.util.stream.IntStream.rangeClosed(1,source.getNumberOfPages()).boxed().toList(),
                    List.of(posting), products);
            if(retainedPages.isEmpty())throw new IOException("OZON_LABEL_BATCH_INVALID_PDF");
            carrierNumbers.putAll(OzonCarrierLabelNumbers.read(shop,official,source,retainedPages,Map.of(posting.postingNumber(),posting)));
            int bindingIndex = 0;
            int appended = 0;
            // Shipping pages retain their exact content and dimensions; standalone product
            // labels are omitted whether or not a combined KIZ label is available.
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

    private static boolean accepted(OzonExemplarJob job) {
        return job != null && job.stage() == OzonExemplarJobStage.ACCEPTED;
    }

    private static boolean printable(OzonExemplarJob job) {
        return job != null && (job.stage() == OzonExemplarJobStage.VALIDATED || accepted(job));
    }

    /** A held code still needs the normal fresh-posting and exact-binding checks before printing. */
    private static boolean awaitingPrintRevalidation(OzonExemplarJob job) {
        return job!=null&&switch(job.stage()){
            case RESERVED,SET_PENDING,VERIFYING,RECONCILE_REQUIRED->true;
            default->false;
        };
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
        default void discard(String number) {}
    }

    @FunctionalInterface
    interface OfficialLabelDownloader extends AutoCloseable {
        File download(Shop shop, String postingNumber, File target) throws IOException;
        default OfficialLabelDownloader forBatch(Shop shop,List<String> numbers)throws IOException{return this;}
        /** Borrowed page from a shared PDF whose posting identity has already been verified. */
        default VerifiedCarrierPage verifiedCarrierPage(Shop shop,String postingNumber)throws IOException{return null;}
        default boolean supportsWholeBatches(){return false;}
        default WholeCarrierBatch wholeBatch(Shop shop,String postingNumber)throws IOException{return null;}
        @Override default void close(){}
    }

    /** Exact authenticated task membership; pages stay in Ozon order without per-page KIZ pairing. */
    record WholeCarrierBatch(PdfDocument source,File file,List<Integer> pages,List<String> members,Set<String> unprinted){
        WholeCarrierBatch{pages=List.copyOf(pages);members=List.copyOf(members);unprinted=Set.copyOf(unprinted);}
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
    public record AvailableBatchExportResult(File labelFile,File pickingFile,List<String> successful,
            Map<String,String> failures,Set<String> waiting,int totalPages,int kizPages,Map<String,String> carrierOnly,Map<String,String> remoteKizWarnings) {
        public AvailableBatchExportResult(File labels,File picking,List<String> successful,Map<String,String> failures,Set<String> waiting,int totalPages,int kizPages,Map<String,String> carrierOnly){this(labels,picking,successful,failures,waiting,totalPages,kizPages,carrierOnly,Map.of());}
        public AvailableBatchExportResult(File labels,File picking,List<String> successful,Map<String,String> failures,Set<String> waiting,int totalPages,int kizPages){this(labels,picking,successful,failures,waiting,totalPages,kizPages,Map.of());}
    }
}
