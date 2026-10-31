package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.sync.SyncFailure;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonApiClient;
import com.vncode.app.integration.ozon.OzonCredentials;
import com.vncode.app.integration.wb.WbApiClient;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Explicit read-only connection review. Never writes credentials or submits product documents. */
public final class RegistrationConnectionDiagnostics {
    public enum Step { MARKETPLACE, CERTIFICATE, SIGNATURE, AUTHENTICATION, ENTERPRISE, CATALOG }
    public enum State { WAITING, CHECKING, VERIFIED, FAILED, UNVERIFIED }
    public record Event(Step step, State state, String code) {
        public Event { Objects.requireNonNull(step); Objects.requireNonNull(state); code=code==null?"":code; }
    }

    interface Checker {
        void marketplace() throws Exception;
        void certificate() throws Exception;
        void authenticate(Runnable signed) throws Exception;
        void enterprise() throws Exception;
        void catalog() throws Exception;
    }

    public static void check(Shop selected, Consumer<Event> listener) throws Exception {
        Objects.requireNonNull(selected);Objects.requireNonNull(listener);
        Shop shop=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        check(new ProductionChecker(shop),listener);
    }

    static void check(Checker checker,Consumer<Event> listener)throws Exception {
        for(var step:Step.values())listener.accept(new Event(step,State.WAITING,""));
        run(Step.MARKETPLACE,checker::marketplace,listener);
        if(!run(Step.CERTIFICATE,checker::certificate,listener)) {
            for(var step:List.of(Step.SIGNATURE,Step.AUTHENTICATION,Step.ENTERPRISE,Step.CATALOG))emit(listener,step,State.UNVERIFIED,"");
            return;
        }
        emit(listener,Step.SIGNATURE,State.CHECKING,"");emit(listener,Step.AUTHENTICATION,State.CHECKING,"");
        boolean[] signed={false};
        try {
            checker.authenticate(()->signed[0]=true);
            if(!signed[0]) {emit(listener,Step.SIGNATURE,State.UNVERIFIED,"");emit(listener,Step.AUTHENTICATION,State.UNVERIFIED,"");emit(listener,Step.ENTERPRISE,State.UNVERIFIED,"");emit(listener,Step.CATALOG,State.UNVERIFIED,"");return;}
            emit(listener,Step.SIGNATURE,State.VERIFIED,"");
            emit(listener,Step.AUTHENTICATION,State.VERIFIED,"");
        } catch(Exception error) {
            interrupt(error);
            emit(listener,Step.SIGNATURE,!signed[0]&&hasCryptoFailure(error)?State.FAILED:State.UNVERIFIED,!signed[0]&&hasCryptoFailure(error)?safeCode(error):"");
            emit(listener,Step.AUTHENTICATION,State.FAILED,safeCode(error));
            emit(listener,Step.ENTERPRISE,State.UNVERIFIED,"");emit(listener,Step.CATALOG,State.UNVERIFIED,"");return;
        }
        if(!run(Step.ENTERPRISE,checker::enterprise,listener)){emit(listener,Step.CATALOG,State.UNVERIFIED,"");return;}
        run(Step.CATALOG,checker::catalog,listener);
    }

    private interface Action {void run()throws Exception;}
    private static boolean run(Step step,Action action,Consumer<Event> listener)throws Exception{
        emit(listener,step,State.CHECKING,"");
        try{action.run();emit(listener,step,State.VERIFIED,"");return true;}
        catch(Exception error){interrupt(error);emit(listener,step,State.FAILED,safeCode(error));return false;}
    }
    private static void emit(Consumer<Event> listener,Step step,State state,String code){listener.accept(new Event(step,state,code));}
    private static void interrupt(Exception error)throws Exception{
        if(Thread.currentThread().isInterrupted() || error instanceof InterruptedException || error instanceof InterruptedIOException&&!(error instanceof java.net.SocketTimeoutException))throw error;
    }
    private static boolean hasCryptoFailure(Throwable error){for(var e=error;e!=null;e=e.getCause())if(e instanceof CryptoProException)return true;return false;}
    private static String safeCode(Throwable error){
        for(Throwable e=error;e!=null;e=e.getCause())if(e instanceof ZnackRegistrationScope.ScopeChangedException)return "CATALOG_CERTIFICATE_SCOPE_CHANGED";
        String code=SyncFailure.of(error,0).code();
        for(var candidate:CryptoProErrorCode.values())if(code.equals("CRYPTOPRO_"+candidate.name()))return code;
        if(code.matches("WB_HTTP_[0-9]{3}")
                ||Set.of("WB_AUTH_REQUIRED","WB_RATE_LIMIT","WB_RESPONSE_INVALID","OZON_AUTH_REQUIRED","OZON_RATE_LIMIT","OZON_RESPONSE_INVALID",
                "ZNACK_AUTH_REQUIRED","ZNACK_RATE_LIMIT","NETWORK_TIMEOUT","NETWORK_DNS","NETWORK_OR_RESPONSE","LICENSE_REQUIRED",
                "CATALOG_OWNER_UNVERIFIED","CATALOG_KEY_OWNER_MISMATCH","CATALOG_KEY_SCOPE_CHANGED","CATALOG_KEY_STORAGE_FAILED",
                "CATALOG_KEY_INVALID","CATALOG_KEY_RESPONSE_INVALID","API_COOLDOWN","ZNACK_SYNC_CANCELLED").contains(code))return code;
        return "CONNECTION_CHECK_FAILED";
    }

    private static final class ProductionChecker implements Checker {
        private final Shop shop;private ZnackModels.Settings settings;private ZnackRegistrationScope scope;
        private RegisteredGtinProof.Scope proof;private NationalCatalogGs1Client.Session session;
        private ProductionChecker(Shop shop){this.shop=shop;}
        private void guard(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("ZNACK_SYNC_CANCELLED");LicenseRuntime.require(Permission.SELLER_API);ListingIdentity.require(shop);if(scope!=null)scope.assertCurrent(shop);}
        @Override public void marketplace()throws Exception{
            guard();
            if(shop.getMarketplace()==Marketplace.WILDBERRIES){var result=new WbApiClient().getProductCards(shop.getApiKey(),"ru",null,null,1);if(result==null||result.getCards()==null||result.getCards().size()>1||result.getCursor()==null||result.getCursor().getTotal()==null||result.getCursor().getTotal()<0)throw new IllegalStateException("WB_RESPONSE_INVALID");}
            else if(shop.getMarketplace()==Marketplace.OZON){var credentials=new OzonCredentials(shop.getClientId(),shop.getApiKey());var result=new OzonApiClient(shop.getId(),credentials).listProducts("",1);if(result==null||!result.has("result")||!result.get("result").isJsonObject()||!result.getAsJsonObject("result").has("items")||!result.getAsJsonObject("result").get("items").isJsonArray())throw new IllegalStateException("OZON_RESPONSE_INVALID");}
            else throw new IllegalArgumentException("MARKETPLACE_UNSUPPORTED");
            guard();
        }
        @Override public void certificate(){
            guard();settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
            scope=ZnackRegistrationScope.fromSettings(settings);proof=RegisteredGtinProof.scope(shop,settings,Instant.now());RegisteredGtinProof.requireProduction(proof);guard();
        }
        @Override public void authenticate(Runnable signed)throws Exception{
            guard();var signer=scope.verifyingSigner(shop,new CryptoProSignatureProvider(settings.cryptcpPath(),scope.fingerprint(),Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds())));
            ZnackSignatureProvider observed=(payload,context)->{var result=signer.sign(payload,context);guard();signed.run();return result;};
            session=new NationalCatalogGs1Client().login(scope.inn(),scope.fingerprint(),observed,this::guard);guard();
        }
        @Override public void enterprise()throws Exception{
            guard();var account=session.accountSnapshot();guard();
            // An unopened application manufactures an INN locally; it is not owner proof.
            if(account.newApplication()||!scope.inn().equals(account.membership().inn()))throw new IllegalStateException("CATALOG_OWNER_UNVERIFIED");
        }
        @Override public void catalog()throws Exception{
            guard();String key=session.apiKey();guard();
            var saved=new CatalogCredentialRepository().read(shop,proof);
            if(saved.isPresent()&&!java.security.MessageDigest.isEqual(key.getBytes(java.nio.charset.StandardCharsets.UTF_8),saved.get().getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new IllegalStateException("CATALOG_KEY_OWNER_MISMATCH");
            guard();ZnackApiClient.forCatalogKey(key,this::guard).checkCatalogConnection(settings.resolvedTrueApiBaseUrl());guard();
        }
    }
    private RegistrationConnectionDiagnostics(){}
}
