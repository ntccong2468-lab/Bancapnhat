package com.vncode.app.features.license;

import com.google.gson.*;
import com.vncode.app.integration.license.LicenseApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.features.license.LicenseModels.*;

class LicenseServiceTest {
    @TempDir Path directory;
    final long epoch=Instant.now().getEpochSecond();
    final AtomicLong nano=new AtomicLong();
    class Api extends LicenseDomainFixture.UnusedApi {
        final KeyPair server=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        int activations,renewals;int hours=72;boolean advanceServerClock;String accountStatus="pending";Failure failure;String challengeActionOverride;CountDownLatch entered,release;
        Api()throws Exception{}
        public Registration start(String email){return new Registration(LicenseDomainFixture.TOKEN);}
        public RegistrationStatus verify(String token,String otp){return new RegistrationStatus("client@gmail.com",accountStatus);}
        public RegistrationStatus status(String token){return new RegistrationStatus("client@gmail.com",accountStatus);}
        public Challenge challenge(String token,String publicKey,String action)throws IOException {
            if(entered!=null){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("TEST_TIMEOUT");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("TEST_INTERRUPTED");}}
            if(failure!=null)throw failure;
            return new Challenge("fixture-challenge","vncode-device-v1\n"+(challengeActionOverride==null?action:challengeActionOverride)+"\nfixture-challenge\nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn",serverNow()+120);
        }
        public Grant activate(String token,Challenge c,String pub,String signature,String code)throws IOException {activations++;return grant(c,pub,signature);}
        public Grant renew(String token,Challenge c,String pub,String signature)throws IOException {renewals++;return grant(c,pub,signature);}
        private Grant grant(Challenge c,String pub,String sig)throws IOException {
            try {
                long issued=serverNow();
                var v=Signature.getInstance("Ed25519");v.initVerify(LicenseDeviceKeys.publicKey(pub));v.update(c.message().getBytes(StandardCharsets.UTF_8));assertTrue(v.verify(LicenseDeviceKeys.decode(sig,64)));
                var json=new JsonObject();json.addProperty("formatVersion",1);json.addProperty("product","vncode");json.addProperty("accountId","fixture-account");json.addProperty("deviceId","fixture-device");
                json.addProperty("devicePublicKeyHash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pub.getBytes(StandardCharsets.UTF_8))));json.addProperty("licenseRevision",1);json.addProperty("issuedAt",issued);json.addProperty("notBefore",issued);json.addProperty("expiresAt",issued+hours*3600);json.addProperty("subscriptionExpiresAt",epoch+7*86400);json.addProperty("maxDevices",1);json.addProperty("maxShops",2);json.addProperty("offlineHours",hours);
                var p=new JsonArray();for(var right:Permission.values())p.add(right.name().toLowerCase(Locale.ROOT));json.add("permissions",p);
                return new Grant(LicenseDomainFixture.TOKEN,new Account("fixture-account","client@gmail.com","approved",epoch+7*86400),LicenseDomainFixture.signJson(server,json.toString()),issued+300);
            }catch(Exception failure){throw new IOException("TEST_CRYPTO_FAILURE");}
        }
        private long serverNow(){return epoch+(advanceServerClock?TimeUnit.NANOSECONDS.toSeconds(nano.get()):0);}
    }
    LicenseService service(Api api,boolean trusted)throws Exception {
        var service=new LicenseService(api,new LicenseVerifier(trusted?Map.of("test-fixture-v1",api.server.getPublic()):Map.of()),LicenseDomainFixture.store(directory),Clock.fixed(Instant.ofEpochSecond(epoch),ZoneOffset.UTC),nano::get);service.initialize().get(5,TimeUnit.SECONDS);return service;
    }
    void register(LicenseService s)throws Exception{s.startRegistration("client@gmail.com").get(5,TimeUnit.SECONDS);s.verifyEmail("123456").get(5,TimeUnit.SECONDS);}
    void active(LicenseService s,Api api)throws Exception{api.accountStatus="approved";register(s);s.activate(LicenseDomainFixture.CODE).get(5,TimeUnit.SECONDS);assertTrue(s.allows(Permission.GTIN));}
    @Test void automaticChecksAreSpacedButManualRefreshRemainsImmediate()throws Exception {
        var api=new Api();api.advanceServerClock=true;try(var s=service(api,true)){
            active(s,api);var tick=LicenseService.class.getDeclaredMethod("tick");tick.setAccessible(true);
            nano.set(TimeUnit.SECONDS.toNanos(60));tick.invoke(s);
            nano.set(TimeUnit.SECONDS.toNanos(120));tick.invoke(s);
            assertEquals(0,api.renewals);
            s.refresh().get(5,TimeUnit.SECONDS);assertEquals(1,api.renewals);
            nano.set(TimeUnit.SECONDS.toNanos(180));tick.invoke(s);assertEquals(1,api.renewals);
            api.entered=new CountDownLatch(1);api.release=new CountDownLatch(1);
            nano.set(TimeUnit.SECONDS.toNanos(300));tick.invoke(s);assertTrue(api.entered.await(2,TimeUnit.SECONDS));
            var automatic=s.refresh();api.release.countDown();automatic.get(5,TimeUnit.SECONDS);
            assertEquals(2,api.renewals);assertTrue(s.allows(Permission.KIZ));
        }
    }
    @Test void approvedRegistrationIsNotLicenseAndEmptyPinNeverRedeems()throws Exception {
        var api=new Api();api.accountStatus="approved";try(var s=service(api,false)){register(s);assertEquals(Status.APPROVED,s.snapshot().status());assertFalse(s.allows(Permission.SELLER_API));s.activate(LicenseDomainFixture.CODE).get();assertEquals(0,api.activations);assertEquals("LICENSE_TRUST_NOT_CONFIGURED",s.snapshot().errorCode());}
    }
    @Test void activationUsesDeviceProofAndKeepsProtectedStateAndLogoutDevice()throws Exception {
        var api=new Api();try(var s=service(api,true)){active(s,api);assertEquals(Status.ACTIVE,s.snapshot().status());assertEquals(2,s.maxShops());var before=LicenseDomainFixture.store(directory).load().orElseThrow();assertNotNull(before.accessToken());s.logout().get();var after=LicenseDomainFixture.store(directory).load().orElseThrow();assertEquals(before.privateKey(),after.privateKey());assertEquals(before.publicKey(),after.publicKey());assertNull(after.accessToken());assertNull(after.envelope());assertFalse(s.allows(Permission.SELLER_API));}
    }
    @Test void networkFailureUsesSignedOfflineButProtocolFailureNeverDoes()throws Exception {
        var api=new Api();try(var s=service(api,true)){active(s,api);api.failure=new LicenseApi.Failure("LICENSE_NETWORK",0);s.refresh().get();assertEquals(Status.OFFLINE,s.snapshot().status());assertTrue(s.allows(Permission.SELLER_API));api.failure=new LicenseApi.Failure("LICENSE_PROTOCOL_INVALID",0);s.refresh().get();assertFalse(s.allows(Permission.SELLER_API));assertEquals(Status.UNVERIFIED,s.snapshot().status());}
    }
    @Test void explicitDenialSurvivesRestartAndLaterNetworkError()throws Exception {
        var api=new Api();try(var s=service(api,true)){active(s,api);api.failure=new LicenseApi.Failure("DEVICE_REVOKED",403);s.refresh().get();assertFalse(s.allows(Permission.GTIN));api.failure=new LicenseApi.Failure("LICENSE_NETWORK",0);s.refresh().get();assertFalse(s.allows(Permission.GTIN));}
        try(var restarted=service(api,true)){assertEquals(Status.DENIED,restarted.snapshot().status());assertFalse(restarted.allows(Permission.GTIN));}
    }
    @Test void zeroOfflineHoursWorksOnlyWithinFreshOnlineDeadline()throws Exception {
        var api=new Api();api.hours=0;try(var s=service(api,true)){active(s,api);nano.set(300_000_000_000L);assertFalse(s.allows(Permission.PRINT_NEW));}
        nano.set(0);api.failure=new LicenseApi.Failure("LICENSE_NETWORK",0);try(var restarted=service(api,true)){assertFalse(restarted.allows(Permission.PRINT_NEW));}
    }
    @Test void renewalIsCoalescedAndLateResponseCannotRestoreLoggedOutRights()throws Exception {
        var api=new Api();try(var s=service(api,true)){active(s,api);api.entered=new CountDownLatch(1);api.release=new CountDownLatch(1);var one=s.refresh();assertTrue(api.entered.await(2,TimeUnit.SECONDS));assertSame(one,s.refresh());var logout=s.logout();assertFalse(s.allows(Permission.KIZ));api.release.countDown();one.get(5,TimeUnit.SECONDS);logout.get(5,TimeUnit.SECONDS);assertFalse(s.allows(Permission.KIZ));assertEquals(Status.UNREGISTERED,s.snapshot().status());assertNull(LicenseDomainFixture.store(directory).load().orElseThrow().accessToken());}
    }
    @Test void checkingRegistrationAfterActivationDoesNotDiscardActiveSession()throws Exception {
        var api=new Api();try(var s=service(api,true)){active(s,api);s.checkStatus().get();assertTrue(s.allows(Permission.PRINT_NEW));assertNotNull(LicenseDomainFixture.store(directory).load().orElseThrow().accessToken());}
    }
    @Test void listenerDisposalAndRedactedModels()throws Exception {
        var api=new Api();try(var s=service(api,true)){var listener=s.subscribe(ignored->{});assertEquals(1,s.listenerCount());listener.close();assertEquals(0,s.listenerCount());active(s,api);assertFalse(s.snapshot().toString().contains("client@gmail.com"));assertFalse(LicenseDomainFixture.store(directory).load().orElseThrow().toString().contains(LicenseDomainFixture.TOKEN));}
    }
    @Test void fullQueueCannotDropLogoutOrReauthorizeOldAccount()throws Exception {
        var api=new Api();try(var s=service(api,true)){
            active(s,api);api.entered=new CountDownLatch(1);api.release=new CountDownLatch(1);
            var inflight=s.refresh();assertTrue(api.entered.await(2,TimeUnit.SECONDS));
            var queued=new ArrayList<CompletableFuture<Snapshot>>();for(int i=0;i<8;i++)queued.add(s.verifyEmail("123456"));
            var logout=s.logout();assertFalse(s.allows(Permission.SELLER_API));api.release.countDown();
            inflight.get(5,TimeUnit.SECONDS);logout.get(5,TimeUnit.SECONDS);for(var request:queued)request.get(5,TimeUnit.SECONDS);
            assertNull(LicenseDomainFixture.store(directory).load().orElseThrow().accessToken());
        }
        try(var restarted=service(api,true)){assertFalse(restarted.allows(Permission.SELLER_API));}
    }
    @Test void signedConfigurationRefusalPersistsAcrossRestart()throws Exception {
        var api=new Api();try(var s=service(api,true)){active(s,api);api.failure=new LicenseApi.Failure("SIGNING_NOT_CONFIGURED",503);s.refresh().get();assertFalse(s.allows(Permission.SELLER_API));}
        try(var restarted=service(api,true)){assertFalse(restarted.allows(Permission.SELLER_API));assertEquals(Status.DENIED,restarted.snapshot().status());}
    }
    @Test void challengeForDifferentPurposeIsNeverSignedOrActivated()throws Exception {
        var api=new Api();api.accountStatus="approved";try(var s=service(api,true)){register(s);api.challengeActionOverride="renew";s.activate(LicenseDomainFixture.CODE).get();assertEquals(0,api.activations);assertEquals("LICENSE_PROTOCOL_INVALID",s.snapshot().errorCode());assertFalse(s.allows(Permission.GTIN));}
    }
    @Test void offlineRefreshPersistsHighwaterAndRestartRejectsRollback()throws Exception {
        var api=new Api();var seconds=new AtomicLong(epoch);
        Clock wall=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId ignored){return this;}public Instant instant(){return Instant.ofEpochSecond(seconds.get());}};
        var s=new LicenseService(api,new LicenseVerifier(Map.of("test-fixture-v1",api.server.getPublic())),LicenseDomainFixture.store(directory),wall,nano::get);
        try(s){s.initialize().get();active(s,api);seconds.set(epoch+3600);nano.set(3_600_000_000_000L);api.failure=new LicenseApi.Failure("LICENSE_NETWORK",0);s.refresh().get();assertTrue(s.allows(Permission.SELLER_API));assertTrue(LicenseDomainFixture.store(directory).load().orElseThrow().maxObservedEpoch()>=epoch+3600);}
        seconds.set(epoch+100);nano.set(0);
        try(var restarted=new LicenseService(api,new LicenseVerifier(Map.of("test-fixture-v1",api.server.getPublic())),LicenseDomainFixture.store(directory),wall,nano::get)){restarted.initialize().get();assertFalse(restarted.allows(Permission.SELLER_API));}
    }
    @Test void restartChecksServerBeforeAllowingNewTasks()throws Exception {
        var api=new Api();try(var s=service(api,true)){active(s,api);}
        api.entered=new CountDownLatch(1);api.release=new CountDownLatch(1);api.failure=new LicenseApi.Failure("DEVICE_REVOKED",403);
        try(var restarted=new LicenseService(api,new LicenseVerifier(Map.of("test-fixture-v1",api.server.getPublic())),LicenseDomainFixture.store(directory),Clock.fixed(Instant.ofEpochSecond(epoch),ZoneOffset.UTC),nano::get)){
            var ready=restarted.initialize();assertTrue(api.entered.await(2,TimeUnit.SECONDS));assertFalse(restarted.allows(Permission.SELLER_API));api.release.countDown();ready.get(5,TimeUnit.SECONDS);assertFalse(restarted.allows(Permission.SELLER_API));
        }
    }

}
