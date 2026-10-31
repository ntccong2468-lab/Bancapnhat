package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationProvenanceDialogTest {
    @Test void onlyShowsOriginForTheSameValueAndExactVariant(){
        var plan=plan(false,null);var same=evidence("variant.size","L");var changed=evidence("variant.size","XL");
        assertTrue(RegistrationProvenanceDialog.matchesEvidence(plan,same));assertFalse(RegistrationProvenanceDialog.matchesEvidence(plan,changed));
        assertFalse(RegistrationProvenanceDialog.matchesEvidence(plan(true,null),same));
    }
    @Test void documentEvidenceUsesTheWholeDocumentRatherThanOnlyItsNumber(){
        var doc=new ZnackModels.GoodsDocument("CONFORMITY_DECLARATION","DECL-1","2026-01-01");var plan=plan(false,doc);
        assertTrue(RegistrationProvenanceDialog.matchesEvidence(plan,evidence("product.document",new com.google.gson.Gson().toJson(doc))));
        assertFalse(RegistrationProvenanceDialog.matchesEvidence(plan,evidence("product.document","DECL-1")));
    }
    private static RegistrationFieldProvenance.Evidence evidence(String field,String value){return new RegistrationFieldProvenance.Evidence(field,ListingIdentity.hash(value),RegistrationFieldProvenance.Source.MARKETPLACE_UNVERIFIED,Instant.EPOCH);}
    private static RegistrationDynamicPreflight.Plan plan(boolean duplicate,ZnackModels.GoodsDocument doc){
        var key=new ProductKey(1,Marketplace.OZON,"100","offer");var variants=new ArrayList<ProductVariant>();variants.add(new ProductVariant("offer","SKU","Blue","L","",false,"48"));if(duplicate)variants.add(new ProductVariant("offer","SKU2","Red","XL","",false,"50"));
        var snapshot=new ProductSnapshot(key,"revision","Shirt","","Brand","SKU","category",List.of(),List.of(),variants,null,List.of(),Instant.EPOCH);
        var draft=new Draft("6103109000","6103",10,"Shirt","Brand",Map.of(),Map.of(),false,false);
        var selection=doc==null?null:new ZnackRegistrationDocuments.Selection(List.of(doc),doc,ZnackRegistrationDocuments.Source.PRODUCT,false);
        return new RegistrationDynamicPreflight.Plan(snapshot,draft,List.of(),selection);
    }
}
