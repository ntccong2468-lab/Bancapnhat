package com.vncode.app.integration.gs1;

import com.vncode.app.features.gs1.Gs1Repository;
import com.vncode.app.shared.WindowsSecretProtector;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.*;
import jakarta.activation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.*;

public final class Gs1MailClient {
    public static final int MAX_ATTACHMENT=10*1024*1024;
    public record Attachment(String name,String type,byte[] bytes){
        public Attachment {
            if(name==null||name.isBlank()||name.length()>200||name.matches("(?s).*[\\r\\n/\\\\].*"))throw new IllegalArgumentException("Invalid attachment name");
            if(bytes==null||bytes.length>MAX_ATTACHMENT||!validContent(type,bytes))throw new IllegalArgumentException("Only PDF, PNG and JPEG documents up to 10 MB are allowed");
            bytes=bytes.clone();
        }
        @Override public byte[] bytes(){return bytes.clone();}
        @Override public String toString(){return "Gs1Attachment["+type+", "+bytes.length+" bytes]";}
    }
    public record Letter(String id,String threadId,String recipient,String subject,String body,List<Attachment> attachments){
        public Letter{
            UUID.fromString(id);UUID.fromString(threadId);
            if(!Gs1Repository.officialSender(recipient))throw new IllegalArgumentException("Choose an official GS1 recipient");
            if(subject==null||subject.isBlank()||subject.length()>500||subject.matches("(?s).*[\\r\\n].*"))throw new IllegalArgumentException("Invalid mail subject");
            if(body==null||body.length()>100000)throw new IllegalArgumentException("Mail body is too large");
            attachments=List.copyOf(attachments);if(attachments.size()>5)throw new IllegalArgumentException("Too many documents");
        }
        public String messageId(){return "<"+id+".vncode@vncode.local>";}
        public String taggedSubject(){return "[VN:"+threadId+"] "+subject;}
    }
    /** No arbitrary IMAP provider establishes sender authentication merely from a From/header. */
    public record Reply(String requestId,String id,String sender,String body,List<Attachment> attachments){public boolean senderAuthenticated(){return false;}}
    public static final class NotSentException extends IOException {
        public NotSentException(){super("GS1 mail was not sent; check TLS account settings");}
    }
    public void send(Gs1MailAccount account,Letter letter)throws IOException{
        boolean started=false,accepted=false;
        try{
            Session session=Session.getInstance(properties());
            MimeMessage message=message(session,account.from(),letter);
            try(Transport transport=session.getTransport("smtps")){
                transport.connect(account.smtpHost(),account.smtpPort(),account.username(),new WindowsSecretProtector().reveal(account.protectedPassword()));
                started=true;
                transport.sendMessage(message,message.getAllRecipients());
                accepted=true;
            }
        }catch(MessagingException|IOException|RuntimeException error){
            if(accepted)return; // A disconnect failure cannot undo the SMTP acceptance already received.
            if(!started)throw new NotSentException();
            throw new IOException("GS1 mail delivery could not be confirmed; check the mailbox before sending again");
        }
    }
    public byte[] export(String from,Letter letter)throws IOException{
        try{var out=new ByteArrayOutputStream();message(Session.getInstance(properties()),from,letter).writeTo(out);return out.toByteArray();}
        catch(MessagingException error){throw new IOException("Could not export the GS1 letter");}
    }
    private static MimeMessage message(Session session,String from,Letter letter)throws MessagingException{
        MimeMessage message=new MimeMessage(session){@Override protected void updateMessageID()throws MessagingException{setHeader("Message-ID",letter.messageId());}};
        message.setFrom(new InternetAddress(from,true));message.setRecipient(Message.RecipientType.TO,new InternetAddress(letter.recipient(),true));
        message.setSubject(letter.taggedSubject(),"UTF-8");message.setSentDate(new Date());
        if(!letter.id().equals(letter.threadId())){message.setHeader("In-Reply-To","<"+letter.threadId()+".vncode@vncode.local>");message.setHeader("References","<"+letter.threadId()+".vncode@vncode.local>");}
        MimeMultipart multipart=new MimeMultipart();MimeBodyPart text=new MimeBodyPart();text.setText(letter.body(),"UTF-8");multipart.addBodyPart(text);
        for(Attachment attachment:letter.attachments()){
            MimeBodyPart part=new MimeBodyPart();part.setDataHandler(new DataHandler(new jakarta.mail.util.ByteArrayDataSource(attachment.bytes(),attachment.type())));part.setFileName(attachment.name());part.setDisposition(Part.ATTACHMENT);multipart.addBodyPart(part);
        }
        message.setContent(multipart);message.saveChanges();return message;
    }
    public List<Reply> read(Gs1MailAccount account,String inn,Set<String> requestIds)throws IOException{
        Gs1Membership.requireInn(inn);for(String id:requestIds)UUID.fromString(id);
        if(requestIds.isEmpty())return List.of();
        Folder folder=null;
        try(Store store=Session.getInstance(properties()).getStore("imaps")){
            store.connect(account.imapHost(),account.imapPort(),account.username(),new WindowsSecretProtector().reveal(account.protectedPassword()));
            folder=store.getFolder("INBOX");folder.open(Folder.READ_ONLY);
            return readFolder(folder,inn,requestIds);
        }catch(MessagingException|RuntimeException error){throw new IOException("GS1 inbox could not be read; check TLS account settings");}
        finally{if(folder!=null&&folder.isOpen())try{folder.close(false);}catch(MessagingException ignored){}}
    }
    /** Search the entire mailbox for owned threads, using bounded query and MIME batches. */
    List<Reply> readFolder(Folder folder,String inn,Set<String> requestIds)throws MessagingException,IOException{
        Gs1Membership.requireInn(inn);List<String> ids=requestIds.stream().sorted().toList();ids.forEach(UUID::fromString);
        Map<String,Reply> replies=new LinkedHashMap<>();
        for(int offset=0;offset<ids.size();offset+=25){
            List<SearchTerm> threads=new ArrayList<>();
            for(String id:ids.subList(offset,Math.min(ids.size(),offset+25))){
                threads.add(new SubjectTerm("[VN:"+id+"]"));threads.add(new HeaderTerm("References","<"+id+".vncode@vncode.local>"));threads.add(new HeaderTerm("In-Reply-To","<"+id+".vncode@vncode.local>"));
            }
            SearchTerm sender=new OrTerm(new SearchTerm[]{new FromStringTerm("mail@gs1ru.org"),new FromStringTerm("markirovka@gs1ru.org"),new FromStringTerm("server@gs1ru.org")});
            Message[] candidates=folder.search(new AndTerm(sender,new OrTerm(threads.toArray(SearchTerm[]::new))));
            for(int start=0;start<candidates.length;start+=50){
                Message[] batch=Arrays.copyOfRange(candidates,start,Math.min(candidates.length,start+50));
                FetchProfile profile=new FetchProfile();profile.add(FetchProfile.Item.ENVELOPE);profile.add(FetchProfile.Item.SIZE);folder.fetch(batch,profile);
                for(Message message:batch){
                    Reply reply=parseReply(message,inn,requestIds);
                    if(reply!=null)replies.putIfAbsent(reply.requestId()+":"+reply.id(),reply);
                }
            }
        }
        return List.copyOf(replies.values());
    }
    Reply parseReply(Message message,String inn,Set<String> requestIds)throws MessagingException,IOException {
                if(message.getSize()>15*1024*1024)return null;
                Address[] from=message.getFrom();
                if(from==null||from.length!=1||!(from[0] instanceof InternetAddress address))return null;
                String sender=address.getAddress().toLowerCase(Locale.ROOT);
                if(!Gs1Repository.officialSender(sender))return null;
                String subject=Objects.requireNonNullElse(message.getSubject(),"");
                String references=String.join(" ",Objects.requireNonNullElse(message.getHeader("References"),new String[0]))+" "+String.join(" ",Objects.requireNonNullElse(message.getHeader("In-Reply-To"),new String[0]));
                List<String> targets=requestIds.stream().filter(id->subject.contains("[VN:"+id+"]")||references.contains("<"+id+".vncode@vncode.local>")).toList();
                if(targets.size()!=1)return null;String target=targets.getFirst();
                StringBuilder body=new StringBuilder();List<Attachment> attachments=new ArrayList<>();readPart(message,body,attachments,0);
                if(!matchesReply(sender,subject+" "+references,body.toString(),inn,target))return null;
                String[] identifiers=message.getHeader("Message-ID");
                String id=identifiers!=null&&identifiers.length>0?identifiers[0]:message.getFolder() instanceof UIDFolder uid?"uid:"+uid.getUIDValidity()+":"+uid.getUID(message):null;
                if(id==null||id.isBlank()||id.length()>256)return null;
                return new Reply(target,id,sender,body.toString(),List.copyOf(attachments));
    }
    private static void readPart(Part part,StringBuilder body,List<Attachment> attachments,int depth)throws MessagingException,IOException{
        if(depth>8||body.length()>100000||attachments.size()>=5)return;
        String filename=part.getFileName();
        if(filename!=null||Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())){
            try(var stream=part.getInputStream()){
                byte[] data=stream.readNBytes(MAX_ATTACHMENT+1);
                String type=part.getContentType().split(";",2)[0].strip().toLowerCase(Locale.ROOT);
                String name=filename==null?"document":MimeUtility.decodeText(filename);
                try{attachments.add(new Attachment(name,type,data));}catch(IllegalArgumentException unsupported){/* Unsupported files are not executed or stored. */}
            }
        }else if(part.isMimeType("text/plain")){
            body.append(readText(part,Math.max(0,100000-body.length()))).append('\n');
        }else if(part.isMimeType("multipart/*")){
            Multipart multipart=(Multipart)part.getContent();for(int i=0;i<Math.min(32,multipart.getCount());i++)readPart(multipart.getBodyPart(i),body,attachments,depth+1);
        }else if(part.isMimeType("text/html")&&body.isEmpty()){
            body.append(readText(part,100000).replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>","").replaceAll("<[^>]*>"," "));
        }
    }
    private static String readText(Part part,int limit)throws MessagingException,IOException{
        String declared=new ContentType(part.getContentType()).getParameter("charset");
        Charset charset;
        try{charset=Charset.forName(declared==null?"US-ASCII":MimeUtility.javaCharset(declared));}
        catch(RuntimeException invalid){throw new IOException("Unsupported GS1 mail text encoding");}
        try(var stream=part.getInputStream()){return new String(stream.readNBytes(limit),charset);}
    }
    /** The user must attest this is a copy from Sent; an exported draft is not delivery evidence. */
    public boolean matchesSentEvidence(byte[] evidence,Gs1MailAccount account,Letter letter)throws IOException{
        if(evidence==null||evidence.length>15*1024*1024)throw new IllegalArgumentException("Sent mail copy exceeds the limit");
        try{
            MimeMessage message=new MimeMessage(Session.getInstance(properties()),new ByteArrayInputStream(evidence));
            if(!singleAddress(message.getFrom(),account.from())||!singleAddress(message.getRecipients(Message.RecipientType.TO),letter.recipient())||message.getRecipients(Message.RecipientType.CC)!=null||message.getRecipients(Message.RecipientType.BCC)!=null)return false;
            if(!letter.messageId().equals(message.getMessageID())||!letter.taggedSubject().equals(message.getSubject()))return false;
            StringBuilder body=new StringBuilder();List<Attachment> attachments=new ArrayList<>();readPart(message,body,attachments,0);
            if(!normalizeBody(body.toString()).equals(normalizeBody(letter.body()))||attachments.size()!=letter.attachments().size())return false;
            for(Attachment expected:letter.attachments())if(attachments.stream().noneMatch(actual->expected.name().equals(actual.name())&&expected.type().equals(actual.type())&&java.security.MessageDigest.isEqual(expected.bytes(),actual.bytes())))return false;
            return true;
        }catch(MessagingException|RuntimeException invalid){throw new IOException("Sent mail copy could not be validated");}
    }
    private static boolean singleAddress(Address[] addresses,String expected){return addresses!=null&&addresses.length==1&&addresses[0] instanceof InternetAddress address&&expected.equalsIgnoreCase(address.getAddress());}
    private static String normalizeBody(String value){return value.replace("\r\n","\n").stripTrailing();}
    public static boolean matchesReply(String sender,String subjectOrReferences,String body,String inn,String requestId){
        if(!Gs1Repository.officialSender(sender)||!(subjectOrReferences.contains("[VN:"+requestId+"]")||subjectOrReferences.contains("<"+requestId+".vncode@vncode.local>")))return false;
        Matcher enterprise=Pattern.compile("(?iu)(?:ИНН|INN)\\s*[:=]?\\s*([0-9]{10,12})").matcher(body);
        while(enterprise.find())if(!inn.equals(enterprise.group(1)))return false;
        return true;
    }
    private static boolean validContent(String type,byte[] bytes){
        if("application/pdf".equals(type))return bytes.length>=5&&new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-");
        if("image/png".equals(type))return bytes.length>=8&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
        if("image/jpeg".equals(type))return bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255;
        return false;
    }
    private static Properties properties(){
        Properties p=new Properties();
        for(String protocol:List.of("smtps","imaps")){
            p.setProperty("mail."+protocol+".ssl.enable","true");p.setProperty("mail."+protocol+".ssl.checkserveridentity","true");
            p.setProperty("mail."+protocol+".connectiontimeout","15000");p.setProperty("mail."+protocol+".timeout","30000");p.setProperty("mail."+protocol+".writetimeout","30000");
            p.setProperty("mail."+protocol+".ssl.protocols","TLSv1.2 TLSv1.3");
        }
        p.setProperty("mail.smtps.auth","true");p.setProperty("mail.debug","false");return p;
    }
}
