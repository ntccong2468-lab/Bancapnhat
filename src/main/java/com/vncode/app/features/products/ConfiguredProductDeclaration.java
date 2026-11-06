package com.vncode.app.features.products;

import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** The product editor uses the selected shop's configured declaration, never a cached certificate. */
public final class ConfiguredProductDeclaration {
    private ConfiguredProductDeclaration(){}
    public static ProductDocument load(Shop shop){
        ListingIdentity.require(shop);
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var document=fromSettings(shop,settings);
        if(shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON){
            var matches=new LinkedHashMap<String,ProductDocument>();
            for(var existing:new ProductRepository().shopDocuments(shop))
                if(isDeclaration(existing.type())&&document.number().equals(existing.number())
                        &&Objects.equals(document.validFrom(),existing.validFrom())
                        &&(document.validUntil()==null||Objects.equals(document.validUntil(),existing.validUntil()))
                        &&Objects.toString(existing.id(),"").matches("[1-9][0-9]{0,18}")
                        &&"https://api-seller.ozon.ru/v1/product/certificate/list".equals(existing.source()))matches.put(existing.id(),existing);
            // The adapter reads certificate/info again before binding this owned reference.
            if(matches.size()==1)document=matches.values().iterator().next();
        }
        ListingIdentity.require(shop);
        return document;
    }
    public static ProductDocument fromSettings(Shop shop,ZnackModels.Settings settings){
        if(!settings.hasDefaultGoodsDocument())throw new IllegalArgumentException("PRODUCT_DEFAULT_DECLARATION_MISSING");
        if(!isDeclaration(settings.documentType()))
            throw new IllegalArgumentException("PRODUCT_DEFAULT_DECLARATION_TYPE");
        var issued=date(settings.documentDate());var expires=date(settings.documentExpiryDate());
        if(issued==null)throw new IllegalArgumentException("PRODUCT_DEFAULT_DECLARATION_MISSING");
        // An absent expiry is unknown, not a declaration that the document never expires.
        return new ProductDocument("","declaration",settings.documentNumber().strip(),issued,expires,"","",null,
                shop.getMarketplace(),DocumentState.UNVERIFIED,"app:configured-declaration",Instant.now(),
                Map.of("type","declaration","certificate_type","declaration","isEndless","false"));
    }
    public static boolean isDeclaration(String type){return Set.of("2","DECLARATION","CONFORMITY_DECLARATION","DECLARATION_OF_CONFORMITY","ДЕКЛАРАЦИЯ").contains(Objects.toString(type,"").strip().toUpperCase(Locale.ROOT));}
    private static LocalDate date(String value){
        if(value==null||value.isBlank())return null;
        try{return LocalDate.parse(value.strip());}
        catch(DateTimeParseException iso){return LocalDate.parse(value.strip(),DateTimeFormatter.ofPattern("dd.MM.uuuu",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT));}
    }
}
