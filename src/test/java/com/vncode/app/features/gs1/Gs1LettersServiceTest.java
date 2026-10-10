package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Gs1LettersServiceTest {
    @TempDir Path directory;
    private Gs1MailAccount account(){return new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"user","sender@fixture.ru","dpapi:fixture");}
    @Test void unknownSmtpDeliveryCannotBeRetried() {
        var repo=new Gs1Repository(directory.resolve("fixture.db"));var calls=new AtomicInteger();
        var service=new Gs1LettersService(repo,(a,l)->{calls.incrementAndGet();throw new IOException("secret=credential upstream failure");});
        var request=service.draft("7707083893","Fixture",Gs1LettersService.Kind.RENEWAL,null,"");
        var error=assertThrows(IOException.class,()->service.send(account(),request.inn(),request.id()));
        assertFalse(error.getMessage().contains("credential"));
        assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,repo.request(request.inn(),request.id()).state());
        assertThrows(IllegalStateException.class,()->service.send(account(),request.inn(),request.id()));
        assertEquals(1,calls.get());
    }
    @Test void smtpSuccessDoesNotMeanGs1Approval() throws Exception {
        var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1LettersService(repo,(a,l)->{});
        var request=service.draft("7707083893","Fixture",Gs1LettersService.Kind.MORE_CODES,1000,"");
        service.send(account(),request.inn(),request.id());
        assertEquals(Gs1Repository.State.SENT,repo.request(request.inn(),request.id()).state());
        assertTrue(repo.membership(request.inn()).isEmpty());
        assertEquals("markirovka@gs1ru.org",request.recipient());
        assertEquals(1,repo.messages(request.inn(),request.id()).size());
    }
    @Test void paymentProofIsGatedByAnInvoiceInTheSameRequest() {
        var repo=new Gs1Repository(directory.resolve("fixture.db"));var service=new Gs1LettersService(repo,(a,l)->{throw new AssertionError("No send without invoice");});
        var request=service.draft("7707083893","Fixture",Gs1LettersService.Kind.RENEWAL,null,"");
        var attachment=new Gs1MailClient.Attachment("proof.pdf","application/pdf","%PDF-fixture".getBytes());
        assertThrows(IllegalStateException.class,()->service.preparePaymentProof(request.inn(),request.id(),attachment));
        assertThrows(SecurityException.class,()->service.preparePaymentProof("7811089030",request.id(),attachment));
    }
    @Test void aFreshProofCannotBypassAnAmbiguousOrSuccessfulDelivery() throws Exception {
        var repo=new Gs1Repository(directory.resolve("fixture.db"));var calls=new AtomicInteger();
        var service=new Gs1LettersService(repo,(a,l)->{calls.incrementAndGet();throw new IOException("Lost SMTP response");});
        var parent=service.draft("7707083893","Fixture",Gs1LettersService.Kind.RENEWAL,null,"");
        repo.addMessage(parent.inn(),parent.id(),"invoice",false,"mail@gs1ru.org","Invoice",true);
        repo.confirmInvoice(parent.inn(),parent.id(),"invoice",new Gs1Repository.PortalInvoice(parent.inn(),"INV-123","1000.00","RUB"));
        var proof=new Gs1MailClient.Attachment("proof.pdf","application/pdf","%PDF-fixture".getBytes());
        var first=service.preparePaymentProof(parent.inn(),parent.id(),proof);
        var second=service.preparePaymentProof(parent.inn(),parent.id(),proof);
        assertThrows(IOException.class,()->service.send(account(),first.inn(),first.id()));
        assertThrows(IllegalStateException.class,()->service.preparePaymentProof(parent.inn(),parent.id(),proof));
        assertThrows(IllegalStateException.class,()->service.send(account(),second.inn(),second.id()));
        byte[] sentCopy=new Gs1MailClient().export(account().from(),service.letter(first));
        assertEquals(Gs1Repository.State.SENT,service.reconcileSent(account(),first.inn(),first.id(),sentCopy));
        assertThrows(IllegalStateException.class,()->service.preparePaymentProof(parent.inn(),parent.id(),proof));
        assertEquals(1,calls.get(),"Recovery must not call SMTP");
    }
    @Test void unrelatedSentCopyCannotResolveAnUnknownSend() throws Exception {
        var repo=new Gs1Repository(directory.resolve("fixture.db"));
        var service=new Gs1LettersService(repo,(a,l)->{throw new IOException("Unknown delivery");});
        var first=service.draft("7707083893","Fixture",Gs1LettersService.Kind.RENEWAL,null,"");
        assertThrows(IOException.class,()->service.send(account(),first.inn(),first.id()));
        var unrelated=service.draft(first.inn(),"Fixture",Gs1LettersService.Kind.RENEWAL,null,"");
        assertThrows(IllegalStateException.class,()->service.send(account(),unrelated.inn(),unrelated.id()));
        byte[] evidence=new Gs1MailClient().export(account().from(),service.letter(unrelated));
        assertThrows(IllegalArgumentException.class,()->service.reconcileSent(account(),first.inn(),first.id(),evidence));
        assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,repo.request(first.inn(),first.id()).state());
    }
    @Test void aFailureBeforeSmtpSendLeavesTheReviewedDraftRetryable() throws Exception {
        var repo=new Gs1Repository(directory.resolve("fixture.db"));var calls=new AtomicInteger();
        var service=new Gs1LettersService(repo,(a,l)->{if(calls.incrementAndGet()==1)throw new Gs1MailClient.NotSentException();});
        var request=service.draft("7707083893","Fixture",Gs1LettersService.Kind.RENEWAL,null,"");
        assertThrows(IOException.class,()->service.send(account(),request.inn(),request.id()));
        assertEquals(Gs1Repository.State.DRAFT,repo.request(request.inn(),request.id()).state());
        service.send(account(),request.inn(),request.id());assertEquals(Gs1Repository.State.SENT,repo.request(request.inn(),request.id()).state());
        assertEquals(2,calls.get());
    }
}
