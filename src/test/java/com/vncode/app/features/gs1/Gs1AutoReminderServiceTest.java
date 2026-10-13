package com.vncode.app.features.gs1;

import com.vncode.app.features.license.*;
import com.vncode.app.integration.gs1.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Gs1AutoReminderServiceTest {
    @TempDir Path directory;
    private final LicenseService prior=LicenseRuntime.service();
    private LicenseService denied;
    private static final String INN="7707083893";
    private Gs1MailAccount account(){return new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"user","sender@fixture.ru","dpapi:fixture");}
    private Clock due(){return Clock.fixed(Instant.now().plus(Duration.ofDays(8)),ZoneOffset.UTC);}
    private Gs1Repository repository(){return new Gs1Repository(directory.resolve("fixture.db"));}
    private Gs1Repository.Request original(Gs1Repository repo)throws Exception{
        // An accepted legacy request is history, not a new prefix send that can bypass its live preflight.
        var request=repo.createRequest(INN,"MORE_CODES","mail@gs1ru.org","Fixture","Reviewed legacy request");
        repo.setState(INN,request.id(),Gs1Repository.State.SENT);repo.addMessage(INN,request.id(),"<"+request.id()+".vncode@vncode.local>",true,account().from(),request.body(),false);
        repo.saveMailAccount(INN,account());return repo.request(INN,request.id());
    }
    @AfterEach void restore(){LicenseRuntime.install(prior);if(denied!=null)denied.close();}

    @Test void absenceOfExplicitConsentAndWaitingPeriodNeverAccessMailboxOrSend()throws Exception{
        var repo=repository();var original=original(repo);var reads=new AtomicInteger();var sends=new AtomicInteger();
        var service=new Gs1AutoReminderService(repo,(a,l,g)->sends.incrementAndGet(),(a,i)->reads.incrementAndGet(),Clock.systemUTC());
        assertEquals(0,service.runDue().considered());
        assertThrows(IllegalArgumentException.class,()->service.enable(INN,original.id(),account(),false));
        assertTrue(repo.reminderConsent(INN,original.id()).isEmpty());
        service.enable(INN,original.id(),account(),true);
        assertEquals(Gs1AutoReminderService.State.WAITING,service.status(INN,original.id(),account()).state());
        service.runDue();assertEquals(0,reads.get());assertEquals(0,sends.get());
        assertThrows(SecurityException.class,()->service.enable("7811089030",original.id(),account(),true));
    }
    @Test void persistentConsentSendsOneCorrelatedReminderAndNeverReplaysAfterRestart()throws Exception{
        var repo=repository();var original=original(repo);var reads=new AtomicInteger();var sends=new AtomicInteger();
        Gs1AutoReminderService.Sender send=(a,l,g)->{g.check();assertEquals(original.id(),l.threadId());assertEquals(original.recipient(),l.recipient());assertTrue(l.body().contains(original.body()));sends.incrementAndGet();};
        var service=new Gs1AutoReminderService(repo,send,(a,i)->reads.incrementAndGet(),due());
        service.enable(INN,original.id(),account(),true);
        var restarted=new Gs1AutoReminderService(new Gs1Repository(directory.resolve("fixture.db")),send,(a,i)->reads.incrementAndGet(),due());
        assertEquals(1,restarted.runDue().sent());assertEquals(1,reads.get());assertEquals(1,sends.get());
        restarted.runDue();service.runDue();assertEquals(1,sends.get());
        assertEquals(Gs1AutoReminderService.State.SENT,restarted.status(INN,original.id(),account()).state());
        assertThrows(IllegalStateException.class,()->service.enable(INN,original.id(),account(),true));
    }
    @Test void changedAccountOrFrozenOriginalContentStopsTheAutomaticJob()throws Exception{
        var repo=repository();var original=original(repo);var sends=new AtomicInteger();var reads=new AtomicInteger();
        var service=new Gs1AutoReminderService(repo,(a,l,g)->sends.incrementAndGet(),(a,i)->reads.incrementAndGet(),due());service.enable(INN,original.id(),account(),true);
        var changed=new Gs1MailAccount("smtp.other",465,"imap.fixture",993,"user","sender@fixture.ru","dpapi:fixture");repo.saveMailAccount(INN,changed);
        assertEquals(Gs1AutoReminderService.State.ACCOUNT_CHANGED,service.status(INN,original.id(),changed).state());service.runDue();
        assertEquals(0,reads.get());assertEquals(0,sends.get());repo.saveMailAccount(INN,account());
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("fixture.db"));var p=c.prepareStatement("UPDATE gs1_requests SET body=? WHERE inn=? AND id=?")){
            p.setString(1,"Changed content");p.setString(2,INN);p.setString(3,original.id());p.executeUpdate();
        }
        assertEquals(Gs1AutoReminderService.State.CONTENT_CHANGED,service.status(INN,original.id(),account()).state());service.runDue();assertEquals(0,reads.get());assertEquals(0,sends.get());
    }
    @Test void credentialRotationDoesNotChangeReviewedMailboxIdentity()throws Exception{
        var repo=repository();var original=original(repo);var writes=new AtomicInteger();
        var service=new Gs1AutoReminderService(repo,(a,l,g)->{g.check();writes.incrementAndGet();},(a,i)->{},due());service.enable(INN,original.id(),account(),true);
        var rotated=new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"user","sender@fixture.ru","dpapi:rotated");repo.saveMailAccount(INN,rotated);
        assertEquals(1,service.runDue().sent());assertEquals(1,writes.get());
        var consent=repo.reminderConsent(INN,original.id()).orElseThrow();assertFalse(consent.toString().contains("sender"));assertFalse(consent.toString().contains("rotated"));
    }
    @Test void freshReplyOrImapFailureNeverCountsAsNoReply()throws Exception{
        var repo=repository();var original=original(repo);var writes=new AtomicInteger();
        var unavailable=new Gs1AutoReminderService(repo,(a,l,g)->writes.incrementAndGet(),(a,i)->{throw new IOException("fixture unavailable");},due());unavailable.enable(INN,original.id(),account(),true);
        assertEquals(1,unavailable.runDue().failed());assertEquals(0,writes.get());assertNull(repo.reminderConsent(INN,original.id()).orElseThrow().reminderId());
        var reply=new Gs1AutoReminderService(repo,(a,l,g)->writes.incrementAndGet(),(a,i)->repo.addMessage(INN,original.id(),"new-reply",false,"mail@gs1ru.org","Received",true),due());
        assertEquals(0,reply.runDue().sent());assertEquals(0,writes.get());assertEquals(Gs1AutoReminderService.State.REPLY_RECEIVED,reply.status(INN,original.id(),account()).state());
    }
    @Test void ambiguousAndKnownUnsentAttemptsRequireManualRecoveryAndAreNeverAutomaticallyRetried()throws Exception{
        for(boolean ambiguous:List.of(false,true)){
            var repo=new Gs1Repository(directory.resolve("delivery-"+ambiguous+".db"));var original=original(repo);var attempts=new AtomicInteger();
            var service=new Gs1AutoReminderService(repo,(a,l,g)->{g.check();attempts.incrementAndGet();if(ambiguous)throw new IOException("fixture missing SMTP answer");throw new Gs1MailClient.NotSentException();},(a,i)->{},due());service.enable(INN,original.id(),account(),true);
            service.runDue();service.runDue();assertEquals(1,attempts.get());
            String id=repo.reminderConsent(INN,original.id()).orElseThrow().reminderId();assertNotNull(id);
            assertEquals(ambiguous?Gs1Repository.State.RECONCILE_REQUIRED:Gs1Repository.State.DRAFT,repo.request(INN,id).state());
            assertEquals(ambiguous?Gs1AutoReminderService.State.RECONCILE_REQUIRED:Gs1AutoReminderService.State.KNOWN_UNSENT,service.status(INN,original.id(),account()).state());
        }
    }
    @Test void revokeDuringMailboxRefreshAndImmediatelyBeforeSmtpStopsWrites()throws Exception{
        var repo=repository();var original=original(repo);var writes=new AtomicInteger();
        var beforePreparation=new Gs1AutoReminderService(repo,(a,l,g)->writes.incrementAndGet(),(a,i)->repo.disableReminderConsent(INN,original.id()),due());beforePreparation.enable(INN,original.id(),account(),true);beforePreparation.runDue();
        assertEquals(0,writes.get());assertNull(repo.reminderConsent(INN,original.id()).orElseThrow().reminderId());
        var beforeSend=new Gs1AutoReminderService(repo,(a,l,g)->{repo.disableReminderConsent(INN,original.id());g.check();writes.incrementAndGet();},(a,i)->{},due());beforeSend.enable(INN,original.id(),account(),true);beforeSend.runDue();
        assertEquals(0,writes.get());var id=repo.reminderConsent(INN,original.id()).orElseThrow().reminderId();assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,id).state());
        assertEquals(Gs1AutoReminderService.State.DISABLED,beforeSend.status(INN,original.id(),account()).state());
    }
    @Test void licenseLossAtSendGuardLeavesDraftWithoutRepeatingAutomaticAttempt()throws Exception{
        var repo=repository();var original=original(repo);var writes=new AtomicInteger();denied=LicenseDomainFixture.denied(directory.resolve("denied"));
        var service=new Gs1AutoReminderService(repo,(a,l,g)->{LicenseRuntime.install(denied);g.check();writes.incrementAndGet();},(a,i)->{},due());service.enable(INN,original.id(),account(),true);service.runDue();
        assertEquals(0,writes.get());var id=repo.reminderConsent(INN,original.id()).orElseThrow().reminderId();assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,id).state());
        LicenseRuntime.install(prior);service.runDue();assertEquals(0,writes.get());
    }
    @Test void twoSchedulersCannotPrepareOrSendTheSameReminderTwice()throws Exception{
        var repo=repository();var original=original(repo);var writes=new AtomicInteger();var checked=new java.util.concurrent.CyclicBarrier(2);
        Gs1AutoReminderService.MailboxRefresher refresh=(a,i)->{try{checked.await();}catch(Exception failure){throw new IOException("fixture synchronization failed");}};
        Gs1AutoReminderService.Sender send=(a,l,g)->{g.check();writes.incrementAndGet();};
        var first=new Gs1AutoReminderService(repo,send,refresh,due());var second=new Gs1AutoReminderService(new Gs1Repository(directory.resolve("fixture.db")),send,refresh,due());first.enable(INN,original.id(),account(),true);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var a=pool.submit((java.util.concurrent.Callable<Gs1AutoReminderService.TickResult>)first::runDue);var b=pool.submit((java.util.concurrent.Callable<Gs1AutoReminderService.TickResult>)second::runDue);a.get();b.get();}
        assertEquals(1,writes.get());assertEquals(1,repo.requests(INN).stream().filter(r->r.kind().equals("REMINDER:"+original.id())).count());
    }
    @Test void boundedScanRotatesPastBlockedConsentsAcrossRestart()throws Exception{
        var repo=repository();
        for(int i=0;i<100;i++){
            var r=repo.createRequest(INN,"MORE_CODES","markirovka@gs1ru.org","Fixture","Fixture");repo.setState(INN,r.id(),Gs1Repository.State.SENT);
            repo.enableReminderConsent(INN,r.id(),"a".repeat(64),"b".repeat(64),Instant.parse("2026-01-01T00:00:00Z"));
        }
        var original=original(repo);var writes=new AtomicInteger();Gs1AutoReminderService.Sender send=(a,l,g)->{g.check();writes.incrementAndGet();};
        var service=new Gs1AutoReminderService(repo,send,(a,i)->{},due());service.enable(INN,original.id(),account(),true);
        assertEquals(100,service.runDue().considered());assertEquals(0,writes.get());
        var restarted=new Gs1AutoReminderService(new Gs1Repository(directory.resolve("fixture.db")),send,(a,i)->{},due());
        assertEquals(1,restarted.runDue().sent());assertEquals(1,writes.get());
    }
    @Test void changedPreparedContentIsDetectedAgainAtSmtpBoundary()throws Exception{
        var repo=repository();var original=original(repo);var writes=new AtomicInteger();
        var service=new Gs1AutoReminderService(repo,(a,l,g)->{
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("fixture.db"));var p=c.prepareStatement("UPDATE gs1_requests SET body=? WHERE inn=? AND id=?")){
                p.setString(1,"Unreviewed replacement");p.setString(2,INN);p.setString(3,l.id());p.executeUpdate();
            }catch(SQLException invalid){throw new IOException("fixture update failed");}
            g.check();writes.incrementAndGet();
        },(a,i)->{},due());service.enable(INN,original.id(),account(),true);service.runDue();
        assertEquals(0,writes.get());var id=repo.reminderConsent(INN,original.id()).orElseThrow().reminderId();assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,id).state());
    }
    @Test void eachTickAttemptsAtMostTwentyDueRequests()throws Exception{
        var repo=repository();var writes=new AtomicInteger();var service=new Gs1AutoReminderService(repo,(a,l,g)->{g.check();writes.incrementAndGet();},(a,i)->{},due());
        for(int i=0;i<25;i++){var original=original(repo);service.enable(INN,original.id(),account(),true);}
        assertEquals(20,service.runDue().sent());assertEquals(20,writes.get());assertEquals(5,service.runDue().sent());assertEquals(25,writes.get());
    }
    @Test void contentDriftBetweenPreviewAndConsentRequiresAnotherReview()throws Exception{
        var repo=repository();var original=original(repo);var service=new Gs1AutoReminderService(repo,(a,l,g)->fail("No send during consent"),(a,i)->{},due());
        var reviewed=service.previewReminder(INN,original.id());
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("fixture.db"));var p=c.prepareStatement("UPDATE gs1_requests SET body=? WHERE inn=? AND id=?")){
            p.setString(1,"Changed after preview");p.setString(2,INN);p.setString(3,original.id());p.executeUpdate();
        }
        assertThrows(IllegalStateException.class,()->service.enable(INN,original.id(),account(),reviewed,true));assertTrue(repo.reminderConsent(INN,original.id()).isEmpty());
        service.enable(INN,original.id(),account(),service.previewReminder(INN,original.id()),true);assertTrue(repo.reminderConsent(INN,original.id()).orElseThrow().enabled());
    }
}
