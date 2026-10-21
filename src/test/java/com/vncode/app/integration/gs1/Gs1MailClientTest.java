package com.vncode.app.integration.gs1;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Gs1MailClientTest {
    @Test void mailAccountRejectsHeaderInjectionAndUnprotectedPassword() {
        assertThrows(IllegalArgumentException.class,()->new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"user","a@fixture.ru\r\nBcc: x@y.ru","dpapi:blob"));
        assertThrows(IllegalArgumentException.class,()->new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"user","a@fixture.ru","plaintext"));
        var account=new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"user","a@fixture.ru","dpapi:blob");
        assertFalse(account.toString().contains("blob"));
    }
    @Test void requestsAreCorrelatedToOwnedThreadAndOfficialSender() {
        String id="7f5c8c7e-87de-4475-bf85-146226111bdd";
        assertTrue(Gs1MailClient.matchesReply("mail@gs1ru.org","Re: [VN:"+id+"] renewal","","7707083893",id));
        assertFalse(Gs1MailClient.matchesReply("attacker@gs1ru.org.example","Re: [VN:"+id+"] renewal","","7707083893",id));
        assertFalse(Gs1MailClient.matchesReply("mail@gs1ru.org","Unrelated","","7707083893",id));
        assertFalse(Gs1MailClient.matchesReply("mail@gs1ru.org","Re: [VN:"+id+"] renewal","ИНН: 7811089030","7707083893",id));
    }
    @Test void attachmentsRejectExecutablesAndOversizedContent() {
        assertThrows(IllegalArgumentException.class,()->new Gs1MailClient.Attachment("receipt.exe","application/octet-stream",new byte[]{77,90}));
        assertThrows(IllegalArgumentException.class,()->new Gs1MailClient.Attachment("receipt.pdf","application/pdf",new byte[10*1024*1024+1]));
        assertThrows(IllegalArgumentException.class,()->new Gs1MailClient.Attachment("receipt.pdf","application/pdf",new byte[]{1,2,3}));
        var attachment=new Gs1MailClient.Attachment("receipt.pdf","application/pdf","%PDF-fixture".getBytes());
        var bytes=attachment.bytes();bytes[0]=0;
        assertEquals('%',attachment.bytes()[0]);
    }
}
