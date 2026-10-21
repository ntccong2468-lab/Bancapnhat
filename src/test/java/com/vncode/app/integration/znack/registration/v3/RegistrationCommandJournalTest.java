package com.vncode.app.integration.znack.registration.v3;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;

class RegistrationCommandJournalTest {
    @TempDir Path temp;
    Owner owner=new Owner(1,"account","production","scope");
    RegistrationCommandJournal journal;
    RegistrationCommandJournal.Command submit=new RegistrationCommandJournal.Command(Action.SUBMIT,"101/11","reviewed payload");
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture')");}
        journal=new RegistrationCommandJournal();
    }
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    @Test void acknowledgedFeedSurvivesRestartWithoutAnotherSend()throws Exception {
        var calls=new AtomicInteger();
        assertEquals(List.of("feed-42"),journal.run(owner,List.of(submit),sent->{sent.run();calls.incrementAndGet();return List.of("feed-42");}));
        assertEquals(List.of("feed-42"),new RegistrationCommandJournal().run(owner,List.of(submit),sent->{fail("acknowledged request replayed");return List.of();}));
        assertEquals(1,calls.get());
    }
    @Test void timeoutBlocksChangedPayloadAndOtherActionsOnSameVariant()throws Exception {
        assertThrows(SocketTimeoutException.class,()->journal.run(owner,List.of(submit),sent->{sent.run();throw new SocketTimeoutException();}));
        var changed=new RegistrationCommandJournal.Command(Action.SUBMIT,"101/11","changed");
        assertThrows(IllegalStateException.class,()->journal.run(owner,List.of(changed),sent->{fail("unknown replayed");return List.of();}));
        assertThrows(IllegalStateException.class,()->journal.run(owner,List.of(new RegistrationCommandJournal.Command(Action.SIGN,"101/11","xml")),sent->{fail("unknown bypassed");return List.of();}));
    }
    @Test void allocationUncertaintyBlocksTheWholeOwnerPool()throws Exception {
        var allocation=new RegistrationCommandJournal.Command(Action.ALLOCATE_GTIN,"101/11","draft");
        assertThrows(SocketTimeoutException.class,()->journal.run(owner,List.of(allocation),sent->{sent.run();throw new SocketTimeoutException();}));
        assertThrows(IllegalStateException.class,()->journal.run(owner,List.of(new RegistrationCommandJournal.Command(Action.ALLOCATE_GTIN,"102/12","other")),sent->{fail();return List.of();}));
    }
    @Test void failureBeforeDispatchDoesNotBecomeAnUnknownRemoteWrite()throws Exception {
        assertThrows(IllegalArgumentException.class,()->journal.run(owner,List.of(submit),sent->{throw new IllegalArgumentException("preflight");}));
        assertEquals(List.of("feed"),journal.run(owner,List.of(submit),sent->{sent.run();return List.of("feed");}));
    }
    @Test void knownHttpRejectionCanBeRetriedButServerFailureCannot()throws Exception {
        assertThrows(ZnackApiException.class,()->journal.run(owner,List.of(submit),sent->{sent.run();throw new ZnackApiException("rejected",429,"");}));
        assertEquals(List.of("feed"),journal.run(owner,List.of(submit),sent->{sent.run();return List.of("feed");}));
        var other=new RegistrationCommandJournal.Command(Action.SUBMIT,"102/12","payload");
        assertThrows(ZnackApiException.class,()->journal.run(owner,List.of(other),sent->{sent.run();throw new ZnackApiException("unavailable",503,"");}));
        assertThrows(IllegalStateException.class,()->journal.run(owner,List.of(other),sent->{fail();return List.of();}));
    }
    @Test void twoWorkersCannotSendTheSameCommand()throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var pool=Executors.newSingleThreadExecutor()){
            var first=pool.submit(()->journal.run(owner,List.of(submit),sent->{sent.run();calls.incrementAndGet();started.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return List.of("feed");}));
            try{assertTrue(started.await(10,TimeUnit.SECONDS));assertThrows(IllegalStateException.class,()->new RegistrationCommandJournal().run(owner,List.of(submit),sent->{fail();return List.of();}));}
            finally{release.countDown();}
            assertEquals(List.of("feed"),first.get(10,TimeUnit.SECONDS));assertEquals(1,calls.get());
        }
    }
    @Test void signingGroupIsAtomicAndRegroupingCannotReplayOneUnknownMember()throws Exception {
        var a=new RegistrationCommandJournal.Command(Action.SIGN,"101/11","xml-a");var b=new RegistrationCommandJournal.Command(Action.SIGN,"102/12","xml-b");
        assertThrows(SocketTimeoutException.class,()->journal.run(owner,List.of(a,b),sent->{sent.run();throw new SocketTimeoutException();}));
        assertThrows(IllegalStateException.class,()->journal.run(owner,List.of(b),sent->{fail();return List.of();}));
        var c=new RegistrationCommandJournal.Command(Action.SIGN,"103/13","xml-c");
        assertThrows(IllegalStateException.class,()->journal.run(owner,List.of(c,b),sent->{fail();return List.of();}));
        assertEquals(List.of("13"),journal.run(owner,List.of(c),sent->{sent.run();return List.of("13");}));
    }
    @Test void onlyMatchingReadbackCanResolveUnknownAndOwnerIsIsolated()throws Exception {
        assertThrows(SocketTimeoutException.class,()->journal.run(owner,List.of(submit),sent->{sent.run();throw new SocketTimeoutException();}));
        var changed=new RegistrationCommandJournal.Command(Action.SUBMIT,"101/11","other payload");
        assertFalse(journal.reconcile(owner,changed,"wrong-feed"));
        assertFalse(journal.reconcile(new Owner(1,"other","production","scope"),submit,"wrong-owner"));
        assertTrue(journal.reconcile(owner,submit,"verified-feed"));
        assertEquals(List.of("verified-feed"),journal.run(owner,List.of(submit),sent->{fail();return List.of();}));
    }
    @Test void confirmedRejectedFeedRequiresTheExactReceiptBeforeAnotherAttempt()throws Exception {
        journal.run(owner,List.of(submit),sent->{sent.run();return List.of("feed-old");});
        assertFalse(journal.reject(owner,submit,"different-feed"));
        assertTrue(journal.reject(owner,submit,"feed-old"));
        assertEquals(List.of("feed-new"),journal.run(owner,List.of(submit),sent->{sent.run();return List.of("feed-new");}));
    }
    @Test void allocationReceiptReservesCodeBeforeTheLegacyCheckpointExists()throws Exception {
        var a=new RegistrationCommandJournal.Command(Action.ALLOCATE_GTIN,"101/11","generation-a");
        journal.run(owner,List.of(a),sent->{sent.run();return List.of("04631993764370");});
        assertTrue(new RegistrationStore().claimedGtins(owner).contains("04631993764370"));
        assertTrue(new com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository().claimedGtins().contains("04631993764370"));
        assertTrue(new com.vncode.app.integration.znack.registration.OzonCardRegistrationRepository().claimedGtins().contains("04631993764370"));
        assertEquals(List.of("04631993764370"),journal.run(owner,List.of(a),sent->{fail();return List.of();}));
        var b=new RegistrationCommandJournal.Command(Action.ALLOCATE_GTIN,"102/12","generation-b");
        assertThrows(IllegalStateException.class,()->journal.run(owner,List.of(b),sent->{sent.run();return List.of("4631993764370");}));
    }
    @Test void allocationGenerationPersistsAcrossReopenAndAdvancesOnlyOnExplicitReplacement()throws Exception {
        String generation=journal.allocationGeneration(1,101,11);
        assertEquals(generation,new RegistrationCommandJournal().allocationGeneration(1,101,11));
        try(var c=Database.getConnection()){RegistrationCommandJournal.advanceGeneration(c,1,101,11);}
        String replacement=journal.allocationGeneration(1,101,11);assertNotEquals(generation,replacement);
        assertEquals(replacement,new RegistrationCommandJournal().allocationGeneration(1,101,11));
        assertThrows(SecurityException.class,()->journal.allocationGeneration(1,999,11));
    }
    @Test void exactCardReadbackUnblocksSigningWithoutInventingAFeedOrReplayingSubmission()throws Exception {
        assertThrows(SocketTimeoutException.class,()->journal.run(owner,List.of(submit),sent->{sent.run();throw new SocketTimeoutException();}));
        assertTrue(journal.observe(owner,submit));
        assertThrows(IllegalStateException.class,()->journal.run(owner,List.of(submit),sent->{fail();return List.of();}));
        var sign=new RegistrationCommandJournal.Command(Action.SIGN,"101/11","xml");
        assertEquals(List.of("91"),journal.run(owner,List.of(sign),sent->{sent.run();return List.of("91");}));
    }
    @Test void sameParticipantPoolRemainsBlockedAcrossShopOrCertificateChanges()throws Exception {
        var first=new RegistrationCommandJournal.Command(Action.ALLOCATE_GTIN,"101/11","generation");
        assertThrows(SocketTimeoutException.class,()->journal.run(owner,"participant-production",List.of(first),sent->{sent.run();throw new SocketTimeoutException();}));
        var rotated=new Owner(1,"new-account-hash","production","new-certificate");
        var second=new RegistrationCommandJournal.Command(Action.ALLOCATE_GTIN,"102/12","new-generation");
        assertThrows(IllegalStateException.class,()->journal.run(rotated,"participant-production",List.of(second),sent->{fail();return List.of();}));
    }
    @Test void crashedWorkerIsNeverReplayed()throws Exception {
        assertThrows(SocketTimeoutException.class,()->journal.run(owner,List.of(submit),sent->{sent.run();throw new SocketTimeoutException();}));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_v3_dispatches SET state='SENDING',worker='999999999@2000-01-01T00:00:00Z'");}
        assertThrows(IllegalStateException.class,()->new RegistrationCommandJournal().run(owner,List.of(submit),sent->{fail();return List.of();}));
        assertTrue(journal.reconcile(owner,submit,"readback"));
    }
}
