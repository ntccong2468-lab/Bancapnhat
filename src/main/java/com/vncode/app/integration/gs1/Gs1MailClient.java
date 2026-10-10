package com.vncode.app.integration.gs1;

import com.vncode.app.features.gs1.Gs1Repository;
import com.vncode.app.shared.WindowsSecretProtector;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.activation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
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
    public record Reply(String requestId,String id,String sender,String body,List<Attachment> attachments){}
    public void send(Gs1MailAccount account,Letter letter)throws IOException{
        try{
            Session session=Session.getInstance(properties());
            MimeMessage message=message(session,account.from(),letter);
            try(Transport transport=session.getTransport("smtps")){
                transport.connect(account.smtpHost(),account.smtpPort(),account.username(),new WindowsSecretProtector().reveal(account.protectedPassword()));
                transport.sendMessage(message,message.getAllRecipients());
            }
        }catch(MessagingException|RuntimeException error){throw new IOException("GS1 mail delivery could not be confirmed; check the mailbox before sending again");}
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
        List<Reply> replies=new ArrayList<>();Folder folder=null;
        try(Store store=Session.getInstance(properties()).getStore("imaps")){
            store.connect(account.imapHost(),account.imapPort(),account.username(),new WindowsSecretProtector().reveal(account.protectedPassword()));
            folder=store.getFolder("INBOX");folder.open(Folder.READ_ONLY);
            int count=folder.getMessageCount();
            if(count==0)return List.of();
            for(Message message:folder.getMessages(Math.max(1,count-249),count)){
                if(message.getSize()>15*1024*1024)continue;
                Address[] from=message.getFrom();
                if(from==null||from.length!=1||!(from[0] instanceof InternetAddress address))continue;
                String sender=address.getAddress().toLowerCase(Locale.ROOT);
                if(!Gs1Repository.officialSender(sender))continue;
                String subject=Objects.requireNonNullElse(message.getSubject(),"");
                String references=String.join(" ",Objects.requireNonNullElse(message.getHeader("References"),new String[0]))+" "+String.join(" ",Objects.requireNonNullElse(message.getHeader("In-Reply-To"),new String[0]));
                String target=requestIds.stream().filter(id->subject.contains("[VN:"+id+"]")||references.contains("<"+id+".vncode@vncode.local>")).findFirst().orElse(null);
                if(target==null)continue;
                StringBuilder body=new StringBuilder();List<Attachment> attachments=new ArrayList<>();readPart(message,body,attachments,0);
                if(!matchesReply(sender,subject+" "+references,body.toString(),inn,target))continue;
                String[] identifiers=message.getHeader("Message-ID");
                String id=identifiers!=null&&identifiers.length>0?identifiers[0]:"uid:"+((UIDFolder)folder).getUIDValidity()+":"+((UIDFolder)folder).getUID(message);
                if(id.length()>256)continue;
                replies.add(new Reply(target,id,sender,body.toString(),List.copyOf(attachments)));
            }
        }catch(MessagingException|RuntimeException error){throw new IOException("GS1 inbox could not be read; check TLS account settings");}
        finally{if(folder!=null&&folder.isOpen())try{folder.close(false);}catch(MessagingException ignored){}}
        return List.copyOf(replies);
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
            try(var stream=part.getInputStream()){body.append(new String(stream.readNBytes(Math.max(0,100000-body.length())),StandardCharsets.UTF_8)).append('\n');}
        }else if(part.isMimeType("multipart/*")){
            Multipart multipart=(Multipart)part.getContent();for(int i=0;i<Math.min(32,multipart.getCount());i++)readPart(multipart.getBodyPart(i),body,attachments,depth+1);
        }else if(part.isMimeType("text/html")&&body.isEmpty()){
            try(var stream=part.getInputStream()){body.append(new String(stream.readNBytes(100000),StandardCharsets.UTF_8).replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>","").replaceAll("<[^>]*>"," "));}
        }
    }
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
