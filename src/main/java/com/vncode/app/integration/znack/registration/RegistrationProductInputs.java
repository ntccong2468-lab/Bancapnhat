package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import java.util.*;

/** Use the fresh marketplace snapshot for card contents, keeping the selected native checkpoint. */
public record RegistrationProductInputs(Sku sku,List<WbCharacteristic> characteristics){
    public RegistrationProductInputs{characteristics=List.copyOf(characteristics);}
    public static RegistrationProductInputs from(Sku selected,ProductSnapshot snapshot){
        if(snapshot.key().marketplace()!=Marketplace.WILDBERRIES||!snapshot.key().cardId().equals(Long.toString(selected.nmId()))
                ||!snapshot.key().variantId().equals(Long.toString(selected.chrtId())))throw new SecurityException("PRODUCT_MISMATCH");
        var variant=snapshot.variants().stream().filter(v->v.id().equals(Long.toString(selected.chrtId()))).findFirst().orElseThrow(()->new IllegalArgumentException("VARIANT_NOT_FOUND"));
        var facts=new ArrayList<WbCharacteristic>();
        for(var attribute:snapshot.attributes()){
            if(!attribute.complexId().isBlank()&&!attribute.complexId().equals("0"))continue;
            int id=0;try{id=Integer.parseInt(attribute.id());}catch(NumberFormatException ignored){}
            facts.add(new WbCharacteristic(id,attribute.name(),attribute.values()));
        }
        String category=snapshot.metadata().getOrDefault("source.category.name",selected.subjectName());
        var current=new Sku(selected.nmId(),selected.chrtId(),selected.subjectId(),snapshot.vendorArticle(),category,snapshot.brand(),snapshot.title(),
                variant.color(),variant.size(),selected.barcodes(),selected.imageUrl(),selected.needKiz(),selected.gtin(),selected.goodId(),selected.feedId(),
                selected.status(),selected.errorMessage(),selected.wbUpdated(),variant.russianSize(),selected.registrationRevision(),selected.tnvedGroupChanged());
        return new RegistrationProductInputs(current,facts);
    }
}
