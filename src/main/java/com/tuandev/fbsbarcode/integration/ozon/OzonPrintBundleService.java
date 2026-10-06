package com.tuandev.fbsbarcode.integration.ozon;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AtomicFilePublisher;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Idempotent Ozon print orchestration: reserve KIZ locally, preserve official pages, append physical
 * KIZ labels and create a separate picking list. KIZ is never submitted to Ozon.
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
                new OzonExemplarService()::stageForPrint,
                new OzonLabelService()::downloadOfficialPdf);
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
        return exportInternal(shop, postingNumber, labelTarget, pickingTarget, true);
    }

    private ExportResult exportLabelOnly(Shop shop, String postingNumber, File labelTarget) throws IOException {
        requirePdfTarget(labelTarget, "label bundle");
        return exportInternal(shop, postingNumber, labelTarget, null, false);
    }

    private ExportResult exportInternal(
            Shop shop,
            String postingNumber,
            File labelTarget,
            File pickingTarget,
            boolean consumeAfterPublish)
            throws IOException {
        String safePosting = OzonApiClient.requireExternalId(postingNumber, "posting number");
        OzonPostingDto posting = postings.find(shop.getId(), safePosting);
        if (posting == null) throw new IOException("The selected Ozon posting is not available locally. Refresh first.");

        OzonExemplarJob job = jobs.find(shop.getId(), safePosting);
        boolean requiresKiz = OzonRequirementGuard.requiresAny(
                posting, policies.findExemptSkus(shop.getId()));
        if (requiresKiz && !printable(job)) {
            OzonPreparationResult result = preparation.prepare(shop, safePosting);
            if ("NOT_REQUIRED".equals(result.stage())) {
                throw new IOException("An Ozon item requiring KIZ cannot be omitted from the print bundle.");
            }
            if (!"ACCEPTED".equals(result.stage()) && !"VALIDATED".equals(result.stage())
                    && !"NOT_REQUIRED".equals(result.stage())) {
                throw new IOException("Ozon KIZ is not ready for printing yet (stage "
                        + safeStage(result.stage()) + ").");
            }
            posting = Objects.requireNonNullElse(postings.find(shop.getId(), safePosting), posting);
            job = jobs.find(shop.getId(), safePosting);
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
            officialStaging = AtomicFilePublisher.stagingFile(labelTarget, ".official.pdf");
            labelStaging = AtomicFilePublisher.stagingFile(labelTarget, ".bundle.pdf");
            if (pickingTarget != null) {
                pickingStaging = AtomicFilePublisher.stagingFile(pickingTarget, ".picking.pdf");
            }
            labels.download(shop, safePosting, officialStaging);
            int officialPages = compose(officialStaging, labelStaging, shop, posting, bindings);
            if (pickingStaging != null) pickingLists.export(pickingStaging, shop, posting);
            AtomicFilePublisher.publish(labelStaging, labelTarget);
            labelStaging = null;
            if (consumeAfterPublish && !bindings.isEmpty()) consumePrinted(job);
            if (pickingStaging != null) {
                AtomicFilePublisher.publish(pickingStaging, pickingTarget);
                pickingStaging = null;
            }
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
        MarketplaceGuard.requireOzon(shop);
        requirePdfTarget(labelTarget, "label batch");
        requirePdfTarget(pickingTarget, "picking batch");
        List<String> safePostings = postingNumbers == null ? List.of() : postingNumbers.stream()
                .map(value -> OzonApiClient.requireExternalId(value, "posting number"))
                .distinct()
                .toList();
        if (safePostings.isEmpty()) throw new IllegalArgumentException("At least one Ozon posting is required.");
        if (labelTarget.toPath().toAbsolutePath().normalize()
                .equals(pickingTarget.toPath().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Ozon label batch and picking batch must use different files.");
        }

        Path temporaryDirectory = Files.createTempDirectory("wcode-ozon-print-");
        List<File> labelParts = new ArrayList<>();
        List<OzonPostingDto> batchPostings = new ArrayList<>();
        File labelStaging = null;
        File pickingStaging = null;
        int totalPages = 0;
        int kizPages = 0;
        try {
            for (int index = 0; index < safePostings.size(); index++) {
                File labelPart = temporaryDirectory.resolve("labels-" + index + ".pdf").toFile();
                ExportResult result = exportLabelOnly(shop, safePostings.get(index), labelPart);
                labelParts.add(labelPart);
                OzonPostingDto posting = postings.find(shop.getId(), safePostings.get(index));
                if (posting == null) {
                    throw new IOException("An Ozon posting disappeared while composing the picking list.");
                }
                batchPostings.add(posting);
                totalPages += result.totalPages();
                kizPages += result.kizPages();
            }
            labelStaging = AtomicFilePublisher.stagingFile(labelTarget, ".batch.pdf");
            pickingStaging = AtomicFilePublisher.stagingFile(pickingTarget, ".batch-picking.pdf");
            merge(labelParts, labelStaging);
            pickingLists.exportBatch(pickingStaging, shop, batchPostings);
            AtomicFilePublisher.publish(labelStaging, labelTarget);
            labelStaging = null;
            for (String postingNumber : safePostings) {
                OzonExemplarJob job = jobs.find(shop.getId(), postingNumber);
                if (job != null && !jobs.bindings(job.id()).isEmpty()) consumePrinted(job);
            }
            AtomicFilePublisher.publish(pickingStaging, pickingTarget);
            pickingStaging = null;
            return new BatchExportResult(
                    labelTarget, pickingTarget, safePostings.size(), totalPages, kizPages);
        } finally {
            AtomicFilePublisher.deleteQuietly(labelStaging);
            AtomicFilePublisher.deleteQuietly(pickingStaging);
            for (File part : labelParts) AtomicFilePublisher.deleteQuietly(part);
            try {
                Files.deleteIfExists(temporaryDirectory);
            } catch (IOException ignored) {
            }
        }
    }

    private static void merge(List<File> parts, File target) throws IOException {
        try (PdfDocument destination = new PdfDocument(new PdfWriter(target))) {
            for (File part : parts) {
                try (PdfDocument source = new PdfDocument(new PdfReader(part))) {
                    if (source.getNumberOfPages() < 1) {
                        throw new IOException("An Ozon PDF part has no pages.");
                    }
                    source.copyPagesTo(1, source.getNumberOfPages(), destination);
                }
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("The Ozon print batch could not be composed.", exception);
        }
    }

    private int compose(
            File official,
            File target,
            Shop shop,
            OzonPostingDto posting,
            List<OzonExemplarJobRepository.KizBinding> bindings) throws IOException {
        if (!Files.isRegularFile(official.toPath())) {
            throw new IOException("Ozon did not provide an official shipping label PDF.");
        }
        try (PdfDocument source = new PdfDocument(new PdfReader(official));
                PdfDocument destination = new PdfDocument(new PdfWriter(target))) {
            int officialPages = source.getNumberOfPages();
            if (officialPages < 1) throw new IOException("The official Ozon shipping label PDF has no pages.");
            int bindingIndex = 0;
            int appended = 0;
            // Preserve every official 58 x 40 mm page unchanged and place the corresponding
            // physical KIZ page directly after it whenever one is available.
            for (int page = 1; page <= officialPages; page++) {
                source.copyPagesTo(page, page, destination);
                if (bindingIndex < bindings.size()) {
                    appended += kizLabels.append(
                            destination, shop, posting, List.of(bindings.get(bindingIndex++)));
                }
            }
            if (bindingIndex < bindings.size()) {
                appended += kizLabels.append(
                        destination, shop, posting, bindings.subList(bindingIndex, bindings.size()));
            }
            if (appended != bindings.size()) {
                throw new IOException("The Ozon KIZ label page count is incomplete.");
            }
            return officialPages;
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
                    "The Ozon label PDF was created, but WCode could not mark its KIZ as printed.", exception);
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
    }

    @FunctionalInterface
    interface OfficialLabelDownloader {
        File download(Shop shop, String postingNumber, File target) throws IOException;
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
