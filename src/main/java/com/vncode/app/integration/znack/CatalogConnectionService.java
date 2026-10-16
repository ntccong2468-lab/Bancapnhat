package com.vncode.app.integration.znack;

import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.registration.ZnackRegistrationScope;
import com.vncode.app.integration.znack.signature.CryptoProSignatureProvider;
import com.vncode.app.models.Shop;
import java.time.Duration;
import java.time.Instant;

/** Blocking connection checks: call on an app worker, never on the FX thread. */
public final class CatalogConnectionService {
    @FunctionalInterface interface RemoteCheck {
        void check(Shop shop, ZnackModels.Settings settings, RegisteredGtinProof.Scope scope, String key, Runnable guard) throws Exception;
    }
    private final CatalogCredentialRepository store;
    @FunctionalInterface interface KeyDiscovery {String discover(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope,Runnable guard)throws Exception;}
    private final RemoteCheck remote;
    private final KeyDiscovery discovery;
    public CatalogConnectionService() { this(new CatalogCredentialRepository(), CatalogConnectionService::checkRemote); }
    CatalogConnectionService(CatalogCredentialRepository store, RemoteCheck remote) { this(store,remote,CatalogConnectionService::discover); }
    CatalogConnectionService(CatalogCredentialRepository store,RemoteCheck remote,KeyDiscovery discovery){this.store=store;this.remote=remote;this.discovery=discovery;}
    public static ZnackApiClient apiForShop(Shop shop, ZnackModels.Settings settings) throws Exception {
        var store = new CatalogCredentialRepository();
        if (!store.hasSaved(shop.getId())) return new ZnackApiClient();
        var scope = RegisteredGtinProof.scope(shop, settings, Instant.now());
        var key = store.read(shop, scope).orElseThrow();
        var digest = ListingIdentity.hash(key);
        return ZnackApiClient.forCatalogKey(key, () -> {
            LicenseRuntime.require(Permission.SELLER_API);
            store.requireUnchanged(shop, scope, digest);
        });
    }
    /** Fresh installations and foreign-Windows backups obtain their own key through configured CryptoPro. */
    public static ZnackApiClient apiForRead(Shop shop,ZnackModels.Settings settings)throws Exception{return new CatalogConnectionService().connectForRead(shop,settings);}
    ZnackApiClient connectForRead(Shop shop,ZnackModels.Settings settings)throws Exception {
        LicenseRuntime.require(Permission.SELLER_API);
        var scope=RegisteredGtinProof.scope(shop,settings,Instant.now());RegisteredGtinProof.requireProduction(scope);
        var registration=ZnackRegistrationScope.fromSettings(settings);
        Runnable guard=()->{if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("ZNACK_SYNC_CANCELLED");LicenseRuntime.require(Permission.SELLER_API);registration.assertCurrent(shop);if(!scope.source().equals(RegisteredGtinProof.current(shop).source()))throw new IllegalStateException("CATALOG_KEY_SCOPE_CHANGED");};
        String key=null;
        if(store.hasSaved(shop.getId())) {
            try{key=store.read(shop,scope).orElseThrow();}
            catch(java.io.IOException unreadable){
                // Reauthenticate this Windows identity rather than sharing or bypassing DPAPI.
                guard.run();
                if("CATALOG_KEY_SCOPE_CHANGED".equals(unreadable.getMessage())||"CATALOG_KEY_STORAGE_FAILED".equals(unreadable.getMessage()))throw unreadable;
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_SYNC_CANCELLED");
            }
        }
        if(key==null){key=discovery.discover(shop,settings,scope,guard);guard.run();store.saveVerified(shop,scope,key,guard);}
        var digest=ListingIdentity.hash(key);guard.run();
        return ZnackApiClient.forCatalogKey(key,()->{guard.run();store.requireUnchanged(shop,scope,digest);});
    }
    private static String discover(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope,Runnable guard)throws Exception{
        var registration=ZnackRegistrationScope.fromSettings(settings);
        var provider=ZnackSigningSession.guardReadAuthentication(shop.getId(),scope.fingerprint(),new CryptoProSignatureProvider(settings.cryptcpPath(),scope.fingerprint(),Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds())));
        var signer=registration.verifyingSigner(shop,provider);
        var session=new NationalCatalogGs1Client().login(scope.inn(),scope.fingerprint(),signer);guard.run();
        String key=session.apiKey();guard.run();
        ZnackApiClient.forCatalogKey(key,guard).checkCatalogConnection(settings.resolvedTrueApiBaseUrl());guard.run();return key;
    }
    public void checkAndSave(Shop shop, String input, java.util.function.BooleanSupplier active) throws Exception {
        LicenseRuntime.require(Permission.SELLER_API);
        var settings = new ZnackRepository(new ZnackModels.ShopContext(shop.getId(), shop.getName())).getSettings();
        var scope = RegisteredGtinProof.scope(shop, settings, Instant.now());
        String key = input == null || input.isBlank() ? store.read(shop, scope).orElseThrow(() -> new java.io.IOException("CATALOG_KEY_REQUIRED"))
                : CatalogCredentialRepository.normalize(input);
        var registration = ZnackRegistrationScope.fromSettings(settings);
        Runnable guard = () -> {
            LicenseRuntime.require(Permission.SELLER_API);
            if (!active.getAsBoolean()) throw new IllegalStateException("CATALOG_KEY_SCOPE_CHANGED");
            registration.assertCurrent(shop);
            if (!scope.source().equals(RegisteredGtinProof.current(shop).source())) throw new IllegalStateException("CATALOG_KEY_SCOPE_CHANGED");
        };
        guard.run();
        RegisteredGtinProof.requireProduction(scope);
        remote.check(shop, settings, scope, key, guard);
        guard.run();
        store.saveVerified(shop, scope, key, guard);
    }
    private static void checkRemote(Shop shop, ZnackModels.Settings settings, RegisteredGtinProof.Scope scope, String key, Runnable guard) throws Exception {
        var registration = ZnackRegistrationScope.fromSettings(settings);
        var signer = registration.verifyingSigner(shop, new CryptoProSignatureProvider(settings.cryptcpPath(), scope.fingerprint(), Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds())));
        var session = new NationalCatalogGs1Client().login(scope.inn(), scope.fingerprint(), signer);
        guard.run();
        session.requireApiKey(key);
        guard.run();
        ZnackApiClient.forCatalogKey(key, guard).checkCatalogConnection(settings.resolvedTrueApiBaseUrl());
        guard.run();
    }
}
