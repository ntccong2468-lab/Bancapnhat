package com.vncode.app.integration.znack;

import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels.Settings;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppPaths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Durable background admission and configuration epochs for the shared purchase engine. */
public final class ZnackSigningSession {
    static final String WAITING_MESSAGE = "Waiting for the user to select this shop before signing.";
    private static final String WAITING_CAUSE_PREFIX = WAITING_MESSAGE + " [CRYPTOPRO_";
    private static final Set<Integer> AUTHORIZED_SHOPS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> READ_GRANTS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> BLOCKED_SHOPS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> RECONFIGURING = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Integer,CryptoProErrorCode> CAUSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,String> SHOP_CERTIFICATES = new ConcurrentHashMap<>();
    private static final Set<PipelineKey> AUTHORIZED_PIPELINES = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<PipelineKey,String> PIPELINE_CERTIFICATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,Object> PAUSE_GENERATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PipelineKey,Object> RESUMPTIONS = new ConcurrentHashMap<>();
    private static final Set<PipelineKey> WAITING = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<PipelineKey,CryptoProErrorCode> WAITING_CAUSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,AtomicLong> GENERATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,ReentrantReadWriteLock> GATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,java.util.concurrent.locks.ReentrantLock> SIGNING_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,ZnackSigningPauseStore> STORES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,ProfileScope> PROFILES = new ConcurrentHashMap<>();
    private static final ThreadLocal<PipelineContext> CURRENT = new ThreadLocal<>();
    private static volatile int foregroundShopId;
    private ZnackSigningSession() { }

    /** Production factories opt in; a bare/mock signer never opens a database. */
    public static void registerRepository(ZnackRepository repository) {
        int shop = repository.shop().shopId();
        profileScope(shop);
        if(gate(shop).getReadHoldCount()>0&&!gate(shop).isWriteLockedByCurrentThread()) {
            if(!STORES.containsKey(shop)) throw new IllegalStateException("ZNACK_SIGNING_SCOPE_NOT_REGISTERED");
            return;
        }
        var lock=gate(shop).writeLock(); lock.lock();
        try {
            var store=STORES.computeIfAbsent(shop,ZnackSigningPauseStore::new);
            var state=store.initialize(ZnackSigningPauseStore.identity(repository.getSettings()));
            if(state.reconfiguring()&&!RECONFIGURING.contains(shop)) state=store.finishReconfiguration(state.generation());
            AtomicLong previous=GENERATIONS.get(shop);
            if((previous==null&&state.generation()!=0)||(previous!=null&&previous.get()!=state.generation())) {
                revokeAll(shop); PAUSE_GENERATIONS.put(shop,new Object());
            }
            GENERATIONS.computeIfAbsent(shop,k->new AtomicLong()).set(state.generation());
            if(state.paused()) {
                BLOCKED_SHOPS.add(shop);
                if(state.cause()!=null) CAUSES.put(shop,state.cause()); else CAUSES.remove(shop);
            }
        } finally { lock.unlock(); }
    }

    public static boolean authorizeVerifiedShop(ZnackRepository repository,Settings settings) {
        Shop shop=new com.vncode.app.features.shop.ShopRepository().findById(repository.shop().shopId());
        return authorizeVerifiedShop(shop,settings,repository);
    }

    public static boolean authorizeVerifiedShop(Shop shop,Settings settings,ZnackRepository repository) {
        if(shop==null||shop.getId()!=repository.shop().shopId()
                ||(shop.getMarketplace()!=Marketplace.WILDBERRIES&&shop.getMarketplace()!=Marketplace.OZON)
                ||!verified(settings)) return false;
        registerRepository(repository);
        int id=shop.getId(); var lock=gate(id).readLock(); lock.lock();
        try {
            if(!ZnackSigningPauseStore.identity(settings).equals(ZnackSigningPauseStore.identity(repository.getSettings()))) return false;
            if(paused(id)||BLOCKED_SHOPS.contains(id)||RECONFIGURING.contains(id)) return false;
            AUTHORIZED_SHOPS.add(id); SHOP_CERTIFICATES.put(id,certificate(settings.signerCertificate()));
            return true;
        } finally { lock.unlock(); }
    }

    /** Explicit selection may clear a durable pause only for a reviewed saved certificate. */
    public static boolean authorizeShopExplicitly(ZnackRepository repository) {
        int id=repository.shop().shopId(); profileScope(id); var lock=gate(id).writeLock();
        if(!lock.tryLock()) throw new IllegalStateException("ZNACK_SIGNING_MUTATION_BUSY");
        try {
            registerRepository(repository);
            Settings settings=repository.getSettings(); if(!verified(settings)) return false;
            if(RECONFIGURING.contains(id)) return false;
            STORES.get(id).clearPause(); clearPauseInMemory(id);
            AUTHORIZED_SHOPS.add(id); SHOP_CERTIFICATES.put(id,certificate(settings.signerCertificate()));
            return true;
        } finally { lock.unlock(); }
    }

    public static void setForegroundShop(int id) { foregroundShopId=id; }

    public static ZnackSignatureProvider guard(int id,ZnackSignatureProvider delegate) { return guard(id,null,delegate); }
    public static ZnackSignatureProvider guard(int id,String fingerprint,ZnackSignatureProvider delegate) {
        Objects.requireNonNull(delegate,"Signature provider is required.");
        ProfileScope profile=profileScope(id);
        long generation=signerGenerationSnapshot(id);
        return new ZnackSignatureProvider() {
            @Override public CryptoProSigningResult sign(byte[] payload,ZnackSignatureContext context)throws CryptoProException {
                return guarded(id,fingerprint,generation,profile,false,delegate::requireKeyConnected,()->delegate.sign(payload,context));
            }
            @Override public void requireKeyConnected()throws CryptoProException { delegate.requireKeyConnected(); }
        };
    }

    /** GS1 authentication cannot grant permission to sign a purchase or business document. */
    public static ZnackSignatureProvider guardReadAuthentication(int id,String fingerprint,ZnackSignatureProvider delegate) {
        Objects.requireNonNull(delegate,"Signature provider is required.");
        ProfileScope profile=profileScope(id);
        long generation=signerGenerationSnapshot(id);
        return (payload,context)-> {
            if(context!=ZnackSignatureContext.AUTH_CHALLENGE&&context!=ZnackSignatureContext.CATALOG_AUTH_CHALLENGE)
                throw new CryptoProException(CryptoProErrorCode.INVALID_SIGNATURE_OUTPUT,"Only authentication challenges are allowed.");
            return guarded(id,fingerprint,generation,profile,true,delegate::requireKeyConnected,()->delegate.sign(payload,context));
        };
    }

    public static XmlSignatureProvider guardXml(int id,String fingerprint,XmlSignatureProvider delegate) {
        Objects.requireNonNull(delegate,"XML signer is required.");
        ProfileScope profile=profileScope(id);
        long generation=signerGenerationSnapshot(id);
        return xml->guarded(id,fingerprint,generation,profile,false,()->{},()->delegate.signXml(xml));
    }

    @FunctionalInterface private interface Probe { void require()throws CryptoProException; }
    private static <T>T guarded(int id,String fingerprint,long generation,ProfileScope profile,boolean authentication,Probe probe,
                               CertificateSigningQueue.Work<T> work)throws CryptoProException {
        PipelineContext context=CURRENT.get();
        PipelineKey key=context!=null&&context.key().shopId()==id?context.key():null;
        if(key!=null&&(context.generation()!=generation||context.profile()!=profile)) throw new SigningDeferredException(null,true,null);
        admit(id,fingerprint,generation,profile,authentication,key);
        return CertificateSigningQueue.run(fingerprint,()-> {
            var signing=SIGNING_LOCKS.computeIfAbsent(id,k->new java.util.concurrent.locks.ReentrantLock(true));
            try { signing.lockInterruptibly(); }
            catch(InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Signing queue was interrupted.",cancelled);
            }
            var lock=gate(id).readLock(); lock.lock();
            try {
                admit(id,fingerprint,generation,profile,authentication,key);
                try {
                    if(id!=foregroundShopId||(context!=null&&context.automatic())) probe.require();
                    T result=work.run();
                    admit(id,fingerprint,generation,profile,authentication,key);
                    return result;
                } catch(SigningDeferredException deferred) { throw deferred;
                } catch(CryptoProException error) {
                    if(error.code()==CryptoProErrorCode.KEY_NOT_CONNECTED) {
                        if(key!=null) { WAITING.add(key); WAITING_CAUSES.put(key,error.code()); }
                        throw new SigningDeferredException(error.code(),false,error);
                    }
                    pauseFailure(id,key,error); throw error;
                }
            } finally { lock.unlock(); signing.unlock(); }
        });
    }

    private static void admit(int id,String fingerprint,long generation,ProfileScope profile,boolean authentication,PipelineKey key)
            throws SigningDeferredException {
        if(profile!=PROFILES.get(id)||!profile.path().equals(currentProfile())
                ||generation!=signerGenerationSnapshot(id)||RECONFIGURING.contains(id))
            throw new SigningDeferredException(null,true,null);
        boolean matching=key!=null&&key.shopId()==id;
        boolean scoped=matching&&RESUMPTIONS.get(key)!=null&&RESUMPTIONS.get(key)==PAUSE_GENERATIONS.get(id);
        boolean readRetry=authentication&&READ_GRANTS.contains(id);
        boolean shopGrant=AUTHORIZED_SHOPS.contains(id)&&matches(SHOP_CERTIFICATES.get(id),fingerprint);
        boolean pipelineGrant=matching&&AUTHORIZED_PIPELINES.contains(key)&&matches(PIPELINE_CERTIFICATES.get(key),fingerprint);
        if(((BLOCKED_SHOPS.contains(id)||paused(id))&&!scoped&&!readRetry)
                ||(matching&&WAITING.contains(key))||(!authentication&&!shopGrant&&!pipelineGrant)) {
            if(matching) WAITING.add(key);
            CryptoProErrorCode cause=matching?WAITING_CAUSES.get(key):null;
            throw new SigningDeferredException(cause==null?CAUSES.get(id):cause,false,null);
        }
    }

    private static void pauseFailure(int id,PipelineKey key,CryptoProException error) {
        PAUSE_GENERATIONS.put(id,new Object()); BLOCKED_SHOPS.add(id); READ_GRANTS.remove(id);
        if(error.code()==null) CAUSES.remove(id); else CAUSES.put(id,error.code());
        if(key!=null) WAITING.add(key);
        var store=STORES.get(id);
        if(store!=null) {
            RuntimeException failure=null;
            for(int attempt=0;attempt<3;attempt++) {
                try { store.pause(error.code()); return; } catch(RuntimeException unavailable) { failure=unavailable; }
            }
            error.addSuppressed(new IllegalStateException("ZNACK_SIGNING_PAUSE_NOT_PERSISTED",failure));
        }
    }

    /** Compatibility consent for callers without a saved production signing store. */
    public static void authorizePipeline(int id,long pipeline) {
        profileScope(id);
        RESUMPTIONS.keySet().removeIf(k->k.shopId()==id); CAUSES.remove(id); BLOCKED_SHOPS.remove(id);
        var key=new PipelineKey(id,pipeline);
        AUTHORIZED_PIPELINES.add(key); PIPELINE_CERTIFICATES.remove(key); clearWaiting(key);
    }
    public static void authorizePipeline(int id,long pipeline,Settings settings) {
        profileScope(id);
        if(paused(id)||BLOCKED_SHOPS.contains(id)) authorizePipelineResumption(id,pipeline,settings);
        else {
            authorizePipeline(id,pipeline);
            PIPELINE_CERTIFICATES.put(new PipelineKey(id,pipeline),certificate(settings.signerCertificate()));
        }
    }
    public static void authorizePipelineResumption(int id,long pipeline) {
        profileScope(id);
        var key=new PipelineKey(id,pipeline);
        Object generation=PAUSE_GENERATIONS.computeIfAbsent(id,k->new Object());
        AUTHORIZED_PIPELINES.add(key); PIPELINE_CERTIFICATES.remove(key); RESUMPTIONS.put(key,generation); clearWaiting(key);
    }
    public static void authorizePipelineResumption(int id,long pipeline,Settings settings) {
        authorizePipelineResumption(id,pipeline);
        PIPELINE_CERTIFICATES.put(new PipelineKey(id,pipeline),certificate(settings.signerCertificate()));
    }
    public static void authorizeShop(int id) {
        profileScope(id);
        clearPauseInMemory(id); AUTHORIZED_SHOPS.add(id); READ_GRANTS.add(id); SHOP_CERTIFICATES.remove(id);
    }
    private static void clearPauseInMemory(int id) {
        RESUMPTIONS.keySet().removeIf(k->k.shopId()==id); CAUSES.remove(id); BLOCKED_SHOPS.remove(id);
        WAITING.removeIf(k->k.shopId()==id); WAITING_CAUSES.keySet().removeIf(k->k.shopId()==id);
    }
    private static void clearWaiting(PipelineKey key) { WAITING.remove(key); WAITING_CAUSES.remove(key); }
    public static boolean isWaitingForSignature(int id,long pipeline) { return WAITING.contains(new PipelineKey(id,pipeline)); }
    public static String signingWaitMessage(int id) { return deferredMessage(CAUSES.get(id)); }
    private static String deferredMessage(CryptoProErrorCode cause) {
        return WAITING_MESSAGE+(cause==null?"":" [CRYPTOPRO_"+cause.name()+"]");
    }
    public static boolean isSigningDeferred(String raw) {
        if(WAITING_MESSAGE.equals(raw)) return true;
        if(raw==null||!raw.startsWith(WAITING_CAUSE_PREFIX)||!raw.endsWith("]")) return false;
        try { CryptoProErrorCode.valueOf(raw.substring(WAITING_CAUSE_PREFIX.length(),raw.length()-1)); return true; }
        catch(IllegalArgumentException invalid) { return false; }
    }
    public static boolean isTransientKeyWait(String raw) { return deferredMessage(CryptoProErrorCode.KEY_NOT_CONNECTED).equals(raw); }
    public static boolean retryDisconnectedKey(int id,long pipeline) {
        return retryDisconnectedKey(id,pipeline,null);
    }
    /** Restores a persisted transient key wait after restart; never grants signing or clears a durable pause. */
    public static boolean retryDisconnectedKey(int id,long pipeline,String persistedWaitMessage) {
        profileScope(id);
        var key=new PipelineKey(id,pipeline);
        CryptoProErrorCode cause=WAITING_CAUSES.get(key);
        boolean transientWait=cause==CryptoProErrorCode.KEY_NOT_CONNECTED
                ||(cause==null&&isTransientKeyWait(persistedWaitMessage));
        if(!transientWait||BLOCKED_SHOPS.contains(id)||paused(id)||RECONFIGURING.contains(id))
            return false;
        clearWaiting(key); return true;
    }

    public static long signerGenerationSnapshot(int id) {
        profileScope(id);
        var store=STORES.get(id); return store==null?GENERATIONS.computeIfAbsent(id,k->new AtomicLong()).get():store.read().generation();
    }
    @FunctionalInterface public interface MutationWork<T> { T run()throws Exception; }
    public static MutationPermit captureMutationPermit(int id,Settings original)throws SigningDeferredException {
        Objects.requireNonNull(original,"Original purchase settings are required.");
        ProfileScope profile=profileScope(id);
        PipelineContext context=CURRENT.get();
        PipelineKey key=context!=null&&context.key().shopId()==id?context.key():null;
        if(key!=null&&context.profile()!=profile) throw new SigningDeferredException(null,true,null);
        long generation=key==null?signerGenerationSnapshot(id):context.generation();
        String fingerprint=certificate(original.signerCertificate());
        admit(id,fingerprint,generation,profile,false,key);
        return new MutationPermit(id,generation,key,fingerprint,profile);
    }
    public static final class MutationPermit {
        private final int id; private final long generation; private final PipelineKey key; private final String fingerprint;
        private final ProfileScope profile;
        private MutationPermit(int id,long generation,PipelineKey key,String fingerprint,ProfileScope profile) {
            this.id=id;this.generation=generation;this.key=key;this.fingerprint=fingerprint;this.profile=profile;
        }
        public void requireCurrent()throws SigningDeferredException { admit(id,fingerprint,generation,profile,false,key); }
        public <T>T execute(MutationWork<T> work)throws Exception {
            Objects.requireNonNull(work,"Mutation is required."); var lock=gate(id).readLock(); lock.lock();
            try { requireCurrent(); return work.run(); } finally { lock.unlock(); }
        }
    }
    public static <T>T executePaidMutation(int id,long expectedGeneration,MutationWork<T> work)throws Exception {
        ProfileScope profile=profileScope(id);
        var lock=gate(id).readLock();lock.lock();
        try {
            PipelineContext context=CURRENT.get();
            PipelineKey key=context!=null&&context.key().shopId()==id?context.key():null;
            if(key!=null&&context.profile()!=profile) throw new SigningDeferredException(null,true,null);
            String approved=key==null?SHOP_CERTIFICATES.get(id):PIPELINE_CERTIFICATES.get(key);
            if(approved==null)approved=SHOP_CERTIFICATES.get(id);
            admit(id,approved,expectedGeneration,profile,false,key);
            return work.run();
        } finally { lock.unlock(); }
    }

    /** Call before entering the settings SQL transaction; active mutations reject a change promptly. */
    public static ReconfigurationScope beginReconfiguration(int id,Settings previous,Settings next) {
        profileScope(id);
        if(ZnackSigningPauseStore.identity(previous).equals(ZnackSigningPauseStore.identity(next)))
            return new ReconfigurationScope(id,null,-1,false);
        var lock=gate(id).writeLock();
        if(!lock.tryLock()) throw new IllegalStateException("ZNACK_SIGNING_MUTATION_BUSY");
        try {
            RECONFIGURING.add(id); revokeAll(id);
            var store=STORES.computeIfAbsent(id,ZnackSigningPauseStore::new);
            var before=store.read();
            var state=store.beginReconfiguration(ZnackSigningPauseStore.identity(next));
            GENERATIONS.computeIfAbsent(id,k->new AtomicLong()).set(state.generation());
            BLOCKED_SHOPS.add(id); PAUSE_GENERATIONS.put(id,new Object());
            boolean initial=(previous.signerCertificate()==null||previous.signerCertificate().isBlank())&&!before.paused();
            return new ReconfigurationScope(id,lock,state.generation(),initial);
        } catch(RuntimeException error) { RECONFIGURING.remove(id);lock.unlock();throw error; }
    }
    public static final class ReconfigurationScope implements AutoCloseable {
        private final int id;private final java.util.concurrent.locks.Lock lock;private final long generation;private final boolean initial;
        private boolean closed;
        private ReconfigurationScope(int id,java.util.concurrent.locks.Lock lock,long generation,boolean initial) {
            this.id=id;this.lock=lock;this.generation=generation;this.initial=initial;
        }
        @Override public void close() {
            if(closed) return;closed=true;if(lock==null) return;
            try {
                var state=STORES.get(id).finishReconfiguration(generation);
                if(initial&&state.generation()==generation) { STORES.get(id).clearPause();BLOCKED_SHOPS.remove(id); }
            } finally { RECONFIGURING.remove(id);lock.unlock(); }
        }
    }
    private static void revokeAll(int id) {
        AUTHORIZED_SHOPS.remove(id);READ_GRANTS.remove(id);SHOP_CERTIFICATES.remove(id);
        AUTHORIZED_PIPELINES.removeIf(k->k.shopId()==id);PIPELINE_CERTIFICATES.keySet().removeIf(k->k.shopId()==id);
        RESUMPTIONS.keySet().removeIf(k->k.shopId()==id);
    }
    private static boolean paused(int id) { var store=STORES.get(id);return store!=null&&store.read().paused(); }
    private static boolean verified(Settings s) { return s!=null&&s.signerCertificate()!=null&&!s.signerCertificate().isBlank()&&s.signerTestedAt()!=null; }
    private static boolean matches(String approved,String actual) { return approved==null||approved.isEmpty()||approved.equals(certificate(actual)); }
    private static String certificate(String s) { return s==null?"":s.replaceAll("\\s+","").toUpperCase(Locale.ROOT); }
    private static ReentrantReadWriteLock gate(int id) { return GATES.computeIfAbsent(id,k->new ReentrantReadWriteLock(true)); }
    private static java.nio.file.Path currentProfile() { return AppPaths.appDataDir().toAbsolutePath().normalize(); }

    /** A new profile receives no old grants. Captured signers/permits retain their old epoch forever. */
    private static ProfileScope profileScope(int id) {
        var path=currentProfile();var before=PROFILES.get(id);
        if(before!=null&&before.path().equals(path))return before;
        var lock=gate(id).writeLock();
        if(!lock.tryLock())throw new IllegalStateException("ZNACK_SIGNING_MUTATION_BUSY");
        try {
            before=PROFILES.get(id);
            if(before!=null&&before.path().equals(path))return before;
            if(before!=null) {
                revokeAll(id);clearPauseInMemory(id);STORES.remove(id);GENERATIONS.remove(id);PAUSE_GENERATIONS.remove(id);
            }
            var next=new ProfileScope(path);PROFILES.put(id,next);return next;
        }finally{lock.unlock();}
    }
    static PipelineScope openPipeline(int id,long pipeline) { return openPipeline(id,pipeline,false); }
    static PipelineScope openPipeline(int id,long pipeline,boolean automatic) {
        ProfileScope profile=profileScope(id);
        var previous=CURRENT.get();CURRENT.set(new PipelineContext(new PipelineKey(id,pipeline),signerGenerationSnapshot(id),automatic,profile));
        return new PipelineScope(previous);
    }
    static void resetForTests() {
        AUTHORIZED_SHOPS.clear();READ_GRANTS.clear();BLOCKED_SHOPS.clear();RECONFIGURING.clear();CAUSES.clear();
        SHOP_CERTIFICATES.clear();AUTHORIZED_PIPELINES.clear();PIPELINE_CERTIFICATES.clear();PAUSE_GENERATIONS.clear();
        RESUMPTIONS.clear();WAITING.clear();WAITING_CAUSES.clear();GENERATIONS.clear();STORES.clear();PROFILES.clear();SIGNING_LOCKS.clear();CURRENT.remove();foregroundShopId=0;
    }
    private record PipelineKey(int shopId,long pipelineId) { }
    private record PipelineContext(PipelineKey key,long generation,boolean automatic,ProfileScope profile) { }
    private static final class ProfileScope {
        private final java.nio.file.Path path;
        private ProfileScope(java.nio.file.Path path){this.path=path;}
        private java.nio.file.Path path(){return path;}
    }
    static final class PipelineScope implements AutoCloseable {
        private final PipelineContext previous;private boolean closed;
        private PipelineScope(PipelineContext previous) { this.previous=previous; }
        @Override public void close() { if(closed)return;closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous); }
    }
    public static final class SigningDeferredException extends CryptoProException {
        private final boolean freshSignerRequired;
        private SigningDeferredException(CryptoProErrorCode cause,boolean freshSignerRequired,Throwable error) {
            super(CryptoProErrorCode.CANCELLED,deferredMessage(cause),error);this.freshSignerRequired=freshSignerRequired;
        }
        public boolean requiresFreshSigner() { return freshSignerRequired; }
    }
}
