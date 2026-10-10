package com.vncode.app.features.gs1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class Gs1RepositoryTest {
    @TempDir Path directory;
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
}
