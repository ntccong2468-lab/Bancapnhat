package com.vncode.app.integration.ozon;

import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.Shop;
import com.vncode.app.features.kiz.KizSourceSelection;
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
        return inspectAll(shop,List.of(postingNumber)).postings().getFirst();
    }

    private OzonPrintReadiness inspect(Shop shop,String safePosting,OzonPostingDto posting,OzonExemplarJob job,
                                      int bindingCount,Map<String,String> skuMappings,Set<String> exemptSkus,
                                      Map<String,Integer> availableCounts,Map<String,Integer> categoryCoverage,KizSourceSelection sources) {
        if (posting == null) {
            return result(safePosting, false, false, 0, List.of(), List.of(), List.of(), "");
        }
        List<String> unsupported = new ArrayList<>(posting.requirements().unsupportedRequirements());
        if (!posting.isSinglePackageSupported()) unsupported.add("partial_or_multibox_package");
        if (!unsupported.isEmpty()) {
            return result(safePosting, false, true, 0, List.of(), List.of(), unsupported, "");
        }

        if (job != null && job.stage() == OzonExemplarJobStage.ACCEPTED) {
            return result(safePosting, true, true, bindingCount, List.of(), List.of(), List.of(), job.stage().name());
        }

        int expectedQuantity=posting.items().stream().filter(i->posting.requirements().mandatoryMarkProductIds().contains(i.productId())||!exemptSkus.contains(i.sku())).mapToInt(OzonPostingItemDto::quantity).sum();
        if(job!=null&&job.stage()!=OzonExemplarJobStage.CREATED&&job.stage()!=OzonExemplarJobStage.REJECTED&&bindingCount==expectedQuantity)
            return result(safePosting,true,true,bindingCount,List.of(),List.of(),List.of(),job.stage().name());
        Set<String> mandatory = Set.copyOf(posting.requirements().mandatoryMarkProductIds());
        Set<String> coveredMandatory = new LinkedHashSet<>();
        List<String> missingSkus = new ArrayList<>();
        for (OzonPostingItemDto item : posting.items()) {
            boolean mandatoryMark = mandatory.contains(item.productId());
            if (mandatoryMark) coveredMandatory.add(item.productId());
            if (!mandatoryMark && !item.sku().isBlank() && exemptSkus.contains(item.sku())) continue;
            if(sources!=null&&!sources.purchased()){
                if(categoryCoverage.getOrDefault(OzonCategoryStock.key(safePosting,item.itemIndex()),0)<item.quantity())missingSkus.add("KIZ_CATEGORY_STOCK: "+item.sku());
                continue;
            }
            String gtin = skuMappings.get(item.sku());
            if ((gtin == null || gtin.isBlank()) && categoryCoverage.getOrDefault(OzonCategoryStock.key(safePosting,item.itemIndex()),0)<item.quantity()) missingSkus.add(item.sku().isBlank() ? item.productId() : item.sku());
        }
        if (!coveredMandatory.containsAll(mandatory)) missingSkus.add("mandatory_product");
        if (!missingSkus.isEmpty()) {
            return result(safePosting, false, true, 0, List.of(), missingSkus, List.of(), "");
        }

        OzonRequirementGuard.PreparationPlan plan = OzonRequirementGuard.plan(posting, skuMappings, exemptSkus, posting.items().stream().filter(i->categoryCoverage.getOrDefault(OzonCategoryStock.key(safePosting,i.itemIndex()),0)>0).map(OzonPostingItemDto::sku).collect(java.util.stream.Collectors.toSet()));
        String stage = job == null ? "" : job.stage().name();
        int requiredKiz = plan.exemplarCount();
        if (requiredKiz == 0) {
            return result(safePosting, true, true, 0, List.of(), List.of(), List.of(), stage);
        }

        Map<String, Integer> requiredByGtin = new LinkedHashMap<>();
        for (OzonRequirementGuard.RequiredItem item : plan.items()) {
            int remaining=item.quantity()-categoryCoverage.getOrDefault(OzonCategoryStock.key(safePosting,item.itemIndex()),0);
            if(remaining>0)requiredByGtin.merge(item.gtin(),remaining,Integer::sum);
        }
        boolean alreadySecured = job != null
                && job.stage() != OzonExemplarJobStage.CREATED
                && job.stage() != OzonExemplarJobStage.REJECTED
                && bindingCount == requiredKiz;
        List<OzonPrintReadiness.GtinAvailability> availability = requiredByGtin.entrySet().stream()
                .map(entry -> new OzonPrintReadiness.GtinAvailability(
                        entry.getKey(), entry.getValue(), alreadySecured
                                ? entry.getValue() : availableCounts.computeIfAbsent(entry.getKey(),gtin->inventory.availableCount(shop.getId(),gtin))))
                .toList();
        boolean ready = availability.stream().allMatch(OzonPrintReadiness.GtinAvailability::sufficient);
        return result(safePosting, ready, true, requiredKiz, availability, List.of(), List.of(), stage);
    }

    /** Checks the full queue as one allocation so two orders cannot both count the same KIZ. */
    public OzonBatchPrintReadiness inspectAll(Shop shop, List<String> postingNumbers) {
        return inspectAll(shop,postingNumbers,null);
    }
    public OzonPrintReadiness inspect(Shop shop,String number,KizSourceSelection sources){return inspectAll(shop,List.of(number),sources).postings().getFirst();}
    public OzonBatchPrintReadiness inspectAll(Shop shop,List<String> postingNumbers,KizSourceSelection sources){
        if(sources!=null&&!sources.anySelected())throw new IllegalStateException("KIZ_SOURCE_REQUIRED");
        MarketplaceGuard.requireOzon(shop);
        List<String> safePostings = postingNumbers == null ? List.of() : postingNumbers.stream()
                .map(value -> OzonApiClient.requireExternalId(value, "posting number"))
                .distinct()
                .toList();
        List<OzonPrintReadiness> results = new ArrayList<>();
        Map<String, Integer> unsecuredByGtin = new LinkedHashMap<>();
        // A shipment reads its catalog mapping once, rather than scanning every product for every order.
        Map<String,String> skuMappings=sources!=null&&!sources.purchased()?Map.of():mappings.findResolvedBySku(shop.getId());
        Set<String> exemptSkus=policies.findExemptSkus(shop.getId());
        Map<String,OzonPostingDto> postingSnapshot=postings.findMany(shop.getId(),safePostings);
        Map<String,OzonExemplarJob> jobSnapshot=jobs.findMany(shop.getId(),safePostings);
        Map<Long,Integer> bindingCounts=jobs.bindingCounts(shop.getId(),jobSnapshot.values().stream().map(OzonExemplarJob::id).toList());
        var unsecuredPostings=new ArrayList<OzonPostingDto>();
        for(String number:safePostings){var posting=postingSnapshot.get(number);var job=jobSnapshot.get(number);
            if(posting==null)continue;
            boolean secured=job!=null&&job.stage()!=OzonExemplarJobStage.CREATED&&job.stage()!=OzonExemplarJobStage.REJECTED
                && bindingCounts.getOrDefault(job.id(),0)==posting.items().stream().filter(i->posting.requirements().mandatoryMarkProductIds().contains(i.productId())||!exemptSkus.contains(i.sku())).mapToInt(OzonPostingItemDto::quantity).sum();
            if(!secured)unsecuredPostings.add(posting);
        }
        var categoryCoverage=sources!=null&&!sources.sellerImported()?Map.<String,Integer>of():OzonCategoryStock.coverage(shop.getId(),unsecuredPostings,exemptSkus);
        var requestedGtins=postingSnapshot.values().stream().flatMap(p->p.items().stream()).map(i->skuMappings.get(i.sku())).filter(java.util.Objects::nonNull).distinct().toList();
        Map<String,Integer> availableCounts=new java.util.HashMap<>(sources==null?inventory.availableCounts(shop.getId(),requestedGtins):sources.purchased()?inventory.purchasedAvailableCounts(shop.getId(),requestedGtins):Map.<String,Integer>of());
        for (String postingNumber : safePostings) {
            OzonExemplarJob job=jobSnapshot.get(postingNumber);
            int bindingCount=job==null?0:bindingCounts.getOrDefault(job.id(),0);
            OzonPrintReadiness readiness = inspect(shop,postingNumber,postingSnapshot.get(postingNumber),job,
                    bindingCount,skuMappings,exemptSkus,availableCounts,categoryCoverage,sources);
            results.add(readiness);
            if (!readiness.ready() || readiness.requiredKiz() == 0) continue;
            boolean secured = job != null
                    && job.stage() != OzonExemplarJobStage.CREATED
                    && job.stage() != OzonExemplarJobStage.REJECTED
                    && bindingCount == readiness.requiredKiz();
            if (secured) continue;
            for (OzonPrintReadiness.GtinAvailability value : readiness.gtinAvailability()) {
                unsecuredByGtin.merge(value.gtin(), value.required(), Integer::sum);
            }
        }
        List<OzonPrintReadiness.GtinAvailability> totals = unsecuredByGtin.entrySet().stream()
                .map(entry -> new OzonPrintReadiness.GtinAvailability(
                        entry.getKey(), entry.getValue(), availableCounts.computeIfAbsent(entry.getKey(),gtin->inventory.availableCount(shop.getId(),gtin))))
                .toList();
        boolean ready = !safePostings.isEmpty()
                && results.stream().allMatch(OzonPrintReadiness::ready)
                && totals.stream().allMatch(OzonPrintReadiness.GtinAvailability::sufficient);
        var remaining=new java.util.HashMap<>(availableCounts);var eligible=new ArrayList<String>();
        for(var value:results) {
            if(!value.ready())continue;
            var job=jobSnapshot.get(value.postingNumber());
            boolean secured=job!=null&&job.stage()!=OzonExemplarJobStage.CREATED&&job.stage()!=OzonExemplarJobStage.REJECTED
                    && bindingCounts.getOrDefault(job.id(),0)==value.requiredKiz();
            if(secured||value.requiredKiz()==0){eligible.add(value.postingNumber());continue;}
            if(value.gtinAvailability().stream().allMatch(g->remaining.getOrDefault(g.gtin(),0)>=g.required())) {
                value.gtinAvailability().forEach(g->remaining.compute(g.gtin(),(key,count)->count-g.required()));
                eligible.add(value.postingNumber());
            }
        }
        return new OzonBatchPrintReadiness(ready, results, totals,eligible);
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
