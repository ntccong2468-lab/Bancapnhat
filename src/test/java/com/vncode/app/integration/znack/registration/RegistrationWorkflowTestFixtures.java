package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.util.List;
import java.util.concurrent.Executor;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;

/** Explicit complete-empty Catalog observations; fake workflow tests never call a production host. */
final class RegistrationWorkflowTestFixtures {
    static RegistrationExistingCards.LookupContext emptyLookup(){
        return new RegistrationExistingCards.LookupContext((s,c)->List.of(),(c,g,id)->{throw new AssertionError("No candidate in complete empty fixture");},(s,c)->"fixture:"+s.getId());
    }
    static ZnackCardRegistrationWorkflow workflow(ZnackCardRegistrationRepository r,ZnackCardRegistrationWorkflow.CatalogFactory f,Executor e){return workflow(r,f,e,false,(s,n,v,g)->false);}
    static ZnackCardRegistrationWorkflow workflow(ZnackCardRegistrationRepository r,ZnackCardRegistrationWorkflow.CatalogFactory f,Executor e,boolean strict,ZnackCardRegistrationWorkflow.MarketplaceCompletion completion){return new ZnackCardRegistrationWorkflow(r,f,e,strict,completion,RegistrationWorkflowTestFixtures::emptyLookup);}
    static ZnackCardRegistrationRepository repository(Shop shop){
        GtinWriteTestFixture.saveSettings(shop,GtinWriteTestFixture.CERTIFICATE,ZnackModels.PRODUCTION_TRUE_API);
        return new ZnackCardRegistrationRepository(){
            private void bind(long id){bindScope(shop,id,ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings()));}
            @Override public boolean claim(int shopId,Sku row,RegistrationAction action){boolean claimed=super.claim(shopId,row,action);if(claimed)bind(row.chrtId());return claimed;}
            @Override public void saveGenerated(int shopId,Sku row,String gtin,String tnved,long category,String name,String payload){super.saveGenerated(shopId,row,gtin,tnved,category,name,payload);bind(row.chrtId());}
            @Override public void saveReadyToSubmitGenerated(int shopId,Sku row,String gtin,String tnved,long category,String name,String payload){super.saveReadyToSubmitGenerated(shopId,row,gtin,tnved,category,name,payload);bind(row.chrtId());}
        };
    }
    static boolean sign(ZnackCardRegistrationWorkflow workflow,Shop shop,List<Sku> rows,boolean confirmed,java.util.function.BiConsumer<Status,String> listener){
        if(!confirmed)return false;
        // An already-sent signature is reconciled without obtaining new approval.
        try{
            if(rows.stream().allMatch(r->r.status()==Status.SIGNING)){
                for(var row:rows)workflow.refreshStatusWithoutSigning(shop,row);return true;
            }
            return workflow.signApprovedReviewed(shop,workflow.prepareApprovedSigning(shop,rows),listener);}catch(Exception e){throw new IllegalStateException(e);}
    }
}
