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
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var key=new ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
        var snapshot=new ProductRepository().localSnapshot(key).orElse(null);
        if(snapshot==null||snapshot.checkedAt()==null||snapshot.checkedAt().isBefore(Instant.now().minusSeconds(60))
                ||!ListingIdentity.fingerprint(shop).equals(snapshot.metadata().get("owner")))
            snapshot=new WbProductSnapshotReader().readForSynchronization(shop,key);
        var inputs=RegistrationProductInputs.from(sku,snapshot);
        String tnved=ZnackWbAttributeMapper.findTnved(inputs.characteristics());
        var codes=snapshot.attributes().stream().filter(a->ProductFieldNames.matches("tnved",a.name())).flatMap(a->a.values().stream()).filter(v->v.matches("[0-9]{4,10}")).distinct().toList();
        if(codes.size()==1)tnved=codes.getFirst();
        if(tnved.isBlank())throw new RegistrationValidationException("ZNACK_MISSING_TNVED");
        var selected=ZnackRegistrationDocuments.select(snapshot,settings,LocalDate.now());
        if(selected.candidates().isEmpty())throw new RegistrationValidationException("ZNACK_MISSING_PRODUCT_DOCUMENT");
        var session=ZnackCardRegistrationWorkflow.productionCatalog(shop);
        var preflight=session.catalog().preflight(tnved);session.scope().assertCurrent(shop);
        selected=session.catalog().reviewDocuments(preflight.token(),selected,tnved);session.scope().assertCurrent(shop);
        selected=RegistrationReviewedValues.reviewedDocuments(shop,snapshot,selected);
        if(selected.automatic()==null||selected.requiresReview())throw new RegistrationValidationException("ZNACK_PRODUCT_DOCUMENT_REVIEW_REQUIRED");
        var snapshotForReview=snapshot;
        var resolved=ZnackCategoryResolver.resolve(inputs.sku().subjectName(),inputs.sku().title(),preflight.categories()).or(()->RegistrationReviewedValues.reviewedCategory(shop,snapshotForReview,preflight.categories())).orElseThrow(()->new RegistrationValidationException("ZNACK_CATEGORY_REVIEW_REQUIRED"));
        var required=session.catalog().requiredAttributes(resolved.id(),preflight.token());
        var mapped=new ZnackWbAttributeMapper().map(inputs.sku(),inputs.characteristics(),required,preflight.tnved(),preflight.categoryTnved(),selected.automatic());
        var draft=new Draft(preflight.tnved(),preflight.categoryTnved(),resolved.id(),mapped.goodName(),mapped.brand(),mapped.attributes(),mapped.attributeTypes(),autoSign).withProductCategory(inputs.sku().subjectName());
        draft=RegistrationReviewedValues.apply(shop,snapshot,draft,required);
        return complete(draft,required,inputs.sku().wbSize());
    }
    /** Only exact preset normalization or a sole permitted option is safe without seller input. */
    static Draft complete(Draft draft,List<Attribute> schema,String wbSize) {
        var values=new LinkedHashMap<>(draft.attributes());var types=new LinkedHashMap<>(draft.attributeTypes());
        for(int pass=0;pass<=schema.size();pass++){
            boolean changed=false;
            for(var attribute:RegistrationAttributeRules.required(schema,values)){
                if(omitted(attribute,draft))continue;
                String current=value(draft,values,attribute.id()),text=current.strip();
                if(attribute.presetOnly()&&!attribute.presets().contains(text)){
                    String normalized=normalize(text);var matches=attribute.presets().stream().filter(p->!normalized.isBlank()&&normalize(p).equals(normalized)).toList();
                    if(matches.size()==1)text=matches.getFirst();else if(text.isBlank()&&attribute.presets().size()==1)text=attribute.presets().getFirst();
                }
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
            String text=value(draft,values,attribute.id()).strip(),type=types.get(attribute.id());
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
