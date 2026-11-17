package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels.Settings;
import com.vncode.app.integration.znack.ZnackModels.ShopContext;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Persisted admission and synthetic signers only: no native process, account token or remote write. */
class Wb1326SigningAdmissionTest {
    private static final int SHOP=7132601, OTHER=7132602, UNTESTED=7132603;
    private static final String CERT="A".repeat(40), NEXT="C".repeat(40);
    @TempDir Path profile;
    private ZnackRepository repository;
    private Settings original;

    @BeforeEach void setup() throws Exception {
        ZnackSigningSession.resetForTests();
        System.setProperty("vncode.appdata.dir",profile.toString());
        Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key) VALUES("+SHOP+",'Synthetic A','fixture'),("+OTHER+",'Synthetic B','fixture'),("+UNTESTED+",'Synthetic C','fixture')");
        }
        repository=repository(SHOP);
        original=settings(CERT,true);
        repository.saveSettings(original);
        ZnackSigningSession.registerRepository(repository);
    }
    @AfterEach void cleanup() {
        ZnackSigningSession.resetForTests();
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void verifiedUnpausedWbAndOzonRecoverWithoutForegroundSelection() throws Exception {
        var other=repository(OTHER);var settings=settings("B".repeat(40),true);other.saveSettings(settings);
        assertTrue(ZnackSigningSession.authorizeVerifiedShop(new Shop(SHOP,"A",Marketplace.WILDBERRIES,null,"fixture"),original,repository));
        assertTrue(ZnackSigningSession.authorizeVerifiedShop(new Shop(OTHER,"B",Marketplace.OZON,"fixture-client","fixture"),settings,other));
        var calls=new AtomicInteger();
        ZnackSigningSession.guard(SHOP,CERT,success(calls)).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE);
        ZnackSigningSession.guard(OTHER,settings.signerCertificate(),success(calls)).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE);
        assertEquals(2,calls.get());
        assertFalse(ZnackSigningSession.authorizeVerifiedShop(new Shop(UNTESTED,"C","fixture"),settings(CERT,false),repository(UNTESTED)));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->ZnackSigningSession.guard(UNTESTED,success(calls))
                .sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        assertEquals(2,calls.get());
    }

    @Test void cancellationSurvivesRestartAndStartupCannotClearIt() throws Exception {
        assertTrue(ZnackSigningSession.authorizeVerifiedShop(repository,original));
        var failure=ZnackSigningSession.guard(SHOP,CERT,(p,c)->{throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Synthetic cancellation");});
        assertThrows(CryptoProException.class,()->failure.sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        assertTrue(new ZnackSigningPauseStore(SHOP).read().paused());
        ZnackSigningSession.resetForTests();
        assertFalse(ZnackSigningSession.authorizeVerifiedShop(repository,repository.getSettings()));
        var calls=new AtomicInteger();
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->ZnackSigningSession.guard(SHOP,CERT,success(calls))
                .sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        assertEquals(0,calls.get());
        assertTrue(ZnackSigningSession.authorizeShopExplicitly(repository));
        ZnackSigningSession.guard(SHOP,CERT,success(calls)).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE);
        assertEquals(1,calls.get());
        assertFalse(new ZnackSigningPauseStore(SHOP).read().paused());
    }

    @Test void verifiedAdmissionCannotUseAnotherShopsRepository() {
        assertFalse(ZnackSigningSession.authorizeVerifiedShop(new Shop(OTHER,"Synthetic other","fixture"),original,repository));
        var calls=new AtomicInteger();
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->ZnackSigningSession.guard(OTHER,CERT,success(calls))
                .sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        assertEquals(0,calls.get());
    }

    @Test void disconnectedBackgroundKeyDefersWithoutSigningOrDurableCancellation() throws Exception {
        var connected=new AtomicBoolean();var calls=new AtomicInteger();var probes=new AtomicInteger();
        ZnackSignatureProvider fake=new ZnackSignatureProvider() {
            @Override public void requireKeyConnected()throws CryptoProException {
                probes.incrementAndGet();if(!connected.get())throw new CryptoProException(CryptoProErrorCode.KEY_NOT_CONNECTED,"Synthetic missing key");
            }
            @Override public CryptoProSigningResult sign(byte[] p,ZnackSignatureContext c) { calls.incrementAndGet();return new CryptoProSigningResult(new byte[]{1},"fixture"); }
        };
        ZnackSigningSession.authorizePipeline(SHOP,11,original);
        var guard=ZnackSigningSession.guard(SHOP,CERT,fake);
        try(var ignored=ZnackSigningSession.openPipeline(SHOP,11,true)) {
            var failure=assertThrows(ZnackSigningSession.SigningDeferredException.class,()->guard.sign(new byte[]{1},ZnackSignatureContext.SUZ_POST_BODY));
            assertTrue(ZnackSigningSession.isTransientKeyWait(failure.getMessage()));
        }
        assertEquals(0,calls.get());assertEquals(1,probes.get());
        assertFalse(new ZnackSigningPauseStore(SHOP).read().paused());
        connected.set(true);assertTrue(ZnackSigningSession.retryDisconnectedKey(SHOP,11));
        try(var ignored=ZnackSigningSession.openPipeline(SHOP,11,true)) { guard.sign(new byte[]{1},ZnackSignatureContext.SUZ_POST_BODY); }
        assertEquals(1,calls.get());assertEquals(2,probes.get());
    }

    @Test void changedConfigurationRejectsCapturedMutationAndOldSigner() throws Exception {
        ZnackSigningSession.authorizeShopExplicitly(repository);
        var calls=new AtomicInteger();var guard=ZnackSigningSession.guard(SHOP,CERT,success(calls));
        var permit=ZnackSigningSession.captureMutationPermit(SHOP,original);
        Settings next=settings(NEXT,true);
        try(var ignored=ZnackSigningSession.beginReconfiguration(SHOP,original,next)) { repository.saveSettings(next); }
        var posts=new AtomicInteger();
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->permit.execute(posts::incrementAndGet));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->guard.sign(new byte[]{1},ZnackSignatureContext.SUZ_POST_BODY));
        assertEquals(0,posts.get());assertEquals(0,calls.get());
        ZnackSigningSession.authorizeShopExplicitly(repository);
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->ZnackSigningSession.guard(SHOP,CERT,success(calls))
                .sign(new byte[]{1},ZnackSignatureContext.SUZ_POST_BODY));
        ZnackSigningSession.guard(SHOP,NEXT,success(calls)).sign(new byte[]{1},ZnackSignatureContext.SUZ_POST_BODY);
        assertEquals(1,calls.get());
    }

    @Test void persistedDisconnectedKeyWaitCanRetryAfterRestartWithoutClearingCancellation() throws Exception {
        assertTrue(ZnackSigningSession.authorizeVerifiedShop(repository,original));
        var disconnected=ZnackSigningSession.guard(SHOP,CERT,new ZnackSignatureProvider() {
            @Override public void requireKeyConnected()throws CryptoProException {
                throw new CryptoProException(CryptoProErrorCode.KEY_NOT_CONNECTED,"Synthetic absent key");
            }
            @Override public CryptoProSigningResult sign(byte[] p,ZnackSignatureContext c) {
                fail("Disconnected key must not sign");return null;
            }
        });
        String persisted;
        try(var ignored=ZnackSigningSession.openPipeline(SHOP,12,true)) {
            persisted=assertThrows(ZnackSigningSession.SigningDeferredException.class,
                    ()->disconnected.sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE)).getMessage();
        }
        ZnackSigningSession.resetForTests();
        assertTrue(ZnackSigningSession.authorizeVerifiedShop(repository,original));
        assertTrue(ZnackSigningSession.retryDisconnectedKey(SHOP,12,persisted));
        var calls=new AtomicInteger();
        try(var ignored=ZnackSigningSession.openPipeline(SHOP,12,true)) {
            ZnackSigningSession.guard(SHOP,CERT,success(calls)).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE);
        }
        assertEquals(1,calls.get());
        assertThrows(CryptoProException.class,()->ZnackSigningSession.guard(SHOP,CERT,(p,c)->{
            throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Synthetic cancellation");
        }).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        ZnackSigningSession.resetForTests();
        assertFalse(ZnackSigningSession.authorizeVerifiedShop(repository,original));
        assertFalse(ZnackSigningSession.retryDisconnectedKey(SHOP,12,persisted));
        assertTrue(new ZnackSigningPauseStore(SHOP).read().paused());
    }

    @Test void scopedOriginalProfileResumeDoesNotUnpauseItsSibling() throws Exception {
        ZnackSigningSession.authorizePipeline(SHOP,21,original);
        ZnackSigningSession.authorizePipeline(SHOP,22,original);
        try(var ignored=ZnackSigningSession.openPipeline(SHOP,21)) {
            assertThrows(CryptoProException.class,()->ZnackSigningSession.guard(SHOP,CERT,(p,c)->{
                throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Synthetic cancellation");
            }).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        }
        ZnackSigningSession.authorizePipelineResumption(SHOP,21,original);
        var calls=new AtomicInteger();
        try(var ignored=ZnackSigningSession.openPipeline(SHOP,21)) {
            ZnackSigningSession.guard(SHOP,CERT,success(calls)).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE);
        }
        try(var ignored=ZnackSigningSession.openPipeline(SHOP,22)) {
            assertThrows(ZnackSigningSession.SigningDeferredException.class,()->ZnackSigningSession.guard(SHOP,CERT,success(calls))
                    .sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        }
        assertEquals(1,calls.get());assertTrue(new ZnackSigningPauseStore(SHOP).read().paused());
    }

    @Test void capturedBatchMembersCannotPiggybackAPausedSibling() throws Exception {
        ZnackSigningSession.authorizePipeline(SHOP,31,original);ZnackSigningSession.authorizePipeline(SHOP,32,original);
        ZnackSigningSession.MutationPermit sibling;
        try(var ignored=ZnackSigningSession.openPipeline(SHOP,32)) { sibling=ZnackSigningSession.captureMutationPermit(SHOP,original); }
        try(var ignored=ZnackSigningSession.openPipeline(SHOP,32)) {
            assertThrows(CryptoProException.class,()->ZnackSigningSession.guard(SHOP,CERT,(p,c)->{
                throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Synthetic cancellation");
            }).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        }
        ZnackSigningSession.authorizePipelineResumption(SHOP,31,original);
        ZnackSigningSession.MutationPermit leader;
        try(var ignored=ZnackSigningSession.openPipeline(SHOP,31)) { leader=ZnackSigningSession.captureMutationPermit(SHOP,original); }
        var posts=new AtomicInteger();
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->leader.execute(()->sibling.execute(posts::incrementAndGet)));
        assertEquals(0,posts.get());
    }

    @Test void configurationSaveDoesNotBlockBehindAnAdmittedNetworkMutation() throws Exception {
        ZnackSigningSession.authorizeShopExplicitly(repository);
        var permit=ZnackSigningSession.captureMutationPermit(SHOP,original);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        long generation=ZnackSigningSession.signerGenerationSnapshot(SHOP);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var request=executor.submit(()->permit.execute(()->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return 1;}));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            try {
                executor.submit(()->assertThrows(IllegalStateException.class,
                        ()->ZnackSigningSession.beginReconfiguration(SHOP,original,settings(NEXT,true)))).get(3,TimeUnit.SECONDS);
                assertEquals(generation,ZnackSigningSession.signerGenerationSnapshot(SHOP));
            } finally { release.countDown(); }
            assertEquals(1,request.get(3,TimeUnit.SECONDS));
        }
    }

    @Test void directMutationCompatibilityHelperStillRequiresSigningAdmission() throws Exception {
        var posts=new AtomicInteger();long generation=ZnackSigningSession.signerGenerationSnapshot(SHOP);
        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                ()->ZnackSigningSession.executePaidMutation(SHOP,generation,posts::incrementAndGet));
        assertEquals(0,posts.get());
        ZnackSigningSession.authorizeShopExplicitly(repository);
        assertEquals(1,ZnackSigningSession.executePaidMutation(SHOP,generation,posts::incrementAndGet));
        assertThrows(CryptoProException.class,()->ZnackSigningSession.guard(SHOP,CERT,(p,c)->{
            throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Synthetic cancellation");
        }).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                ()->ZnackSigningSession.executePaidMutation(SHOP,generation,posts::incrementAndGet));
        assertEquals(1,posts.get());
    }

    @Test void anotherProfileCannotReuseCapturedSignersOrMutationPermitsEvenAfterReturning() throws Exception {
        ZnackSigningSession.authorizeShopExplicitly(repository);
        var posts=new AtomicInteger();var signs=new AtomicInteger();
        var permit=ZnackSigningSession.captureMutationPermit(SHOP,original);
        var signer=ZnackSigningSession.guard(SHOP,CERT,success(signs));
        System.setProperty("vncode.appdata.dir",profile.resolve("other-profile").toString());
        try {
            Database.initDatabase();
            try(var c=Database.getConnection();var statement=c.createStatement()) {
                statement.execute("INSERT INTO shops(id,name,api_key) VALUES("+SHOP+",'Synthetic separate profile','fixture')");
            }
            var separate=repository(SHOP);separate.saveSettings(original);
            assertTrue(ZnackSigningSession.authorizeVerifiedShop(separate,separate.getSettings()));
            assertThrows(ZnackSigningSession.SigningDeferredException.class,()->permit.execute(posts::incrementAndGet));
            assertThrows(ZnackSigningSession.SigningDeferredException.class,()->signer.sign(new byte[]{1},ZnackSignatureContext.SUZ_POST_BODY));
            var separatePermit=ZnackSigningSession.captureMutationPermit(SHOP,separate.getSettings());
            assertEquals(1,separatePermit.execute(posts::incrementAndGet));
        } finally { System.setProperty("vncode.appdata.dir",profile.toString()); }
        assertTrue(ZnackSigningSession.authorizeVerifiedShop(repository,original));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->permit.execute(posts::incrementAndGet));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->signer.sign(new byte[]{1},ZnackSignatureContext.SUZ_POST_BODY));
        assertEquals(1,posts.get());assertEquals(0,signs.get());
    }

    @Test void explicitReadAuthenticationDoesNotUnpausePaidMutations() throws Exception {
        ZnackSigningSession.authorizeShopExplicitly(repository);
        assertThrows(CryptoProException.class,()->ZnackSigningSession.guard(SHOP,CERT,(p,c)->{
            throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Synthetic cancellation");
        }).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        ZnackSigningSession.authorizeShop(SHOP);
        var reads=new AtomicInteger();var purchases=new AtomicInteger();
        ZnackSigningSession.guardReadAuthentication(SHOP,CERT,success(reads)).sign(new byte[]{1},ZnackSignatureContext.CATALOG_AUTH_CHALLENGE);
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->ZnackSigningSession.guard(SHOP,CERT,success(purchases))
                .sign(new byte[]{1},ZnackSignatureContext.SUZ_POST_BODY));
        assertEquals(1,reads.get());assertEquals(0,purchases.get());assertTrue(new ZnackSigningPauseStore(SHOP).read().paused());
    }

    @Test void durableStateNeverStoresPrivateFailureTextOrCredentials() throws Exception {
        ZnackSigningSession.authorizeShopExplicitly(repository);
        assertThrows(CryptoProException.class,()->ZnackSigningSession.guard(SHOP,CERT,(p,c)->{
            throw new CryptoProException(CryptoProErrorCode.CANCELLED,"PIN=fixture-secret clientToken=fixture-secret");
        }).sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")) {
            p.setString(1,"znack.signing.v1."+SHOP);
            try(var row=p.executeQuery()) {
                assertTrue(row.next());String value=row.getString(1);
                assertFalse(value.contains("fixture-secret"));assertFalse(value.contains(original.omsConnection()));
                assertFalse(value.contains(CERT));assertTrue(value.contains("CANCELLED"));
            }
        }
    }

    private ZnackRepository repository(int shop) { return new ZnackRepository(new ShopContext(shop,"Synthetic")); }
    private Settings settings(String certificate,boolean tested) {
        return new Settings("http://127.0.0.1:9","http://127.0.0.1:9","fixture-oms","fixture-connection",
                "7700000001","","7700000001","",certificate,"[]","FIXTURE","01.01.2026","",true,
                "","[]","{}",tested?Instant.parse("2026-01-01T00:00:00Z"):null,"",profile.resolve("never-executed").toString(),
                "",60,"31.12.2030","CONFORMITY_DECLARATION");
    }
    private ZnackSignatureProvider success(AtomicInteger calls) {
        return (p,c)->{calls.incrementAndGet();return new CryptoProSigningResult(new byte[]{1},"fixture");};
    }
}
