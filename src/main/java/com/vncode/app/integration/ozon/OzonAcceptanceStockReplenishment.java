package com.vncode.app.integration.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Explicit, seller-approved shortage replenishment for acceptance, never a background action. */
public final class OzonAcceptanceStockReplenishment {
    private final Function<Shop,OzonApiClient> clients;
    public OzonAcceptanceStockReplenishment(){this(shop->new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey())));}
    OzonAcceptanceStockReplenishment(Function<Shop,OzonApiClient> clients){this.clients=Objects.requireNonNull(clients);}

    /** Caller passes original postings; native split children are verified against the durable source. */
    public void run(Shop shop,List<String> numbers,KizSourceSelection sources,
                    Predicate<KizPrintPurchaseSession.Proposal> consent,Consumer<String> progress,BooleanSupplier cancelled)throws Exception {
        if(sources==null||!sources.purchased())throw new IOException("OZON_ACCEPT_KIZ_STOCK_REQUIRED");
        Objects.requireNonNull(consent);Objects.requireNonNull(progress);Objects.requireNonNull(cancelled);
        var frozen=List.copyOf(new LinkedHashSet<>(numbers));
        if(frozen.isEmpty()||frozen.size()>1000)throw new IllegalArgumentException("OZON_ACCEPT_PURCHASE_SELECTION_INVALID");
        new KizPrintPurchaseSession(shop,consent,progress,cancelled).run(()->{replenish(shop,frozen,sources);return null;});
    }

    private void replenish(Shop shop,List<String> numbers,KizSourceSelection sources)throws Exception {
        MarketplaceGuard.requireOzon(shop);ListingIdentity.require(shop);LicenseRuntime.require(Permission.SELLER_API);
        var api=clients.apply(shop);var repo=new OzonPostingRepository();var postings=new LinkedHashMap<String,OzonPostingDto>();var conflicting=new HashSet<String>();
        var receipts=new OzonUnitShipRepository();
        for(String number:numbers){
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            ListingIdentity.require(shop);
            // Child rows can appear independently after a split. The original parent receipt
            // owns both the source selection and the full child identity check.
            if(receipts.pendingParentOfChild(shop,number)!=null)continue;
            var receipt=receipts.find(shop,number);
            if(receipt!=null&&!"REJECTED".equals(receipt.state())){
                // A resumed split owns its original source selection; never charge against a new or unknown one.
                if(receipt.sources()==null||!receipt.sources().equals(sources)){blockChildren(postings,conflicting,receipt.children());continue;}
                if("COMPLETE".equals(receipt.state())||receipt.children().isEmpty())continue;
                var children=verifiedChildren(api,receipt);
                if(children.isEmpty()){blockChildren(postings,conflicting,receipt.children());continue;}
                for(var posting:children){repo.upsertDetail(shop.getId(),posting);if("awaiting_packaging".equalsIgnoreCase(posting.status())&&OzonAcceptanceKizService.requiredBeforeAccept(shop,posting))addUnique(postings,conflicting,posting);}
            }else{
                OzonPostingDto posting;
                try{posting=OzonJson.requirePostingDetail(api.getPosting(number,true,true),number);}
                catch(OzonApiException error){if(error.statusCode()==404)continue;throw error;}
                repo.upsertDetail(shop.getId(),posting);
                // An uncertain split can have no children_json yet. Same order/warehouse
                // still belongs to the unfinished multi-unit receipt until reconciled.
                if(receipts.pendingParentForPosting(shop,posting)!=null)continue;
                if("awaiting_packaging".equalsIgnoreCase(posting.status())&&OzonAcceptanceKizService.requiredBeforeAccept(shop,posting))addUnique(postings,conflicting,posting);
            }
        }
        if(postings.isEmpty())return;
        var exempt=new OzonProductKizPolicyRepository().findExemptSkus(shop.getId());
        var skuMappings=new OzonProductGtinMappingRepository().findResolvedBySku(shop.getId());
        var jobs=new OzonExemplarJobRepository();var pending=new ArrayList<OzonPostingDto>();
        for(var posting:postings.values()){
            var job=jobs.find(shop.getId(),posting.postingNumber());
            if(job!=null){
                var bindings=jobs.bindings(job.id());
                if(!bindings.isEmpty()){
                    int units=posting.items().stream().filter(i->posting.requirements().mandatoryMarkProductIds().contains(i.productId())||!exempt.contains(i.sku())).mapToInt(OzonPostingItemDto::quantity).sum();
                    if(bindings.size()!=units)continue;
                    continue;
                }
                if(job.mutationAttemptedAt()!=null&&!job.mutationAttemptedAt().isBlank())continue;
                if(job.stage()!=OzonExemplarJobStage.CREATED&&job.stage()!=OzonExemplarJobStage.REJECTED)continue;
            }
            pending.add(posting);
        }
        if(pending.isEmpty())return;
        var category=sources.sellerImported()?OzonCategoryStock.coverage(shop.getId(),pending,exempt):Map.<String,Integer>of();
        var quantities=new TreeMap<String,Integer>();var candidates=new ArrayList<KizPurchaseMappingProof.Product>();
        try(var connection=Database.getConnection()){
            var products=OzonCategoryStock.verifiedProducts(connection,shop.getId(),pending.stream().flatMap(p->p.items().stream()).toList());
            for(var posting:pending){
                var skus=posting.items().stream().filter(i->category.getOrDefault(OzonCategoryStock.key(posting.postingNumber(),i.itemIndex()),0)>0).map(OzonPostingItemDto::sku).collect(java.util.stream.Collectors.toSet());
                OzonRequirementGuard.PreparationPlan plan;
                try{plan=OzonRequirementGuard.plan(posting,skuMappings,exempt,skus);}
                catch(OzonRequirementGuard.MissingMappingException|OzonRequirementGuard.UnsupportedRequirementException notReady){if(!skus.isEmpty())throw new IOException("OZON_ACCEPT_CATEGORY_ALLOCATION_REVIEW");continue;}
                // Avoid purchasing for unrelated items when a mixed posting has incomplete Ozon requirements.
                if(plan.items().stream().anyMatch(item->!item.mandatory())){if(!skus.isEmpty())throw new IOException("OZON_ACCEPT_CATEGORY_ALLOCATION_REVIEW");continue;}
                if(plan.items().stream().anyMatch(item->item.quantity()>category.getOrDefault(OzonCategoryStock.key(posting.postingNumber(),item.itemIndex()),0)&&(item.gtin()==null||item.gtin().isBlank()))){if(!skus.isEmpty())throw new IOException("OZON_ACCEPT_CATEGORY_ALLOCATION_REVIEW");continue;}
                for(var item:plan.items()){
                    int remaining=item.quantity()-category.getOrDefault(OzonCategoryStock.key(posting.postingNumber(),item.itemIndex()),0);
                    if(remaining<=0)continue;
                    if(item.gtin()==null||item.gtin().isBlank())throw new OzonRequirementGuard.MissingMappingException(item.sku());
                    quantities.merge(item.gtin(),remaining,Math::addExact);
                    candidates.add(new KizPurchaseMappingProof.Product(products.get(item.sku()),item.sku(),item.gtin(),posting.postingNumber()));
                }
            }
        }
        if(quantities.isEmpty())return;
        var result=new KizStockReplenisher().ensurePurchased(shop,quantities,missing->KizPurchaseMappingProof.require(shop,candidates,missing.keySet()));
        if(result.waiting())throw new KizPurchasePendingException(quantities,result);
        if(!result.missing().isEmpty())throw new IOException("OZON_ACCEPT_KIZ_STOCK_REQUIRED");
    }

    private static void addUnique(Map<String,OzonPostingDto> postings,Set<String> conflicting,OzonPostingDto posting){
        String id=posting.postingNumber();if(conflicting.contains(id))return;
        var previous=postings.putIfAbsent(id,posting);
        if(previous!=null&&!previous.equals(posting)){postings.remove(id);conflicting.add(id);}
    }
    private static void blockChildren(Map<String,OzonPostingDto> postings,Set<String> conflicting,List<String> children){
        for(String child:children){postings.remove(child);conflicting.add(child);}
    }

    /** A split may be ambiguous or return foreign children. Do not buy until every child is proven. */
    private static List<OzonPostingDto> verifiedChildren(OzonApiClient api,OzonUnitShipRepository.Receipt receipt)throws IOException {
        var source=receipt.source();var ids=new LinkedHashSet<>(receipt.children());
        if(ids.size()!=OzonPackageBuilder.unitCount(source))return List.of();
        var children=new ArrayList<OzonPostingDto>();var expected=new TreeMap<String,Integer>();var actual=new TreeMap<String,Integer>();
        for(var item:source.items())expected.merge(item.productId()+"\u0000"+item.sku(),item.quantity(),Math::addExact);
        for(var id:ids){
            OzonPostingDto child;
            try{child=OzonJson.requirePostingDetail(api.getPosting(id,true,true),id);}
            catch(OzonApiException error){if(error.statusCode()==404)return List.of();throw error;}
            if(!source.orderId().equals(child.orderId())||!source.warehouseId().equals(child.warehouseId())||OzonPackageBuilder.unitCount(child)!=1)return List.of();
            for(var item:child.items())actual.merge(item.productId()+"\u0000"+item.sku(),item.quantity(),Math::addExact);
            children.add(child);
        }
        return expected.equals(actual)?List.copyOf(children):List.of();
    }
}
