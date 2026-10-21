package com.vncode.app.integration.gs1;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.SearchTerm;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1MimeMailboxTest {
    private static final String ID="7f5c8c7e-87de-4475-bf85-146226111bdd";
    private MimeMessage message(String body,String charset)throws Exception{
        var message=new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("mail@gs1ru.org"));message.setSubject("Re: [VN:"+ID+"] invoice","UTF-8");
        message.setText(body,charset);message.saveChanges();
        var bytes=new ByteArrayOutputStream();message.writeTo(bytes);
        return new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(bytes.toByteArray()));
    }
    private String decodedBody(MimeMessage message)throws Exception{
        var method=Gs1MailClient.class.getDeclaredMethod("readPart",Part.class,StringBuilder.class,List.class,int.class);method.setAccessible(true);
        StringBuilder result=new StringBuilder();method.invoke(null,message,result,new ArrayList<>(),0);return result.toString();
    }
    @Test void windows1251AndKoi8RussianRepliesStayReadable()throws Exception{
        for(String charset:List.of("windows-1251","KOI8-R"))assertEquals("Счет на оплату\nИНН: 7707083893",decodedBody(message("Счет на оплату\nИНН: 7707083893",charset)).stripTrailing());
    }
    @Test void aForeignInnInWindows1251MustStillBeRejected()throws Exception{
        String decoded=decodedBody(message("Счет на оплату\nИНН: 7811089030","windows-1251"));
        assertFalse(Gs1MailClient.matchesReply("mail@gs1ru.org","[VN:"+ID+"]",decoded,"7707083893",ID));
    }
    @Test void olderRepliesAreFoundBeyondTheLast250UnrelatedMessages()throws Exception{
        List<Message> messages=new ArrayList<>();messages.add(message("Old invoice, ИНН: 7707083893","UTF-8"));
        for(int i=0;i<300;i++){var unrelated=message("Unrelated business mail","UTF-8");unrelated.setSubject("Unrelated "+i);messages.add(unrelated);}
        var mailbox=new MemoryFolder(messages);var replies=new Gs1MailClient().readFolder(mailbox,"7707083893",Set.of(ID));
        assertEquals(1,replies.size());assertTrue(replies.getFirst().body().contains("Old invoice"));assertEquals(1,mailbox.searches);
    }
    @Test void candidateBatchesDoNotTruncateAThreadOrTrustForgedAuthenticationHeaders()throws Exception{
        List<Message> messages=new ArrayList<>();
        for(int i=0;i<111;i++){var message=message("Reply "+i+", ИНН: 7707083893","UTF-8");message.setHeader("Authentication-Results","imap.fixture; dkim=pass header.d=gs1ru.org");messages.add(message);}
        var mailbox=new MemoryFolder(messages);var replies=new Gs1MailClient().readFolder(mailbox,"7707083893",Set.of(ID));
        assertEquals(111,replies.size());assertTrue(mailbox.maxBatch<=50);assertEquals(3,mailbox.fetches);
        assertTrue(replies.stream().noneMatch(Gs1MailClient.Reply::senderAuthenticated));
    }
    private static final class MemoryFolder extends Folder {
        private final List<Message> messages;int searches,fetches,maxBatch;
        MemoryFolder(List<Message> messages){super(new Store(Session.getInstance(new Properties()),null){
            @Override public Folder getDefaultFolder(){return null;}@Override public Folder getFolder(String name){return null;}@Override public Folder getFolder(URLName name){return null;}
        });this.messages=messages;mode=READ_ONLY;}
        @Override public Message[] search(SearchTerm term)throws MessagingException{searches++;return super.search(term,messages.toArray(Message[]::new));}
        @Override public void fetch(Message[] batch,FetchProfile profile){fetches++;maxBatch=Math.max(maxBatch,batch.length);}
        @Override public String getName(){return "INBOX";}@Override public String getFullName(){return "INBOX";}@Override public Folder getParent(){return null;}
        @Override public boolean exists(){return true;}@Override public Folder[] list(String pattern){return new Folder[0];}@Override public char getSeparator(){return '/';}
        @Override public int getType(){return HOLDS_MESSAGES;}@Override public boolean create(int type){return false;}@Override public boolean hasNewMessages(){return false;}
        @Override public Folder getFolder(String name){return null;}@Override public boolean delete(boolean recurse){return false;}@Override public boolean renameTo(Folder folder){return false;}
        @Override public void open(int mode){this.mode=mode;}@Override public void close(boolean expunge){}@Override public boolean isOpen(){return true;}
        @Override public Flags getPermanentFlags(){return new Flags();}@Override public int getMessageCount(){return messages.size();}@Override public Message getMessage(int index){return messages.get(index-1);}
        @Override public void appendMessages(Message[] values){throw new AssertionError("Inbox is read-only");}@Override public Message[] expunge(){throw new AssertionError("Inbox is read-only");}
    }
}
