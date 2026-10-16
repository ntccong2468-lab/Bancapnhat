package com.vncode.app.features.kiz;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import java.time.Duration;
import java.util.*;
import java.util.function.IntConsumer;
import static com.vncode.app.features.kiz.KizWarehouseRepository.*;

public final class KizWarehouseService {
    private final KizWarehouseRepository repository=new KizWarehouseRepository();
    public Imported importFile(Shop shop,KizImportReader.Preview preview,boolean confirmed,String category)throws Exception {
        if(!confirmed)throw new IllegalArgumentException("KIZ_IMPORT_CONFIRMATION_REQUIRED");
        return ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            LicenseRuntime.require(Permission.KIZ);ListingIdentity.require(shop);
            return repository.commitToCategory(shop,preview,category);
        });
    }
    public Imported importFile(Shop shop,KizImportReader.Preview preview,boolean confirmed)throws Exception {
        if(!confirmed)throw new IllegalArgumentException("KIZ_IMPORT_CONFIRMATION_REQUIRED");
        return ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            LicenseRuntime.require(Permission.KIZ);ListingIdentity.require(shop);
            // A seller-provided category pool is local stock, not a Catalog observation.
            return repository.commit(shop,preview);
        });
    }
    /** Read-only True API verification, not introduction, order creation, or code purchase. */
    public int verify(Shop shop,IntConsumer progress)throws Exception {
        return ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            LicenseRuntime.require(Permission.KIZ);ListingIdentity.require(shop);var scope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(scope);
            var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
            var signer=ZnackSigningSession.guard(shop.getId(),settings.signerCertificate(),new CryptoProSignatureProvider(settings.cryptcpPath(),settings.signerCertificate(),Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds())));
            var api=new ZnackApiClient();var auth=new ZnackAuthService(api,signer);
            String token=auth.trueApiToken(settings);if(!scope.inn().equals(auth.resolvedParticipantInn(settings)))throw new SecurityException("KIZ_OWNER_MISMATCH");
            repository.prepareVerification(shop,scope.source(),scope.inn());
            long cursor=0;int ready=0,checked=0;
            while(checked<100000){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("KIZ_IMPORT_CANCELLED");
                ListingIdentity.require(shop);LicenseRuntime.require(Permission.KIZ);
                if(!scope.equals(RegisteredGtinProof.current(shop)))throw new SecurityException("KIZ_OWNER_CHANGED");
                var pending=repository.pending(shop,scope.source(),1000,cursor);if(pending.isEmpty())break;
                var body=new JsonArray();pending.forEach(item->body.add(item.cis()));
                var verdicts=inspect(pending,scope.inn(),api.cisesInfo(settings.resolvedTrueApiBaseUrl(),token,body));
                if(!scope.equals(RegisteredGtinProof.current(shop)))throw new SecurityException("KIZ_OWNER_CHANGED");
                repository.verified(shop,scope.source(),pending,verdicts);
                ready+=verdicts.values().stream().filter(Verdict::ready).count();checked+=pending.size();cursor=pending.getLast().id();progress.accept(checked);
            }
            return ready;
        });
    }
    static Map<String,Verdict> inspect(List<Pending> requested,String inn,JsonElement response){
        var wanted=new HashMap<String,Pending>();requested.forEach(item->wanted.put(item.cis(),item));
        var result=new HashMap<String,Verdict>();var seen=new HashSet<String>();
        if(response==null||!response.isJsonArray())throw new IllegalStateException("KIZ_VERIFY_RESPONSE_INVALID");
        for(var element:response.getAsJsonArray()){
            if(!element.isJsonObject())continue;var entry=element.getAsJsonObject();var info=entry.has("cisInfo")&&entry.get("cisInfo").isJsonObject()?entry.getAsJsonObject("cisInfo"):entry;
            String cis=text(info,"cis");if(cis.isBlank())cis=text(entry,"requestedCis");
            if(!wanted.containsKey(cis))continue;
            if(!seen.add(cis)){result.put(cis,new Verdict(false,"KIZ_VERIFY_DUPLICATE_RESPONSE"));continue;}
            var item=wanted.get(cis);String reason="KIZ_READY";
            if(!text(entry,"errorCode").isBlank()||!text(entry,"errorMessage").isBlank())reason="KIZ_VERIFY_LOOKUP_FAILED";
            else if(!inn.equals(text(info,"ownerInn")))reason="KIZ_OWNER_MISMATCH";
            else if(!RegisteredGtinProof.sameCode(item.gtin(),text(info,"gtin")))reason="KIZ_GTIN_MISMATCH";
            else if(!text(info,"status").equals("INTRODUCED")||!Set.of("","EMPTY").contains(text(info,"statusEx")))reason="KIZ_NOT_IN_CIRCULATION";
            result.put(cis,new Verdict(reason.equals("KIZ_READY"),reason));
        }
        wanted.keySet().forEach(cis->result.putIfAbsent(cis,new Verdict(false,"KIZ_VERIFY_INCOMPLETE")));return Map.copyOf(result);
    }
    private static String text(JsonObject o,String key){return o.has(key)&&o.get(key).isJsonPrimitive()?o.get(key).getAsString():"";}
}
