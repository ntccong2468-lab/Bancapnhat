package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventoryService;
import com.tuandev.fbsbarcode.models.Shop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Calculates mapping and KIZ sufficiency without reserving a code or calling Ozon. */
public final class OzonPrintReadinessService {
    private final OzonPostingRepository postings;
    private final OzonProductGtinMappingRepository mappings;
    private final OzonProductKizPolicyRepository policies;
    private final OzonExemplarJobRepository jobs;
    private final ZnackGtinInventoryService inventory;

    public OzonPrintReadinessService() {
        this(new OzonPostingRepository(), new OzonProductGtinMappingRepository(),
                new OzonProductKizPolicyRepository(), new OzonExemplarJobRepository(),
                new ZnackGtinInventoryService());
    }

    OzonPrintReadinessService(
            OzonPostingRepository postings,
            OzonProductGtinMappingRepository mappings,
            OzonExemplarJobRepository jobs,
            ZnackGtinInventoryService inventory) {
        this(postings, mappings, new OzonProductKizPolicyRepository(), jobs, inventory);
    }

    OzonPrintReadinessService(
            OzonPostingRepository postings,
            OzonProductGtinMappingRepository mappings,
            OzonProductKizPolicyRepository policies,
            OzonExemplarJobRepository jobs,
            ZnackGtinInventoryService inventory) {
        this.postings = Objects.requireNonNull(postings, "postings");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public OzonPrintReadiness inspect(Shop shop, String postingNumber) {
        MarketplaceGuard.requireOzon(shop);
        String safePosting = OzonApiClient.requireExternalId(postingNumber, "posting number");
        OzonPostingDto posting = postings.find(shop.getId(), safePosting);
        if (posting == null) {
            return result(safePosting, false, false, 0, List.of(), List.of(), List.of(), "");
        }
        List<String> unsupported = new ArrayList<>(posting.requirements().unsupportedRequirements());
        if (!posting.isSinglePackageSupported()) unsupported.add("partial_or_multibox_package");
        if (!unsupported.isEmpty()) {
            return result(safePosting, false, true, 0, List.of(), List.of(), unsupported, "");
        }

        OzonExemplarJob job = jobs.find(shop.getId(), safePosting);
        if (job != null && job.stage() == OzonExemplarJobStage.ACCEPTED) {
            int accepted = jobs.bindings(job.id()).size();
            return result(safePosting, true, true, accepted, List.of(), List.of(), List.of(), job.stage().name());
        }

        Map<String, String> skuMappings = mappings.findResolvedBySku(shop.getId());
        Set<String> exemptSkus = policies.findExemptSkus(shop.getId());
        Set<String> mandatory = Set.copyOf(posting.requirements().mandatoryMarkProductIds());
        Set<String> coveredMandatory = new LinkedHashSet<>();
        List<String> missingSkus = new ArrayList<>();
        for (OzonPostingItemDto item : posting.items()) {
            boolean mandatoryMark = mandatory.contains(item.productId());
            if (mandatoryMark) coveredMandatory.add(item.productId());
            if (!mandatoryMark && !item.sku().isBlank() && exemptSkus.contains(item.sku())) continue;
            String gtin = skuMappings.get(item.sku());
            if (gtin == null || gtin.isBlank()) missingSkus.add(item.sku().isBlank() ? item.productId() : item.sku());
        }
        if (!coveredMandatory.containsAll(mandatory)) missingSkus.add("mandatory_product");
        if (!missingSkus.isEmpty()) {
            return result(safePosting, false, true, 0, List.of(), missingSkus, List.of(), "");
        }

        OzonRequirementGuard.PreparationPlan plan = OzonRequirementGuard.plan(posting, skuMappings, exemptSkus);
        String stage = job == null ? "" : job.stage().name();
        int requiredKiz = plan.exemplarCount();
        if (requiredKiz == 0) {
            return result(safePosting, true, true, 0, List.of(), List.of(), List.of(), stage);
        }

        Map<String, Integer> requiredByGtin = new LinkedHashMap<>();
        for (OzonRequirementGuard.RequiredItem item : plan.items()) {
            requiredByGtin.merge(item.gtin(), item.quantity(), Integer::sum);
        }
        boolean alreadySecured = job != null
                && job.stage() != OzonExemplarJobStage.CREATED
                && job.stage() != OzonExemplarJobStage.REJECTED
                && jobs.bindings(job.id()).size() == requiredKiz;
        List<OzonPrintReadiness.GtinAvailability> availability = requiredByGtin.entrySet().stream()
                .map(entry -> new OzonPrintReadiness.GtinAvailability(
                        entry.getKey(), entry.getValue(), alreadySecured
                                ? entry.getValue() : inventory.availableCount(shop.getId(), entry.getKey())))
                .toList();
        boolean ready = availability.stream().allMatch(OzonPrintReadiness.GtinAvailability::sufficient);
        return result(safePosting, ready, true, requiredKiz, availability, List.of(), List.of(), stage);
    }

    /** Checks the full queue as one allocation so two orders cannot both count the same KIZ. */
    public OzonBatchPrintReadiness inspectAll(Shop shop, List<String> postingNumbers) {
        MarketplaceGuard.requireOzon(shop);
        List<String> safePostings = postingNumbers == null ? List.of() : postingNumbers.stream()
                .map(value -> OzonApiClient.requireExternalId(value, "posting number"))
                .distinct()
                .toList();
        List<OzonPrintReadiness> results = new ArrayList<>();
        Map<String, Integer> unsecuredByGtin = new LinkedHashMap<>();
        for (String postingNumber : safePostings) {
            OzonPrintReadiness readiness = inspect(shop, postingNumber);
            results.add(readiness);
            if (!readiness.ready() || readiness.requiredKiz() == 0) continue;
            OzonExemplarJob job = jobs.find(shop.getId(), postingNumber);
            boolean secured = job != null
                    && job.stage() != OzonExemplarJobStage.CREATED
                    && job.stage() != OzonExemplarJobStage.REJECTED
                    && jobs.bindings(job.id()).size() == readiness.requiredKiz();
            if (secured) continue;
            for (OzonPrintReadiness.GtinAvailability value : readiness.gtinAvailability()) {
                unsecuredByGtin.merge(value.gtin(), value.required(), Integer::sum);
            }
        }
        List<OzonPrintReadiness.GtinAvailability> totals = unsecuredByGtin.entrySet().stream()
                .map(entry -> new OzonPrintReadiness.GtinAvailability(
                        entry.getKey(), entry.getValue(), inventory.availableCount(shop.getId(), entry.getKey())))
                .toList();
        boolean ready = !safePostings.isEmpty()
                && results.stream().allMatch(OzonPrintReadiness::ready)
                && totals.stream().allMatch(OzonPrintReadiness.GtinAvailability::sufficient);
        return new OzonBatchPrintReadiness(ready, results, totals);
    }

    private static OzonPrintReadiness result(
            String postingNumber,
            boolean ready,
            boolean postingAvailable,
            int requiredKiz,
            List<OzonPrintReadiness.GtinAvailability> availability,
            List<String> missingSkus,
            List<String> unsupported,
            String stage) {
        return new OzonPrintReadiness(
                postingNumber, ready, postingAvailable, requiredKiz,
                availability, missingSkus, unsupported, stage);
    }
}
