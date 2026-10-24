package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.wb.WbProductSnapshotReader;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.models.Shop;
import java.time.*;
import java.util.*;

/** The established default template and permit rules, usable without an open JavaFX view. */
final class RegistrationAutomaticDraft {
    private static final Set<Long> OMITTED=Set.of(3959L,ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,ZnackNationalCatalogService.CERTIFICATE_ATTRIBUTE_ID);
    static Draft prepare(Shop shop,Sku sku,boolean autoSign)throws Exception {
        return prepareForSubmission(shop,sku,autoSign).draft();
    }
    static ZnackRegistrationBatchService.PreparedDraft prepareForSubmission(Shop shop,Sku sku,boolean autoSign)throws Exception {
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var key=new ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
        var snapshot=new ProductRepository().localSnapshot(key).orElse(null);
        if(snapshot==null||snapshot.checkedAt()==null||snapshot.checkedAt().isBefore(Instant.now().minusSeconds(60))
                ||!ListingIdentity.fingerprint(shop).equals(snapshot.metadata().get("owner")))
            snapshot=new WbProductSnapshotReader().readForSynchronization(shop,key);
        var inputs=RegistrationProductInputs.from(sku,snapshot);
        var codes=snapshot.attributes().stream().filter(a->ProductFieldNames.matches("tnved",a.name())).flatMap(a->a.values().stream()).map(ProductFieldNames::tnvedCode).filter(v->!v.isBlank()).distinct().toList();
        String tnved=codes.size()==1?codes.getFirst():"";
        if(tnved.isBlank())throw new RegistrationValidationException("ZNACK_MISSING_TNVED");
        var selected=ZnackRegistrationDocuments.select(snapshot,settings,LocalDate.now());
        if(selected.candidates().isEmpty())throw new RegistrationValidationException("ZNACK_MISSING_PRODUCT_DOCUMENT");
        var session=ZnackCardRegistrationWorkflow.productionCatalog(shop);
        ZnackNationalCatalogService.Preflight preflight;
        if(sku.canReviewExistingDraft())preflight=session.catalog().preflightExisting(tnved,session.token());
        else try{preflight=session.catalog().preflight(tnved);}
        catch(IllegalStateException quota){
            if(quota.getMessage()==null||!quota.getMessage().startsWith("GS1/GTIN quota is unavailable or exhausted")||!RegistrationDynamicPreflight.knownDraftForReview(shop,snapshot))throw quota;
            preflight=session.catalog().preflightExisting(tnved,session.token());
        }
        session.scope().assertCurrent(shop);
        var reviewPreflight=preflight;
        selected=session.catalog().reviewDocuments(preflight.token(),selected,tnved);session.scope().assertCurrent(shop);
        selected=RegistrationReviewedValues.reviewedDocuments(shop,snapshot,selected);
        if(selected.automatic()==null||selected.requiresReview())throw new RegistrationValidationException("ZNACK_PRODUCT_DOCUMENT_REVIEW_REQUIRED");
        var snapshotForReview=snapshot;
        var resolved=ZnackCategoryResolver.resolve(inputs.sku().subjectName(),inputs.sku().title(),preflight.categories()).or(()->RegistrationReviewedValues.reviewedCategory(shop,snapshotForReview,reviewPreflight.categories())).orElseThrow(()->new RegistrationValidationException("ZNACK_CATEGORY_REVIEW_REQUIRED"));
        try(var countryRead=session.catalog().registrationCountryReadScope()){
        var required=session.catalog().registrationAttributes(resolved.id(),preflight.token());
        var mapped=new ZnackWbAttributeMapper().map(inputs.sku(),inputs.characteristics(),required,preflight.tnved(),preflight.categoryTnved(),selected.automatic());
        var draft=new Draft(preflight.tnved(),preflight.categoryTnved(),resolved.id(),mapped.goodName(),mapped.brand(),mapped.attributes(),mapped.attributeTypes(),autoSign).withProductCategory(inputs.sku().subjectName());
        draft=RegistrationVerifiedDefaults.applyAutomatic(draft,required,session.catalog(),selected.automatic(),snapshot);
        draft=RegistrationReviewedValues.apply(shop,snapshot,draft,required);
        return new ZnackRegistrationBatchService.PreparedDraft(complete(draft,required,inputs.sku().wbSize()),session);
        }
    }
    /** Normalize known source values; a sole allowed option is not proof of a product fact. */
    static Draft complete(Draft draft,List<Attribute> schema,String wbSize) {
        var values=new LinkedHashMap<>(draft.attributes());var types=new LinkedHashMap<>(draft.attributeTypes());
        for(int pass=0;pass<=schema.size();pass++){
            boolean changed=false;
            for(var attribute:RegistrationAttributeRules.required(schema,values)){
                if(omitted(attribute,draft))continue;
                String current=value(draft,values,attribute.id()),text=RegistrationPresetNormalizer.resolve(attribute,current);
                if(text.isBlank()&&!RegistrationTemplate.clean(current).isBlank())text=RegistrationTemplate.clean(current);
                if(!text.equals(current)){values.put(attribute.id(),text);changed=true;}
                String type=types.get(attribute.id());
                if(type==null||!attribute.valueTypes().isEmpty()&&!attribute.valueTypes().contains(type))type=ZnackWbAttributeMapper.resolveValueType(attribute,text,wbSize);
                if(type!=null)types.put(attribute.id(),type);
            }
            if(!changed)break;
        }
        var missing=new ArrayList<String>();
        for(var attribute:RegistrationAttributeRules.required(schema,values)){
            if(omitted(attribute,draft))continue;
            String text=RegistrationTemplate.clean(value(draft,values,attribute.id())),type=types.get(attribute.id());
            if(text.isBlank()||text.length()>5000||attribute.presetOnly()&&!attribute.presets().contains(text)||!RegistrationAttributeRules.validFieldValue(attribute,text)
                    ||type==null||!attribute.valueTypes().isEmpty()&&!attribute.valueTypes().contains(type))missing.add(attribute.name()+" ["+attribute.id()+"]");
        }
        String name=values.getOrDefault(2478L,draft.goodName()),brand=values.getOrDefault(2504L,draft.brand());
        if(name==null||name.isBlank())missing.add("Наименование товара [2478]");
        if(brand==null||brand.isBlank())missing.add("Товарный знак [2504]");
        if(!missing.isEmpty())throw new RegistrationValidationException("ZNACK_REQUIRED_ATTRIBUTES_MISSING: "+String.join(", ",missing));
        return new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),name,brand,values,types,draft.autoSign(),draft.isKit());
    }
    private static boolean omitted(Attribute a,Draft d){return OMITTED.contains(a.id())||a.id()==13933L&&d.feedTnved().length()==10;}
    private static String value(Draft d,Map<Long,String> values,long id){return Objects.requireNonNullElse(values.getOrDefault(id,id==2478L?d.goodName():id==2504L?d.brand():""),"");}
    private static String normalize(String text){return java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replace('ё','е');}
}
