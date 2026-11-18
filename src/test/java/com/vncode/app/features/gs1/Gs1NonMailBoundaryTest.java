package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Gs1NonMailBoundaryTest {
    @TempDir Path root;
    private static final String INN="7707083893";
    private Gs1MailAccount account(){return new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"user","sender@fixture.ru","dpapi:fixture");}
    private Gs1Repository.Request operation(Gs1Repository repo,String kind){return repo.createRequest(INN,kind,"mail@gs1ru.org","Operator operation","Private reviewed PDF identity");}
    @Test void operatorAndXmlJoinCannotBecomeMailEvenWithAnAllowlistedMailRecipient(){
        var repo=new Gs1Repository(root.resolve("operations.db"));var calls=new AtomicInteger();var letters=new Gs1LettersService(repo,(a,l)->calls.incrementAndGet());
        for(String kind:List.of("OPERATOR_AGREEMENT:"+"a".repeat(64),"JOIN","UNKNOWN_OPERATION")){
            var request=operation(repo,kind);
            assertThrows(IllegalStateException.class,()->letters.send(account(),INN,request.id()));
            assertThrows(IllegalStateException.class,()->letters.letter(request));
            assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,request.id()).state());assertTrue(repo.messages(INN,request.id()).isEmpty());
        }
        assertEquals(0,calls.get());
    }
    @Test void mailReconciliationNeverConsumesAnOperatorSigningCheckpoint(){
        var repo=new Gs1Repository(root.resolve("reconcile.db"));var letters=new Gs1LettersService(repo,(a,l)->fail("No email delivery"));
        var request=operation(repo,"OPERATOR_AGREEMENT:"+"b".repeat(64));repo.setState(INN,request.id(),Gs1Repository.State.RECONCILE_REQUIRED);
        assertThrows(IllegalStateException.class,()->letters.reconcileSent(account(),INN,request.id(),new byte[0]));
        assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,repo.request(INN,request.id()).state());
    }
    @Test void operatorCannotAcquirePaymentProofOrReminderThroughTheMailUi(){
        var repo=new Gs1Repository(root.resolve("proof.db"));var letters=new Gs1LettersService(repo,(a,l)->fail("No email delivery"));
        var request=operation(repo,"OPERATOR_AGREEMENT:"+"c".repeat(64));repo.addMessage(INN,request.id(),"invoice",false,"mail@gs1ru.org","Synthetic invoice",true);
        repo.confirmInvoice(INN,request.id(),"invoice",new Gs1Repository.PortalInvoice(INN,"INV-FIXTURE","1000.00","RUB"));
        assertFalse(letters.canPreparePaymentProof(INN,request.id()));
        assertThrows(IllegalStateException.class,()->letters.preparePaymentProof(INN,request.id(),new Gs1MailClient.Attachment("proof.pdf","application/pdf","%PDF-fixture".getBytes())));
        assertThrows(IllegalStateException.class,()->letters.prepareReminder(INN,request.id(),java.time.Instant.now().plusSeconds(900000),true));
        assertEquals(1,repo.requests(INN).size(),"No mail child may be created for a signing operation");
    }
    @Test void onlyNonMailHistoryReturnsWithoutEvenConsultingMailboxCredentials()throws Exception{
        var repo=new Gs1Repository(root.resolve("inbox.db"));var letters=new Gs1LettersService(repo,(a,l)->fail("No email delivery"));
        operation(repo,"OPERATOR_AGREEMENT:"+"d".repeat(64));operation(repo,"JOIN");
        assertEquals(0,assertDoesNotThrow(()->letters.refresh(null,INN)),"No mail cases means no SMTP/IMAP account or transport is consulted");
    }
    @Test void aMailShapedChildCannotBypassAnOperatorParentsKind(){
        var repo=new Gs1Repository(root.resolve("child.db"));var calls=new AtomicInteger();var letters=new Gs1LettersService(repo,(a,l)->calls.incrementAndGet());
        var request=operation(repo,"OPERATOR_AGREEMENT:"+"e".repeat(64));repo.setState(INN,request.id(),Gs1Repository.State.SENT);
        var child=operation(repo,"REMINDER:"+request.id());
        assertThrows(IllegalStateException.class,()->letters.send(account(),INN,child.id()));assertThrows(IllegalStateException.class,()->letters.letter(child));
        assertEquals(0,calls.get());assertEquals(Gs1Repository.State.DRAFT,repo.request(INN,child.id()).state());
    }
}
