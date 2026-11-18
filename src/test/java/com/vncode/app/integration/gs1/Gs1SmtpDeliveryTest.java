package com.vncode.app.integration.gs1;

import com.vncode.app.features.gs1.Gs1LettersService;
import com.vncode.app.features.gs1.Gs1Repository;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseRuntime;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Transport-boundary checks only: no DNS, sockets, mail credentials or external delivery. */
class Gs1SmtpDeliveryTest {
    @TempDir Path directory;
    private static final String INN="7707083893",TO="mail@gs1ru.org";
    private static final String ID="7f5c8c7e-87de-4475-bf85-146226111bdd";
    private static Gs1MailAccount account(){return new Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"fixture","sender@fixture.ru","dpapi:fixture");}
    private static Gs1MailClient.Letter letter(){return new Gs1MailClient.Letter(ID,ID,TO,"Fixture","Reviewed letter",List.of());}
    private static InternetAddress address(String value)throws Exception{return new InternetAddress(value,true);}
    private static Address[] recipients(String value)throws Exception{return new Address[]{address(value)};}
    private static MessagingException rejection(String command,int code)throws Exception{
        return new SMTPSendFailedException(command,code,"Fixture negative SMTP acknowledgment",null,null,recipients(TO),null);
    }
    private static Gs1MailClient client(FakeTransport transport){return new Gs1MailClient(session->transport,password->"fixture-password");}

    @Test void rcptRejectionWithOneInvalidRecipientIsKnownUnsent()throws Exception{
        var refused=new SMTPAddressFailedException(address(TO),"RCPT TO:<"+TO+">",550,"Fixture recipient rejected");
        var transport=new FakeTransport(new SendFailedException("Invalid Addresses",refused,null,null,recipients(TO)));
        assertThrows(Gs1MailClient.NotSentException.class,()->client(transport).send(account(),letter()));
        assertEquals(List.of("connect","send","close"),transport.events);
    }
    @Test void mailFromNegativeAcknowledgmentIsKnownUnsent()throws Exception{
        String command="MAIL FROM:<sender@fixture.ru>";
        var detail=new SMTPSenderFailedException(address(account().from()),command,550,"Fixture sender rejected");
        var transport=new FakeTransport(new SMTPSendFailedException(command,550,"Fixture",detail,null,recipients(TO),null));
        assertThrows(Gs1MailClient.NotSentException.class,()->client(transport).send(account(),letter()));
    }
    @Test void rejectedDataCommandIsKnownUnsent()throws Exception{
        var transport=new FakeTransport(rejection("DATA",552));
        assertThrows(Gs1MailClient.NotSentException.class,()->client(transport).send(account(),letter()));
    }
    @Test void explicitNegativeFinalDataAcknowledgmentIsKnownUnsent()throws Exception{
        var transport=new FakeTransport(rejection(".",554));
        assertThrows(Gs1MailClient.NotSentException.class,()->client(transport).send(account(),letter()));
    }
    @Test void lostFinalDataResponseRemainsUnconfirmed()throws Exception{
        assertUnconfirmed(new FakeTransport(rejection(".",-1)));
        assertUnconfirmed(new FakeTransport(new MessagingException("Lost response",new SocketTimeoutException("Fixture timeout"))));
    }
    @Test void exceptionTextAloneCannotProveARejection()throws Exception{
        assertUnconfirmed(new FakeTransport(new SendFailedException("550 rejected",null,null,recipients(TO),null)));
    }
    @Test void unrelatedCommandOrPositiveResponseCannotAuthorizeAnotherSend()throws Exception{
        assertUnconfirmed(new FakeTransport(rejection("RSET",550)));
        assertUnconfirmed(new FakeTransport(rejection(".",250)));
        assertUnconfirmed(new FakeTransport(rejection("QUIT",550)));
    }
    @Test void acceptedOrUnaccountedRecipientsRemainUnconfirmed()throws Exception{
        assertUnconfirmed(new FakeTransport(new SMTPSendFailedException(".",554,"Fixture",null,recipients(TO),recipients(TO),null)));
        assertUnconfirmed(new FakeTransport(new SMTPSendFailedException("DATA",552,"Fixture",null,null,null,null)));
        assertUnconfirmed(new FakeTransport(new SMTPSendFailedException("DATA",552,"Fixture",null,null,recipients("other@fixture.ru"),null)));
    }
    @Test void foreignRecipientOrConflictingNestedFailureCannotAuthorizeRetry()throws Exception{
        var foreign=new SMTPAddressFailedException(address("other@fixture.ru"),"RCPT TO:<other@fixture.ru>",550,"Fixture");
        assertUnconfirmed(new FakeTransport(new SendFailedException("Fixture",foreign,null,null,recipients(TO))));
        var timeout=new SocketTimeoutException("Uncertain earlier command");
        assertUnconfirmed(new FakeTransport(new SMTPSendFailedException("DATA",552,"Fixture",timeout,null,recipients(TO),null)));
    }
    @Test void successfulAcceptanceSurvivesDisconnectFailure()throws Exception{
        var transport=new FakeTransport(null);transport.closeFailure=true;
        assertDoesNotThrow(()->client(transport).send(account(),letter()));
        assertEquals(1,transport.sends);
    }
    @Test void rejectionProofSurvivesDisconnectFailure()throws Exception{
        var transport=new FakeTransport(rejection("DATA",552));transport.closeFailure=true;
        assertThrows(Gs1MailClient.NotSentException.class,()->client(transport).send(account(),letter()));
    }
    @Test void connectionFailureAndOwnerGuardDoNotStartDelivery()throws Exception{
        var transport=new FakeTransport(null);transport.connectFailure=true;
        assertThrows(Gs1MailClient.NotSentException.class,()->client(transport).send(account(),letter()));assertEquals(0,transport.sends);
        var guarded=new FakeTransport(null);
        assertThrows(Gs1MailClient.NotSentException.class,()->client(guarded).send(account(),letter(),()->{guarded.events.add("owner-check");throw new Gs1MailClient.NotSentException();}));
        assertEquals(List.of("connect","owner-check","close"),guarded.events);assertEquals(0,guarded.sends);
    }
    @Test void tlsIdentityAndBoundedTimeoutPropertiesRemainEnabled()throws Exception{
        var transport=new FakeTransport(null);var seen=new Properties();
        var mail=new Gs1MailClient(session->{seen.putAll(session.getProperties());return transport;},password->"fixture");
        mail.send(account(),letter(),()->transport.events.add("owner-check"));
        for(String protocol:List.of("smtps","imaps")){
            assertEquals("true",seen.getProperty("mail."+protocol+".ssl.enable"));
            assertEquals("true",seen.getProperty("mail."+protocol+".ssl.checkserveridentity"));
            assertEquals("TLSv1.2 TLSv1.3",seen.getProperty("mail."+protocol+".ssl.protocols"));
            assertEquals("15000",seen.getProperty("mail."+protocol+".connectiontimeout"));
            assertEquals("30000",seen.getProperty("mail."+protocol+".timeout"));
            assertEquals("30000",seen.getProperty("mail."+protocol+".writetimeout"));
        }
        assertEquals("true",seen.getProperty("mail.smtps.auth"));
        assertEquals("false",seen.getProperty("mail.debug"));
        assertEquals(List.of("connect","owner-check","send","close"),transport.events);
    }
    @Test void authorizationDenialPreventsConnecting()throws Exception{
        var active=LicenseRuntime.service();
        try{
            LicenseRuntime.install(LicenseDomainFixture.denied(directory.resolve("denied")));
            var transport=new FakeTransport(null);
            assertThrows(LicenseDeniedException.class,()->client(transport).send(account(),letter()));
            assertTrue(transport.events.isEmpty());
        }finally{LicenseRuntime.install(active);}
    }
    @Test void confirmedRejectionLeavesDurableDraftRetryable()throws Exception{
        var repository=new Gs1Repository(directory.resolve("rejected.db"));
        var transport=new FakeTransport(rejection("DATA",552));
        var letters=new Gs1LettersService(repository,client(transport)::send);
        var draft=letters.draft(INN,"Fixture",Gs1LettersService.Kind.RENEWAL,null,"");
        assertThrows(IOException.class,()->letters.send(account(),INN,draft.id()));
        assertEquals(Gs1Repository.State.DRAFT,repository.request(INN,draft.id()).state());
        transport.failure=null;letters.send(account(),INN,draft.id());
        assertEquals(Gs1Repository.State.SENT,repository.request(INN,draft.id()).state());assertEquals(2,transport.sends);
    }
    @Test void ambiguousDeliveryKeepsDurableReconciliationAndBlocksResend()throws Exception{
        var repository=new Gs1Repository(directory.resolve("ambiguous.db"));
        var transport=new FakeTransport(new MessagingException("Lost final response",new SocketTimeoutException("Fixture")));
        var letters=new Gs1LettersService(repository,client(transport)::send);
        var draft=letters.draft(INN,"Fixture",Gs1LettersService.Kind.RENEWAL,null,"");
        assertThrows(IOException.class,()->letters.send(account(),INN,draft.id()));
        assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,repository.request(INN,draft.id()).state());
        assertThrows(IllegalStateException.class,()->letters.send(account(),INN,draft.id()));assertEquals(1,transport.sends);
    }
    private static void assertUnconfirmed(FakeTransport transport){
        var failure=assertThrows(IOException.class,()->client(transport).send(account(),letter()));
        assertFalse(failure instanceof Gs1MailClient.NotSentException,"Uncertain delivery cannot make the draft retryable");
        assertEquals(1,transport.sends);
    }
    private static final class FakeTransport extends Transport {
        MessagingException failure;boolean closeFailure,connectFailure;int sends;
        final List<String> events=new ArrayList<>();
        FakeTransport(MessagingException failure){super(Session.getInstance(new Properties()),null);this.failure=failure;}
        @Override public void connect(String host,int port,String user,String password)throws MessagingException{
            events.add("connect");if(connectFailure)throw new AuthenticationFailedException("Fixture denied");
        }
        @Override public void sendMessage(Message message,Address[] addresses)throws MessagingException{
            events.add("send");sends++;assertEquals(1,addresses.length);assertEquals(TO,((InternetAddress)addresses[0]).getAddress());
            if(failure!=null)throw failure;
        }
        @Override public void close()throws MessagingException{events.add("close");if(closeFailure)throw new MessagingException("Fixture disconnect");}
    }
}
