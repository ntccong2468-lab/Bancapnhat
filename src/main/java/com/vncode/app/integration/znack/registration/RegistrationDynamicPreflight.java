package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow.CatalogSession;

/** Read-only Catalog/schema review. No GTIN allocation, feed creation or signature is sent here. */
public final class RegistrationDynamicPreflight {
    public record Missing(ProductKey key,String article,String variant,Attribute attribute,String before,String suggestion,String suggestedType,String reason){}
    public record Plan(ProductSnapshot snapshot,Draft draft,List<Attribute> schema,ZnackRegistrationDocuments.Selection documents){public Plan(ProductSnapshot snapshot,Draft draft,List<Attribute> schema){this(snapshot,draft,schema,null);}public Plan{schema=List.copyOf(schema);}}
    public record DocumentWarning(ProductKey key,String article,String detail){}
    public record Blocked(ProductKey key,String article,String field,String detail,List<Category> categories){public Blocked{categories=List.copyOf(categories);}}
    public record Review(List<Plan> plans,List<Missing> missing,List<DocumentWarning> documents,List<Blocked> blocked){public Review(List<Plan> plans,List<Missing> missing){this(plans,missing,List.of(),List.of());}public Review{plans=List.copyOf(plans);missing=List.copyOf(missing);documents=List.copyOf(documents);blocked=List.copyOf(blocked);}}
    private final ZnackCardRegistrationWorkflow.CatalogFactory catalogs;
    private final java.util.function.Function<Shop,List<com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin>> catalogRows;
    public RegistrationDynamicPreflight(){this(ZnackCardRegistrationWorkflow::productionCatalog);}
    public RegistrationDynamicPreflight(ZnackCardRegistrationWorkflow.CatalogFactory catalogs){this(catalogs,shop->new com.vncode.app.features.gtinsync.GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace()));}
    RegistrationDynamicPreflight(ZnackCardRegistrationWorkflow.CatalogFactory catalogs,java.util.function.Function<Shop,List<com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin>> catalogRows){this.catalogs=catalogs;this.catalogRows=catalogRows;}
    public Review read(Shop shop,List<ProductSnapshot> snapshots,Map<ProductKey,Map<Long,RegistrationReviewedValues.Value>> entered)throws Exception{
        return read(shop,snapshots,entered,Map.of());
    }
    public Review read(Shop shop,List<ProductSnapshot> snapshots,Map<ProductKey,Map<Long,RegistrationReviewedValues.Value>> entered,Map<ProductKey,String> acknowledgedDocuments)throws Exception{
        return read(shop,snapshots,entered,acknowledgedDocuments,Map.of());
    }
    public Review read(Shop shop,List<ProductSnapshot> snapshots,Map<ProductKey,Map<Long,RegistrationReviewedValues.Value>> entered,Map<ProductKey,String> acknowledgedDocuments,Map<ProductKey,Long> categories)throws Exception{
        CatalogSession session=null;ZnackNationalCatalogService.CountryReadScope countryRead=null;var preflights=new HashMap<String,ZnackNationalCatalogService.Preflight>();var schemas=new HashMap<Long,List<Attribute>>();var plans=new ArrayList<Plan>();var missing=new ArrayList<Missing>();var warnings=new ArrayList<DocumentWarning>();var blocked=new ArrayList<Blocked>();var documentsCache=new HashMap<String,ZnackRegistrationDocuments.Selection>();
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var draftCandidates=new DraftCandidates(shop,catalogRows.apply(shop));
        try{
        for(var original:snapshots){
            var snapshot=shop.getMarketplace()==Marketplace.OZON?new com.vncode.app.integration.ozon.OzonShopBrand().apply(shop,original):original;
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_PREFLIGHT_CANCELLED");ListingIdentity.require(shop,snapshot.key());
            if(session==null){session=catalogs.open(shop);countryRead=session.catalog().registrationCountryReadScope();}session.scope().assertCurrent(shop);
            String tnved=ProductFieldNames.tnvedCode(snapshot.attributes().stream().filter(a->ProductFieldNames.matches("tnved",a.name())).flatMap(a->a.values().stream()).findFirst().orElse(""));
            boolean existingDraft=draftCandidates.matches(shop,snapshot);String preflightKey=tnved+"/"+existingDraft;
            var checked=preflights.get(preflightKey);if(checked==null){checked=existingDraft?session.catalog().preflightExisting(tnved,session.token()):session.catalog().preflight(tnved);preflights.put(preflightKey,checked);}
            var selectedDocuments=ZnackRegistrationDocuments.select(snapshot,settings,LocalDate.now());String documentKey=tnved+"/"+selectedDocuments.source()+"/"+RegistrationReviewedValues.permitRevision(selectedDocuments);var documents=documentsCache.get(documentKey);if(documents==null){documents=session.catalog().reviewDocuments(session.token(),selectedDocuments,tnved);documentsCache.put(documentKey,documents);}
            if(documents.automatic()==null&&documents.candidates().size()==1&&!documents.issues().isEmpty()&&documents.issues().stream().allMatch(RegistrationPermitValidator::advisory))documents=new ZnackRegistrationDocuments.Selection(documents.candidates(),documents.candidates().getFirst(),documents.source(),false,documents.issues());
            if(documents.automatic()==null||documents.requiresReview()){blocked.add(new Blocked(snapshot.key(),snapshot.vendorArticle(),"documents","ZNACK_PRODUCT_DOCUMENT_REVIEW_REQUIRED; "+String.join("; ",documents.issues()),List.of()));continue;}
            if(!documents.issues().isEmpty()&&!RegistrationReviewedValues.permitRevision(documents).equals(acknowledgedDocuments.get(snapshot.key())))warnings.add(new DocumentWarning(snapshot.key(),snapshot.vendorArticle(),String.join("; ",documents.issues())));
            var category=categories.containsKey(snapshot.key())?checked.categories().stream().filter(c->c.id()==categories.get(snapshot.key())).findFirst():ZnackCategoryResolver.resolve(snapshot,checked.categories());
            if(category.isEmpty()){blocked.add(new Blocked(snapshot.key(),snapshot.vendorArticle(),"catalog_category","ZNACK_CATEGORY_REVIEW_REQUIRED",checked.categories()));continue;}var resolved=category.orElseThrow();
            var schema=schemas.get(resolved.id());if(schema==null){schema=session.catalog().registrationAttributes(resolved.id(),session.token());schemas.put(resolved.id(),schema);}
            Draft draft;
            if(shop.getMarketplace()==Marketplace.WILDBERRIES){
                var selected=new ZnackCardRegistrationRepository().find(shop.getId(),Long.parseLong(snapshot.key().variantId()));if(selected==null)throw new SecurityException("SOURCE_PRODUCT_NOT_OBSERVED");
                var inputs=RegistrationProductInputs.from(selected,snapshot);var mapped=new ZnackWbAttributeMapper().map(inputs.sku(),inputs.characteristics(),schema,checked.tnved(),checked.categoryTnved(),documents.automatic());
                draft=new Draft(checked.tnved(),checked.categoryTnved(),resolved.id(),mapped.goodName(),mapped.brand(),mapped.attributes(),mapped.attributeTypes()).withProductCategory(inputs.sku().subjectName());
            }else{
                var preparation=OzonCardRegistrationWorkflow.reviewedPreparation(shop,snapshot,checked,resolved.id(),schema,documents,session);
                draft=OzonCardRegistrationWorkflow.draftForReview(preparation,false);schema=preparation.required();
            }
            draft=RegistrationVerifiedDefaults.applyAutomatic(draft,schema,session.catalog(),documents.automatic(),snapshot);
            try{draft=RegistrationReviewedValues.apply(shop,snapshot,draft,schema);}catch(RegistrationValidationException changed){if(changed.getMessage()==null||!changed.getMessage().contains("ZNACK_REVIEWED_VALUES_CHANGED"))throw changed;/* Rebuild the proposal from fresh facts; worker loads remain strict. */}
            draft=RegistrationReviewedValues.apply(draft,entered.getOrDefault(snapshot.key(),Map.of()));
            var plan=new Plan(snapshot,draft,schema,documents);plans.add(plan);missing.addAll(inspect(plan));
        }
        return new Review(plans,missing,warnings,blocked);
        }finally{if(countryRead!=null)countryRead.close();}
    }
    /** A scoped, recent, unique physical Draft permits quota-free review, never a write. */
    static boolean knownDraftForReview(Shop shop,ProductSnapshot snapshot){
        return new DraftCandidates(shop,new com.vncode.app.features.gtinsync.GtinSyncRepository().catalog(shop.getId(),shop.getMarketplace())).matches(shop,snapshot);
    }
    private static final class DraftCandidates{
        private final Map<String,List<com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin>> articles=new HashMap<>();
        private final com.vncode.app.features.gtinsync.OzonSkuGtinMatcher ozon;
        DraftCandidates(Shop shop,List<com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin> observed){
            com.vncode.app.features.gtinsync.RegisteredGtinProof.Scope scope;
            try{scope=com.vncode.app.features.gtinsync.RegisteredGtinProof.current(shop);}
            catch(com.vncode.app.features.gtinsync.RegisteredGtinProof.UnverifiedOwnerException unknown){ozon=new com.vncode.app.features.gtinsync.OzonSkuGtinMatcher(List.of(),true);return;}
            var now=java.time.Instant.now();
            var rows=observed.stream()
                .filter(row->row.source().equals(scope.source())&&row.tradeUnit()
                        &&com.vncode.app.features.gtinsync.RegisteredGtinProof.identifiedObservation(row)
                        &&!row.syncedAt().isBefore(now.minusSeconds(86400))&&!row.syncedAt().isAfter(now.plusSeconds(300)))
                .toList();
            for(var row:rows)articles.computeIfAbsent(com.vncode.app.features.gtinsync.GtinMatchingService.normalizeArticle(row.article()),key->new ArrayList<>()).add(row);
            ozon=new com.vncode.app.features.gtinsync.OzonSkuGtinMatcher(rows,true);
        }
        boolean matches(Shop shop,ProductSnapshot snapshot){
            var variants=snapshot.variants().stream().filter(value->value.id().equals(snapshot.key().variantId())).toList();
            if(variants.size()!=1)return false;var variant=variants.getFirst();
            var matches=articles.getOrDefault(com.vncode.app.features.gtinsync.GtinMatchingService.normalizeArticle(snapshot.vendorArticle()),List.of()).stream().filter(row->com.vncode.app.integration.listing.ListingIdentity.compatibleVariant(row,snapshot.vendorArticle(),variant.color(),variant.size(),variant.russianSize())).toList();
            if(matches.isEmpty()&&shop.getMarketplace()==Marketplace.OZON)matches=ozon.candidates(snapshot.key().variantId(),snapshot.vendorArticle(),variant.color(),variant.size(),variant.russianSize(),snapshot.title(),snapshot.metadata().getOrDefault("source.category.name",""),snapshot.brand());
            return matches.size()==1&&CatalogCardState.reviewableDraft(matches.getFirst());
        }
    }
    public static List<Missing> inspect(Plan plan){
        var result=new ArrayList<Missing>();var draft=plan.draft();String wbSize=plan.snapshot().variants().stream().filter(v->v.id().equals(plan.snapshot().key().variantId())).map(ProductVariant::russianSize).findFirst().orElse("");
        for(var attribute:RegistrationAttributeRules.required(plan.schema(),draft.attributes())){
            if(Set.of(3959L,13933L,23557L,23561L).contains(attribute.id()))continue;
            String before=Objects.toString(draft.attributes().get(attribute.id()),attribute.id()==2478?draft.goodName():attribute.id()==2504?draft.brand():"");String text=RegistrationTemplate.clean(before),type=draft.attributeTypes().get(attribute.id());
            boolean valueValid=!text.isBlank()&&text.length()<=5000&&(!attribute.presetOnly()||attribute.presets().contains(text))&&RegistrationAttributeRules.validFieldValue(attribute,text);
            boolean typeValid=attribute.valueTypes().isEmpty()||type!=null&&attribute.valueTypes().contains(type);
            if(valueValid&&typeValid)continue;
            String suggestion=text;
            if(attribute.presetOnly()&&!attribute.presets().contains(suggestion)){
                String comparable=normalized(text);var matches=attribute.presets().stream().filter(v->!comparable.isBlank()&&normalized(v).equals(comparable)).toList();
                suggestion=matches.size()==1?matches.getFirst():text.isBlank()&&attribute.presets().size()==1?attribute.presets().getFirst():"";
            }
            if(!suggestion.isBlank()&&!RegistrationAttributeRules.validFieldValue(attribute,suggestion))suggestion="";
            String suggestedType=typeValid?type:ZnackWbAttributeMapper.resolveValueType(attribute,suggestion,wbSize);
            result.add(new Missing(plan.snapshot().key(),plan.snapshot().vendorArticle(),plan.snapshot().key().variantId(),attribute,before,suggestion,suggestedType,valueValid?"type":"value"));
        }
        return List.copyOf(result);
    }
    public static List<RegistrationReviewedValues.Approval> approvals(Review review){
        if(!review.missing().isEmpty()||!review.documents().isEmpty()||!review.blocked().isEmpty())throw new RegistrationValidationException("ZNACK_REQUIRED_ATTRIBUTES_MISSING");
        var approvals=new ArrayList<RegistrationReviewedValues.Approval>();
        for(var plan:review.plans()){
            var values=new LinkedHashMap<Long,RegistrationReviewedValues.Value>();
            for(var attribute:RegistrationAttributeRules.required(plan.schema(),plan.draft().attributes()))if(!Set.of(3959L,13933L,23557L,23561L).contains(attribute.id()))values.put(attribute.id(),new RegistrationReviewedValues.Value(plan.draft().attributes().getOrDefault(attribute.id(),""),plan.draft().attributeTypes().getOrDefault(attribute.id(),"")));
            approvals.add(new RegistrationReviewedValues.Approval(plan.snapshot(),plan.schema(),values,plan.documents()==null?"":RegistrationReviewedValues.permitRevision(plan.documents()),plan.draft().categoryId()));
        }
        return List.copyOf(approvals);
    }
    private static String normalized(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replace('ё','е');}
}
