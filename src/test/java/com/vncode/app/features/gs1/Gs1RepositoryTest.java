package com.vncode.app.features.gs1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class Gs1RepositoryTest {
    @TempDir Path directory;
    @Test void reviewedLetterAndPdfAreCommittedTogetherOrRolledBack() {
        var repository=new Gs1Repository(directory.resolve("attachments.db"));
        var pdf=new com.vncode.app.integration.gs1.Gs1MailClient.Attachment("reviewed.pdf","application/pdf","%PDF-1.4\nfixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThrows(IllegalStateException.class,()->repository.createRequestWithAttachments("7707083893","MORE_CODES","markirovka@gs1ru.org","Fixture","Reviewed",java.util.List.of(pdf,pdf)));
        assertTrue(repository.requests("7707083893").isEmpty(),"A failed attachment save must not leave a sendable draft");
        var request=repository.createRequestWithAttachments("7707083893","MORE_CODES","markirovka@gs1ru.org","Fixture","Reviewed",java.util.List.of(pdf));
        assertEquals(1,repository.attachments(request.inn(),request.id(),"draft").size());
        assertArrayEquals(pdf.bytes(),repository.attachments(request.inn(),request.id(),"draft").getFirst().bytes());
    }
    @Test void threadAttachmentListKeepsLargeBytesOutUntilDownload() {
        var repository=new Gs1Repository(directory.resolve("fixture.db"));var row=repository.createRequest("7707083893","RENEWAL","mail@gs1ru.org","A","body");
        repository.addMessage(row.inn(),row.id(),"reply",false,"mail@gs1ru.org","reply",true);
        var pdf=new byte[1024*1024];System.arraycopy("%PDF-1.7\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,pdf,0,9);
        repository.addAttachment(row.inn(),row.id(),"reply",new com.vncode.app.integration.gs1.Gs1MailClient.Attachment("large.pdf","application/pdf",pdf));
        var summaries=repository.attachmentSummaries(row.inn(),row.id());assertEquals(1,summaries.size());assertEquals(1024*1024,summaries.getFirst().size());assertEquals("reply",summaries.getFirst().messageId());
        assertTrue(new com.google.gson.Gson().toJson(summaries).length()<500);assertEquals(1024*1024,repository.attachment(row.inn(),row.id(),summaries.getFirst()).bytes().length);
        assertThrows(SecurityException.class,()->repository.attachmentSummaries("7811089030",row.id()));assertEquals(1,repository.unread(row.inn()));assertEquals(0,repository.unread("7811089030"));
    }
    @Test void repositorySeparatesEnterprises() {
        var repository=new Gs1Repository(directory.resolve("fixture.db"));
        var first=repository.createRequest("7707083893", "RENEWAL", "mail@gs1ru.org", "Subject", "Reviewed body");
        assertEquals(1, repository.requests("7707083893").size());
        assertTrue(repository.requests("7811089030").isEmpty());
        assertThrows(SecurityException.class,()->repository.request("7811089030",first.id()));
    }
    @Test void ambiguousSubmitSurvivesReopenAndCannotBeSentAgain() {
        Path file=directory.resolve("fixture.db");
        var repository=new Gs1Repository(file);
        var request=repository.createRequest("7707083893","JOIN","","Join","digest");
        assertTrue(repository.claimSend(request.inn(),request.id()));
        assertFalse(repository.claimSend(request.inn(),request.id()));
        repository.setState(request.inn(),request.id(), Gs1Repository.State.RECONCILE_REQUIRED);
        var reopened=new Gs1Repository(file);
        assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,reopened.request(request.inn(),request.id()).state());
        assertFalse(reopened.claimSend(request.inn(),request.id()));
    }
    @Test void openingAnotherRepositoryDoesNotChangeAnActiveSend() {
        Path file=directory.resolve("fixture.db");
        var first=new Gs1Repository(file);
        var request=first.createRequest("7707083893","RENEWAL","mail@gs1ru.org","Subject","Body");
        first.claimSend(request.inn(),request.id());
        var second=new Gs1Repository(file);
        assertEquals(Gs1Repository.State.SENDING,second.request(request.inn(),request.id()).state());
    }
    @Test void invoiceFromAnotherRequestCannotUnlockPaymentProof() {
        var repository=new Gs1Repository(directory.resolve("fixture.db"));
        var a=repository.createRequest("7707083893","RENEWAL","mail@gs1ru.org","A","body");
        var b=repository.createRequest("7707083893","RENEWAL","mail@gs1ru.org","B","body");
        repository.addMessage(a.inn(),a.id(),"invoice-fixture",false,"mail@gs1ru.org","Invoice",true);
        repository.confirmInvoice(a.inn(),a.id(),"invoice-fixture",new Gs1Repository.PortalInvoice(a.inn(),"INV-123","1000","RUB"));
        assertTrue(repository.hasConfirmedInvoice(a.inn(),a.id()));
        assertFalse(repository.hasConfirmedInvoice(b.inn(),b.id()));
        assertThrows(SecurityException.class,()->repository.confirmInvoice("7811089030",a.id(),"invoice-fixture",new Gs1Repository.PortalInvoice("7811089030","INV-123","1000","RUB")));
    }
    @Test void aFromHeaderAloneCannotVerifyAnInvoice() {
        var repository=new Gs1Repository(directory.resolve("fixture.db"));
        var request=repository.createRequest("7707083893","RENEWAL","mail@gs1ru.org","A","body");
        repository.addMessage(request.inn(),request.id(),"forged",false,"mail@gs1ru.org","Purported invoice",true);
        assertFalse(repository.hasConfirmedInvoice(request.inn(),request.id()));
        assertThrows(SecurityException.class,()->repository.confirmInvoice(request.inn(),request.id(),"forged",new Gs1Repository.PortalInvoice("7811089030","INV-123","1000","RUB")));
        assertThrows(IllegalArgumentException.class,()->new Gs1Repository.PortalInvoice(request.inn(),"","1000","RUB"));
        assertThrows(IllegalArgumentException.class,()->new Gs1Repository.PortalInvoice(request.inn(),"INV-123","-1000","RUB"));
        assertFalse(repository.hasConfirmedInvoice(request.inn(),request.id()));
    }
    @Test void competingRepositoriesCreateOnlyOneReminderCheckpoint() throws Exception {
        Path file=directory.resolve("reminder.db");var first=new Gs1Repository(file);var second=new Gs1Repository(file);
        var original=first.createRequest("7707083893","MORE_CODES","markirovka@gs1ru.org","Fixture","Fixture");
        var start=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return first.createReminder(original.inn(),original.id(),"Reminder","Fixture");});
            var b=pool.submit(()->{start.await();return second.createReminder(original.inn(),original.id(),"Reminder","Fixture");});
            int created=(a.get().isPresent()?1:0)+(b.get().isPresent()?1:0);
            assertEquals(1,created);
        }
        assertEquals(1,new Gs1Repository(file).requests(original.inn()).stream().filter(r->r.kind().equals("REMINDER:"+original.id())).count());
        assertThrows(SecurityException.class,()->second.createReminder("7811089030",original.id(),"Reminder","Fixture"));
    }
    @Test void onlyFreshOfficialProductionMembershipCompletesOwnSuccessfulMembershipRequests(){
        var repository=new Gs1Repository(directory.resolve("complete.db"));String inn="7707083893";
        var invoice=repository.createRequest(inn,"JOINING_INVOICE","mail@gs1ru.org","Fixture","Fixture");repository.setState(inn,invoice.id(),Gs1Repository.State.SENT);
        var restore=repository.createRequest(inn,"RESTORE_MEMBERSHIP","mail@gs1ru.org","Fixture","Fixture");repository.setState(inn,restore.id(),Gs1Repository.State.SENT);
        var uncertain=repository.createRequest(inn,"RESTORE_MEMBERSHIP","mail@gs1ru.org","Fixture","Fixture");repository.setState(inn,uncertain.id(),Gs1Repository.State.RECONCILE_REQUIRED);
        var sending=repository.createRequest(inn,"JOINING_INVOICE","mail@gs1ru.org","Fixture","Fixture");repository.setState(inn,sending.id(),Gs1Repository.State.SENDING);
        var foreign=repository.createRequest("7811089030","JOINING_INVOICE","mail@gs1ru.org","Fixture","Fixture");repository.setState(foreign.inn(),foreign.id(),Gs1Repository.State.SENT);
        var today=java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow"));var now=java.time.Instant.now();
        var valid=new com.vncode.app.integration.gs1.Gs1Membership(inn,"Fixture",true,false,today.plusDays(120),java.util.List.of(),now,com.vncode.app.integration.gs1.Gs1Membership.CATALOG_SOURCE);
        assertEquals(0,repository.completeMembershipRequests(valid,"sandbox"));
        var stale=new com.vncode.app.integration.gs1.Gs1Membership(inn,"Fixture",true,false,today.plusDays(120),java.util.List.of(),now.minus(java.time.Duration.ofDays(2)),valid.source());
        assertEquals(0,repository.completeMembershipRequests(stale,"production"));
        var unsupported=new com.vncode.app.integration.gs1.Gs1Membership(inn,"Fixture",true,false,today.plusDays(120),java.util.List.of(),now,"smtp:reply");
        assertEquals(0,repository.completeMembershipRequests(unsupported,"production"));
        var excluded=new com.vncode.app.integration.gs1.Gs1Membership(inn,"Fixture",true,true,today.plusDays(120),java.util.List.of(),now,valid.source());
        assertEquals(0,repository.completeMembershipRequests(excluded,"production"));
        assertEquals(2,repository.completeMembershipRequests(valid,"production"));
        assertEquals(Gs1Repository.State.RESOLVED,repository.request(inn,invoice.id()).state());
        assertEquals(Gs1Repository.State.RESOLVED,repository.request(inn,restore.id()).state());
        assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,repository.request(inn,uncertain.id()).state());
        assertEquals(Gs1Repository.State.SENDING,repository.request(inn,sending.id()).state());
        assertEquals(Gs1Repository.State.SENT,repository.request(foreign.inn(),foreign.id()).state());
        assertEquals(0,repository.completeMembershipRequests(valid,"production"));
    }
}
