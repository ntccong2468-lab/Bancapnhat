package com.vncode.app.features.gs1;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.gs1.Gs1MailAccount;
import com.vncode.app.integration.gs1.Gs1MailClient;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Additive GS1 state. Every access is scoped by verified enterprise INN. */
public final class Gs1Repository {
    public enum State { DRAFT, SENDING, SENT, SUBMITTED, RECONCILE_REQUIRED, REJECTED }
    public record Request(String id,String inn,String kind,String recipient,String subject,String body,State state,Instant createdAt) {
        @Override public String toString(){return "Gs1Request[id="+id+", state="+state+"]";}
    }
    public record Message(String id,boolean outgoing,String sender,String body,boolean unread,boolean invoice,Instant createdAt) {}
    @FunctionalInterface private interface Connections {Connection open()throws SQLException;}
    private final Connections connections;
    public Gs1Repository(){this.connections=Database::getConnection;initialize();}
    public Gs1Repository(Path file){this.connections=()->DriverManager.getConnection("jdbc:sqlite:"+file);initialize();}
    private void initialize(){
        try(Connection c=connections.open();Statement s=c.createStatement()){
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_membership(inn TEXT PRIMARY KEY,payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_requests(id TEXT PRIMARY KEY,inn TEXT NOT NULL,kind TEXT NOT NULL,recipient TEXT NOT NULL,subject TEXT NOT NULL,body TEXT NOT NULL,state TEXT NOT NULL,created_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_gs1_requests_inn ON gs1_requests(inn,created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_messages(inn TEXT NOT NULL,request_id TEXT NOT NULL,id TEXT NOT NULL,outgoing INTEGER NOT NULL,sender TEXT NOT NULL,body TEXT NOT NULL,unread INTEGER NOT NULL,invoice INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,PRIMARY KEY(inn,request_id,id))");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_mail_accounts(inn TEXT PRIMARY KEY,payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_attachments(inn TEXT NOT NULL,request_id TEXT NOT NULL,message_id TEXT NOT NULL,name TEXT NOT NULL,type TEXT NOT NULL,content BLOB NOT NULL,PRIMARY KEY(inn,request_id,message_id,name))");
        }catch(SQLException error){throw storeError();}
    }
    public Request createRequest(String inn,String kind,String recipient,String subject,String body){
        Gs1Membership.requireInn(inn);String id=UUID.randomUUID().toString();Instant now=Instant.now();
        execute("INSERT INTO gs1_requests VALUES(?,?,?,?,?,?,?,?)",id,inn,kind,recipient,subject,body,State.DRAFT.name(),now.toString());
        return new Request(id,inn,kind,recipient,subject,body,State.DRAFT,now);
    }
    public List<Request> requests(String inn){
        Gs1Membership.requireInn(inn);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT * FROM gs1_requests WHERE inn=? ORDER BY created_at DESC")){
            p.setString(1,inn);try(ResultSet r=p.executeQuery()){List<Request> values=new ArrayList<>();while(r.next())values.add(readRequest(r));return List.copyOf(values);}
        }catch(SQLException error){throw storeError();}
    }
    public Request request(String inn,String id){return requests(inn).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->new SecurityException("GS1 request does not belong to this enterprise"));}
    public boolean claimSend(String inn,String id){
        request(inn,id);
        return execute("""
                UPDATE gs1_requests SET state='SENDING'
                WHERE inn=? AND id=? AND state='DRAFT'
                AND (kind!='JOIN' OR NOT EXISTS(
                  SELECT 1 FROM gs1_requests other WHERE other.inn=gs1_requests.inn
                  AND other.kind='JOIN' AND other.id!=gs1_requests.id
                  AND other.state IN('SENDING','SUBMITTED','RECONCILE_REQUIRED')))
                """,inn,id)==1;
    }
    public void setState(String inn,String id,State state){request(inn,id);execute("UPDATE gs1_requests SET state=? WHERE inn=? AND id=?",state.name(),inn,id);}
    public void addMessage(String inn,String requestId,String id,boolean outgoing,String sender,String body,boolean unread){
        request(inn,requestId);
        execute("INSERT OR IGNORE INTO gs1_messages VALUES(?,?,?,?,?,?,?,0,?)",inn,requestId,id,outgoing?1:0,sender,body,unread?1:0,Instant.now().toString());
    }
    public List<Message> messages(String inn,String requestId){
        request(inn,requestId);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT * FROM gs1_messages WHERE inn=? AND request_id=? ORDER BY created_at,id")){
            p.setString(1,inn);p.setString(2,requestId);
            try(ResultSet r=p.executeQuery()){List<Message> result=new ArrayList<>();while(r.next())result.add(new Message(r.getString("id"),r.getBoolean("outgoing"),r.getString("sender"),r.getString("body"),r.getBoolean("unread"),r.getBoolean("invoice"),Instant.parse(r.getString("created_at"))));return List.copyOf(result);}
        }catch(SQLException error){throw storeError();}
    }
    public void markRead(String inn,String id){request(inn,id);execute("UPDATE gs1_messages SET unread=0 WHERE inn=? AND request_id=?",inn,id);}
    public int unread(String inn){return requests(inn).stream().mapToInt(r->(int)messages(inn,r.id()).stream().filter(Message::unread).count()).sum();}
    public void confirmInvoice(String inn,String requestId,String messageId){
        request(inn,requestId);
        Message message=messages(inn,requestId).stream().filter(m->m.id().equals(messageId)).findFirst().orElseThrow(()->new SecurityException("Unknown invoice message"));
        if(message.outgoing()||!officialSender(message.sender()))throw new SecurityException("Only a GS1 reply may be confirmed as an invoice");
        execute("UPDATE gs1_messages SET invoice=1 WHERE inn=? AND request_id=? AND id=?",inn,requestId,messageId);
    }
    public boolean hasConfirmedInvoice(String inn,String id){return messages(inn,id).stream().anyMatch(m->m.invoice()&&!m.outgoing());}
    public void saveMailAccount(String inn,Gs1MailAccount account){
        Gs1Membership.requireInn(inn);execute("INSERT INTO gs1_mail_accounts VALUES(?,?) ON CONFLICT(inn) DO UPDATE SET payload=excluded.payload",inn,new Gson().toJson(account));
    }
    public Optional<Gs1MailAccount> mailAccount(String inn){
        Gs1Membership.requireInn(inn);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT payload FROM gs1_mail_accounts WHERE inn=?")){
            p.setString(1,inn);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(new Gson().fromJson(r.getString(1),Gs1MailAccount.class)):Optional.empty();}
        }catch(SQLException error){throw storeError();}catch(RuntimeException invalid){return Optional.empty();}
    }
    public void addAttachment(String inn,String requestId,String messageId,Gs1MailClient.Attachment attachment){
        request(inn,requestId);
        execute("INSERT OR IGNORE INTO gs1_attachments VALUES(?,?,?,?,?,?)",inn,requestId,messageId,attachment.name(),attachment.type(),attachment.bytes());
    }
    public List<Gs1MailClient.Attachment> attachments(String inn,String requestId,String messageId){
        request(inn,requestId);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT name,type,content FROM gs1_attachments WHERE inn=? AND request_id=? AND message_id=?")){
            p.setString(1,inn);p.setString(2,requestId);p.setString(3,messageId);
            try(ResultSet r=p.executeQuery()){List<Gs1MailClient.Attachment> result=new ArrayList<>();while(r.next())result.add(new Gs1MailClient.Attachment(r.getString(1),r.getString(2),r.getBytes(3)));return List.copyOf(result);}
        }catch(SQLException error){throw storeError();}
    }
    public static boolean officialSender(String sender){return sender!=null&&Set.of("mail@gs1ru.org","markirovka@gs1ru.org","server@gs1ru.org").contains(sender.toLowerCase(Locale.ROOT));}
    public void saveMembership(Gs1Membership value){
        JsonObject json=new JsonObject();json.addProperty("inn",value.inn());json.addProperty("name",value.legalName());json.addProperty("member",value.member());json.addProperty("excluded",value.excluded());json.addProperty("expiry",value.expiry()==null?null:value.expiry().toString());json.addProperty("checkedAt",value.checkedAt().toString());json.addProperty("source",value.source());json.add("prefixes",new Gson().toJsonTree(value.prefixes()));
        execute("INSERT INTO gs1_membership VALUES(?,?) ON CONFLICT(inn) DO UPDATE SET payload=excluded.payload",value.inn(),json.toString());
    }
    public Optional<Gs1Membership> membership(String inn){
        Gs1Membership.requireInn(inn);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT payload FROM gs1_membership WHERE inn=?")){
            p.setString(1,inn);try(ResultSet r=p.executeQuery()){
                if(!r.next())return Optional.empty();JsonObject j=JsonParser.parseString(r.getString(1)).getAsJsonObject();
                List<Gs1Membership.Prefix> prefixes=List.of(new Gson().fromJson(j.get("prefixes"),Gs1Membership.Prefix[].class));
                return Optional.of(new Gs1Membership(inn,j.get("name").getAsString(),j.get("member").isJsonNull()?null:j.get("member").getAsBoolean(),j.get("excluded").getAsBoolean(),j.get("expiry").isJsonNull()?null:LocalDate.parse(j.get("expiry").getAsString()),prefixes,Instant.parse(j.get("checkedAt").getAsString()),j.get("source").getAsString()));
            }
        }catch(SQLException error){throw storeError();}catch(RuntimeException corrupt){return Optional.empty();}
    }
    private int execute(String sql,Object... values){
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);return p.executeUpdate();}
        catch(SQLException error){throw storeError();}
    }
    private static Request readRequest(ResultSet r)throws SQLException{return new Request(r.getString("id"),r.getString("inn"),r.getString("kind"),r.getString("recipient"),r.getString("subject"),r.getString("body"),State.valueOf(r.getString("state")),Instant.parse(r.getString("created_at")));}
    private static IllegalStateException storeError(){return new IllegalStateException("GS1 data store unavailable");}
}
