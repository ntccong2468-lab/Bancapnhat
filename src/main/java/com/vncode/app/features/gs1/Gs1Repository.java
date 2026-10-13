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
    public enum State { DRAFT, SENDING, SENT, SUBMITTED, RECONCILE_REQUIRED, REJECTED, RESOLVED }
    public record Request(String id,String inn,String kind,String recipient,String subject,String body,State state,Instant createdAt) {
        @Override public String toString(){return "Gs1Request[id="+id+", state="+state+"]";}
    }
    public record Message(String id,boolean outgoing,String sender,String body,boolean unread,boolean invoice,Instant createdAt) {}
    public record AttachmentSummary(String messageId,String name,String type,long size) {}
    public record PrefixRequestReview(String inn,String requestId,List<String> gcps,int quantity,String reviewedDigest,Instant sourceCheckedAt){
        public PrefixRequestReview{gcps=List.copyOf(gcps);}
        @Override public String toString(){return "Gs1PrefixRequestReview[redacted]";}
    }
    public record ReminderConsent(String inn,String requestId,String accountDigest,String contentDigest,
                                  Instant approvedAt,boolean enabled,String reminderId){
        @Override public String toString(){return "Gs1ReminderConsent[enabled="+enabled+", attempted="+(reminderId!=null)+"]";}
    }
    /** Operator verification in the official portal, independent of the email's From header. */
    public record PortalInvoice(String inn,String number,String amount,String currency) {
        public PortalInvoice {
            Gs1Membership.requireInn(inn);
            if(number==null||number.isBlank()||number.length()>100||number.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Enter the invoice number from the official portal");
            number=number.strip();
            try {
                var value=new java.math.BigDecimal(amount);
                if(value.signum()<=0||value.precision()>14||value.scale()>2)throw new IllegalArgumentException();
                amount=value.stripTrailingZeros().toPlainString();
            } catch(RuntimeException invalid){throw new IllegalArgumentException("Enter a positive invoice amount");}
            if(!"RUB".equals(currency))throw new IllegalArgumentException("GS1 RUS invoice currency must be RUB");
        }
        @Override public String toString(){return "Gs1PortalInvoice[manually verified]";}
    }
    @FunctionalInterface private interface Connections {Connection open()throws SQLException;}
    private static final Gson PREFIX_JSON=new GsonBuilder().registerTypeAdapter(LocalDate.class,(JsonSerializer<LocalDate>)(value,type,context)->new JsonPrimitive(value.toString())).registerTypeAdapter(LocalDate.class,(JsonDeserializer<LocalDate>)(json,type,context)->LocalDate.parse(json.getAsString())).create();
    private final Connections connections;
    public Gs1Repository(){this.connections=Database::getConnection;initialize();}
    public Gs1Repository(Path file){this.connections=()->DriverManager.getConnection("jdbc:sqlite:"+file);initialize();}
    private void initialize(){
        try(Connection c=connections.open();Statement s=c.createStatement()){
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_membership(inn TEXT PRIMARY KEY,payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_membership_scoped(inn TEXT NOT NULL,environment TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(inn,environment))");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_requests(id TEXT PRIMARY KEY,inn TEXT NOT NULL,kind TEXT NOT NULL,recipient TEXT NOT NULL,subject TEXT NOT NULL,body TEXT NOT NULL,state TEXT NOT NULL,created_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_gs1_requests_inn ON gs1_requests(inn,created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_messages(inn TEXT NOT NULL,request_id TEXT NOT NULL,id TEXT NOT NULL,outgoing INTEGER NOT NULL,sender TEXT NOT NULL,body TEXT NOT NULL,unread INTEGER NOT NULL,invoice INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,PRIMARY KEY(inn,request_id,id))");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_mail_accounts(inn TEXT PRIMARY KEY,payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_attachments(inn TEXT NOT NULL,request_id TEXT NOT NULL,message_id TEXT NOT NULL,name TEXT NOT NULL,type TEXT NOT NULL,content BLOB NOT NULL,PRIMARY KEY(inn,request_id,message_id,name))");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_invoice_verifications(inn TEXT NOT NULL,request_id TEXT NOT NULL,message_id TEXT NOT NULL,number TEXT NOT NULL,amount TEXT NOT NULL,currency TEXT NOT NULL,channel TEXT NOT NULL,verified_at TEXT NOT NULL,PRIMARY KEY(inn,request_id))");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_mail_reconciliations(inn TEXT NOT NULL,request_id TEXT NOT NULL,evidence_digest TEXT NOT NULL,channel TEXT NOT NULL,verified_at TEXT NOT NULL,PRIMARY KEY(inn,request_id))");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_auto_reminder_consents(inn TEXT NOT NULL,request_id TEXT NOT NULL,account_digest TEXT NOT NULL,content_digest TEXT NOT NULL,approved_at TEXT NOT NULL,enabled INTEGER NOT NULL,reminder_id TEXT,PRIMARY KEY(inn,request_id))");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_auto_reminder_checks(inn TEXT NOT NULL,request_id TEXT NOT NULL,checked_seq INTEGER NOT NULL,PRIMARY KEY(inn,request_id))");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_membership_request_resolutions(inn TEXT NOT NULL,request_id TEXT NOT NULL,verified_at TEXT NOT NULL,PRIMARY KEY(inn,request_id))");
            s.execute("CREATE TABLE IF NOT EXISTS gs1_prefix_request_reviews(inn TEXT NOT NULL,request_id TEXT NOT NULL,gcps_json TEXT NOT NULL,quantity INTEGER NOT NULL,reviewed_digest TEXT NOT NULL,source_checked_at TEXT NOT NULL,PRIMARY KEY(inn,request_id))");
        }catch(SQLException error){throw storeError();}
    }
    public Request createRequest(String inn,String kind,String recipient,String subject,String body){
        Gs1Membership.requireInn(inn);String id=UUID.randomUUID().toString();Instant now=Instant.now();
        execute("INSERT INTO gs1_requests VALUES(?,?,?,?,?,?,?,?)",id,inn,kind,recipient,subject,body,State.DRAFT.name(),now.toString());
        return new Request(id,inn,kind,recipient,subject,body,State.DRAFT,now);
    }
    /** The reviewed PDF and letter become one durable draft, including on database failure. */
    public Request createRequestWithAttachments(String inn,String kind,String recipient,String subject,String body,List<Gs1MailClient.Attachment> attachments){
        Gs1Membership.requireInn(inn);var reviewed=List.copyOf(attachments);
        if(reviewed.size()>5)throw new IllegalArgumentException("Too many GS1 attachments");
        String id=UUID.randomUUID().toString();Instant now=Instant.now();
        try(Connection c=connections.open()){
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement("INSERT INTO gs1_requests VALUES(?,?,?,?,?,?,?,?)")){
                    Object[] values={id,inn,kind,recipient,subject,body,State.DRAFT.name(),now.toString()};
                    for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO gs1_attachments VALUES(?,?,?,?,?,?)")){
                    for(var attachment:reviewed){p.setString(1,inn);p.setString(2,id);p.setString(3,"draft");p.setString(4,attachment.name());p.setString(5,attachment.type());p.setBytes(6,attachment.bytes());p.addBatch();}p.executeBatch();
                }
                c.commit();
            }catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
        }catch(SQLException error){throw storeError();}
        return new Request(id,inn,kind,recipient,subject,body,State.DRAFT,now);
    }
    /** Atomic one-draft checkpoint per original request, including competing app windows. */
    public Optional<Request> createReminder(String inn,String parentId,String subject,String body){
        var original=request(inn,parentId);
        String id=UUID.randomUUID().toString(),kind="REMINDER:"+parentId;Instant now=Instant.now();
        int inserted=execute("""
                INSERT INTO gs1_requests(id,inn,kind,recipient,subject,body,state,created_at)
                SELECT ?,?,?,?,?,?,'DRAFT',?
                WHERE NOT EXISTS(SELECT 1 FROM gs1_requests WHERE inn=? AND kind=?)
                """,id,inn,kind,original.recipient(),subject,body,now.toString(),inn,kind);
        return inserted==1?Optional.of(new Request(id,inn,kind,original.recipient(),subject,body,State.DRAFT,now)):Optional.empty();
    }
    public List<Request> requests(String inn){
        Gs1Membership.requireInn(inn);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT r.*,EXISTS(SELECT 1 FROM gs1_membership_request_resolutions resolved WHERE resolved.inn=r.inn AND resolved.request_id=r.id) AS resolved FROM gs1_requests r WHERE r.inn=? ORDER BY r.created_at DESC")){
            p.setString(1,inn);try(ResultSet r=p.executeQuery()){List<Request> values=new ArrayList<>();while(r.next())values.add(readRequest(r));return List.copyOf(values);}
        }catch(SQLException error){throw storeError();}
    }
    /** Exact PDF, letter and reviewed-source metadata are persisted together, never as a partial draft. */
    public Request createReviewedPrefixRequest(Gs1MailClient.Letter letter,PrefixRequestReview review){
        Gs1Membership.requireInn(review.inn());requireDigest(review.reviewedDigest());
        if(!letter.id().equals(review.requestId())||!letter.id().equals(letter.threadId())||!"mail@gs1ru.org".equals(letter.recipient())||letter.attachments().size()!=1)
            throw new IllegalArgumentException("Invalid reviewed prefix request");
        Gs1PrefixRequestModels.Quantity.fromValue(review.quantity());Instant now=Instant.now();
        try(Connection c=connections.open()){
            c.setAutoCommit(false);
            try{
                try(PreparedStatement p=c.prepareStatement("INSERT INTO gs1_requests VALUES(?,?,'MORE_CODES',?,?,?,'DRAFT',?)")){
                    Object[] values={letter.id(),review.inn(),letter.recipient(),letter.subject(),letter.body(),now.toString()};for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();
                }
                var attachment=letter.attachments().getFirst();
                try(PreparedStatement p=c.prepareStatement("INSERT INTO gs1_attachments VALUES(?,?,'draft',?,?,?)")){
                    p.setString(1,review.inn());p.setString(2,letter.id());p.setString(3,attachment.name());p.setString(4,attachment.type());p.setBytes(5,attachment.bytes());p.executeUpdate();
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO gs1_prefix_request_reviews VALUES(?,?,?,?,?,?)")){
                    Object[] values={review.inn(),letter.id(),new Gson().toJson(review.gcps()),review.quantity(),review.reviewedDigest(),review.sourceCheckedAt().toString()};for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();
                }
                c.commit();
            }catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
        }catch(SQLException error){throw storeError();}
        return new Request(letter.id(),review.inn(),"MORE_CODES",letter.recipient(),letter.subject(),letter.body(),State.DRAFT,now);
    }
    public Optional<PrefixRequestReview> prefixRequestReview(String inn,String id){
        request(inn,id);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT * FROM gs1_prefix_request_reviews WHERE inn=? AND request_id=?")){
            p.setString(1,inn);p.setString(2,id);try(ResultSet r=p.executeQuery()){
                if(!r.next())return Optional.empty();
                return Optional.of(new PrefixRequestReview(inn,id,List.of(new Gson().fromJson(r.getString("gcps_json"),String[].class)),r.getInt("quantity"),r.getString("reviewed_digest"),Instant.parse(r.getString("source_checked_at"))));
            }
        }catch(SQLException|RuntimeException invalid){throw storeError();}
    }
    public boolean enableReminderConsent(String inn,String parentId,String accountDigest,String contentDigest,Instant approvedAt){
        var original=request(inn,parentId);
        if(!original.kind().equals("MORE_CODES")||original.state()!=State.SENT)throw new IllegalStateException("Only a confirmed prefix request can enable automatic reminders");
        requireDigest(accountDigest);requireDigest(contentDigest);Objects.requireNonNull(approvedAt);
        return execute("""
                INSERT INTO gs1_auto_reminder_consents VALUES(?,?,?,?,?,1,NULL)
                ON CONFLICT(inn,request_id) DO UPDATE SET account_digest=excluded.account_digest,
                  content_digest=excluded.content_digest,approved_at=excluded.approved_at,enabled=1
                WHERE gs1_auto_reminder_consents.reminder_id IS NULL
                """,inn,parentId,accountDigest,contentDigest,approvedAt.toString())==1;
    }
    /** Revocation is local and remains available after a license expires. */
    public void disableReminderConsent(String inn,String parentId){
        request(inn,parentId);execute("UPDATE gs1_auto_reminder_consents SET enabled=0 WHERE inn=? AND request_id=?",inn,parentId);
    }
    public Optional<ReminderConsent> reminderConsent(String inn,String parentId){
        request(inn,parentId);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT * FROM gs1_auto_reminder_consents WHERE inn=? AND request_id=?")){
            p.setString(1,inn);p.setString(2,parentId);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(readConsent(r)):Optional.empty();}
        }catch(SQLException error){throw storeError();}
    }
    /** Bounded scheduler input; only explicitly opted-in, unconsumed consents are returned. */
    public List<ReminderConsent> pendingReminderConsents(int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid reminder scan limit");
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("""
                SELECT consent.* FROM gs1_auto_reminder_consents consent
                LEFT JOIN gs1_auto_reminder_checks checked ON checked.inn=consent.inn AND checked.request_id=consent.request_id
                WHERE consent.enabled=1 AND consent.reminder_id IS NULL
                ORDER BY COALESCE(checked.checked_seq,0),consent.approved_at LIMIT ?
                """)){
            p.setInt(1,limit);try(ResultSet r=p.executeQuery()){var result=new ArrayList<ReminderConsent>();while(r.next())result.add(readConsent(r));return List.copyOf(result);}
        }catch(SQLException error){throw storeError();}
    }
    /** Persistent fair rotation; wall-clock changes cannot starve later opted-in requests. */
    public void markReminderChecked(String inn,String parentId){
        request(inn,parentId);
        execute("""
                INSERT INTO gs1_auto_reminder_checks(inn,request_id,checked_seq)
                SELECT ?,?,(SELECT COALESCE(MAX(checked_seq),0)+1 FROM gs1_auto_reminder_checks) WHERE 1
                ON CONFLICT(inn,request_id) DO UPDATE SET checked_seq=excluded.checked_seq
                """,inn,parentId);
    }
    /** Consumes one automatic attempt and creates its exact correlated draft in the same transaction. */
    public Optional<Request> prepareAutomaticReminder(String inn,String parentId,String accountDigest,String contentDigest,String subject,String body){
        var original=request(inn,parentId);requireDigest(accountDigest);requireDigest(contentDigest);
        String id=UUID.randomUUID().toString(),kind="REMINDER:"+parentId;Instant now=Instant.now();
        try(Connection c=connections.open()){
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("""
                    UPDATE gs1_auto_reminder_consents SET reminder_id=?
                    WHERE inn=? AND request_id=? AND enabled=1 AND reminder_id IS NULL AND account_digest=? AND content_digest=?
                    AND EXISTS(SELECT 1 FROM gs1_requests WHERE inn=? AND id=? AND kind='MORE_CODES' AND state='SENT')
                    AND NOT EXISTS(SELECT 1 FROM gs1_messages WHERE inn=? AND request_id=? AND outgoing=0)
                    AND NOT EXISTS(SELECT 1 FROM gs1_requests WHERE inn=? AND kind=?)
                    """)){
                int i=1;for(String value:List.of(id,inn,parentId,accountDigest,contentDigest,inn,parentId,inn,parentId,inn,kind))p.setString(i++,value);
                if(p.executeUpdate()!=1){c.rollback();return Optional.empty();}
            }
            try(PreparedStatement p=c.prepareStatement("INSERT INTO gs1_requests VALUES(?,?,?,?,?,?,'DRAFT',?)")){
                int i=1;for(String value:List.of(id,inn,kind,original.recipient(),subject,body,now.toString()))p.setString(i++,value);p.executeUpdate();
            }
            c.commit();return Optional.of(new Request(id,inn,kind,original.recipient(),subject,body,State.DRAFT,now));
        }catch(SQLException error){throw storeError();}
    }
    /** Consent revocation and a newly arrived reply participate in the durable send claim. */
    public boolean claimAutomaticReminder(String inn,String parentId,String id,String accountDigest,String contentDigest){
        request(inn,id);requireDigest(accountDigest);requireDigest(contentDigest);
        return execute("""
                UPDATE gs1_requests SET state='SENDING'
                WHERE inn=? AND id=? AND kind=? AND state='DRAFT'
                AND EXISTS(SELECT 1 FROM gs1_auto_reminder_consents WHERE inn=? AND request_id=? AND enabled=1
                  AND reminder_id=? AND account_digest=? AND content_digest=?)
                AND EXISTS(SELECT 1 FROM gs1_requests parent WHERE parent.inn=? AND parent.id=? AND parent.state='SENT' AND parent.kind='MORE_CODES')
                AND NOT EXISTS(SELECT 1 FROM gs1_messages WHERE inn=? AND request_id=? AND outgoing=0)
                AND NOT EXISTS(SELECT 1 FROM gs1_requests other WHERE other.inn=? AND other.kind=? AND other.id!=?
                  AND other.state IN('SENDING','SENT','SUBMITTED','RECONCILE_REQUIRED'))
                """,inn,id,"REMINDER:"+parentId,inn,parentId,id,accountDigest,contentDigest,inn,parentId,inn,parentId,inn,"REMINDER:"+parentId,id)==1;
    }
    public Request request(String inn,String id){
        Gs1Membership.requireInn(inn);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT r.*,EXISTS(SELECT 1 FROM gs1_membership_request_resolutions resolved WHERE resolved.inn=r.inn AND resolved.request_id=r.id) AS resolved FROM gs1_requests r WHERE r.inn=? AND r.id=?")){
            p.setString(1,inn);p.setString(2,id);try(ResultSet r=p.executeQuery()){if(r.next())return readRequest(r);throw new SecurityException("GS1 request does not belong to this enterprise");}
        }catch(SQLException error){throw storeError();}
    }
    public boolean claimSend(String inn,String id){
        request(inn,id);
        return execute("""
                UPDATE gs1_requests SET state='SENDING'
                WHERE inn=? AND id=? AND state='DRAFT'
                AND NOT EXISTS(
                  SELECT 1 FROM gs1_requests other WHERE other.inn=gs1_requests.inn
                  AND other.kind=gs1_requests.kind AND other.id!=gs1_requests.id
                  AND (other.state IN('SENDING','SUBMITTED','RECONCILE_REQUIRED')
                    OR ((gs1_requests.kind LIKE 'PAYMENT_PROOF:%' OR gs1_requests.kind LIKE 'REMINDER:%'
                      OR gs1_requests.kind IN('JOINING_INVOICE','RESTORE_MEMBERSHIP')) AND other.state='SENT'
                      AND NOT EXISTS(SELECT 1 FROM gs1_membership_request_resolutions resolved WHERE resolved.inn=other.inn AND resolved.request_id=other.id))))
                """,inn,id)==1;
    }
    public boolean hasBlockingOperation(String inn,String kind){
        return requests(inn).stream().anyMatch(r->r.kind().equals(kind)&&(Set.of(State.SENDING,State.SUBMITTED,State.RECONCILE_REQUIRED).contains(r.state())||((kind.startsWith("PAYMENT_PROOF:")||kind.startsWith("REMINDER:")||Set.of("JOINING_INVOICE","RESTORE_MEMBERSHIP").contains(kind))&&r.state()==State.SENT)));
    }
    public void setState(String inn,String id,State state){
        if(state==State.RESOLVED)throw new IllegalArgumentException("Membership resolution requires a sourced active membership assertion");
        request(inn,id);execute("UPDATE gs1_requests SET state=? WHERE inn=? AND id=?",state.name(),inn,id);
    }
    public void releaseUnsent(String inn,String id){request(inn,id);execute("UPDATE gs1_requests SET state='DRAFT' WHERE inn=? AND id=? AND state='SENDING'",inn,id);}
    /** Current official membership resolves a successful request, never an uncertain SMTP delivery. */
    public int completeMembershipRequests(Gs1Membership snapshot,String environment){
        Objects.requireNonNull(snapshot);requireEnvironment(environment);
        Instant now=Instant.now();
        if(!"production".equals(environment)||!Gs1Membership.CATALOG_SOURCE.equals(snapshot.source())
                ||snapshot.stale(now)||snapshot.checkedAt().isAfter(now.plusSeconds(60)))return 0;
        var status=snapshot.status(LocalDate.now(ZoneId.of("Europe/Moscow")));
        if(status!=Gs1Membership.Status.ACTIVE&&status!=Gs1Membership.Status.EXPIRING)return 0;
        return execute("""
                INSERT OR IGNORE INTO gs1_membership_request_resolutions(inn,request_id,verified_at)
                SELECT inn,id,? FROM gs1_requests
                WHERE inn=? AND state='SENT' AND kind IN('JOINING_INVOICE','RESTORE_MEMBERSHIP')
                """,snapshot.checkedAt().toString(),snapshot.inn());
    }
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
    public int unread(String inn){
        Gs1Membership.requireInn(inn);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM gs1_messages m JOIN gs1_requests r ON r.inn=m.inn AND r.id=m.request_id WHERE m.inn=? AND m.unread=1")){
            p.setString(1,inn);try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}
        }catch(SQLException error){throw storeError();}
    }
    public void markReadMessages(String inn,String requestId,List<Message> shown){
        request(inn,requestId);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("UPDATE gs1_messages SET unread=0 WHERE inn=? AND request_id=? AND id=?")){
            for(var message:shown){p.setString(1,inn);p.setString(2,requestId);p.setString(3,message.id());p.addBatch();}p.executeBatch();
        }catch(SQLException error){throw storeError();}
    }
    public void confirmInvoice(String inn,String requestId,String messageId,PortalInvoice verified){
        request(inn,requestId);
        if(verified==null||!inn.equals(verified.inn()))throw new SecurityException("Invoice enterprise differs from this request");
        Message message=messages(inn,requestId).stream().filter(m->m.id().equals(messageId)).findFirst().orElseThrow(()->new SecurityException("Unknown invoice message"));
        if(message.outgoing()||!officialSender(message.sender()))throw new SecurityException("Only a GS1 reply may be confirmed as an invoice");
        var previous=verifiedInvoice(inn,requestId);
        if(previous.isPresent()&&!previous.get().equals(verified))throw new IllegalStateException("An invoice is already bound to this request; use a new request for another invoice");
        execute("INSERT OR IGNORE INTO gs1_invoice_verifications VALUES(?,?,?,?,?,?,?,?)",inn,requestId,messageId,verified.number(),verified.amount(),verified.currency(),"MANUAL_OFFICIAL_PORTAL",Instant.now().toString());
        if(!verifiedInvoice(inn,requestId).orElseThrow().equals(verified))throw new IllegalStateException("Another invoice has already been bound to this request");
        execute("UPDATE gs1_messages SET invoice=1 WHERE inn=? AND request_id=? AND id=?",inn,requestId,messageId);
    }
    public Optional<PortalInvoice> verifiedInvoice(String inn,String id){
        request(inn,id);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT number,amount,currency FROM gs1_invoice_verifications WHERE inn=? AND request_id=? AND channel='MANUAL_OFFICIAL_PORTAL'")){
            p.setString(1,inn);p.setString(2,id);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(new PortalInvoice(inn,r.getString(1),r.getString(2),r.getString(3))):Optional.empty();}
        }catch(SQLException error){throw storeError();}
    }
    public boolean hasConfirmedInvoice(String inn,String id){return verifiedInvoice(inn,id).isPresent();}
    public void recordSentCopy(String inn,String id,String digest){
        request(inn,id);
        if(!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid sent copy digest");
        execute("INSERT OR IGNORE INTO gs1_mail_reconciliations VALUES(?,?,?,?,?)",inn,id,digest,"MANUAL_SENT_COPY",Instant.now().toString());
    }
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
    public List<AttachmentSummary> attachmentSummaries(String inn,String requestId){
        request(inn,requestId);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT message_id,name,type,length(content) FROM gs1_attachments WHERE inn=? AND request_id=? ORDER BY message_id,name")){
            p.setString(1,inn);p.setString(2,requestId);try(ResultSet r=p.executeQuery()){var result=new ArrayList<AttachmentSummary>();while(r.next())result.add(new AttachmentSummary(r.getString(1),r.getString(2),r.getString(3),r.getLong(4)));return List.copyOf(result);}
        }catch(SQLException error){throw storeError();}
    }
    public Gs1MailClient.Attachment attachment(String inn,String requestId,AttachmentSummary selected){
        request(inn,requestId);
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement("SELECT type,content FROM gs1_attachments WHERE inn=? AND request_id=? AND message_id=? AND name=?")){
            p.setString(1,inn);p.setString(2,requestId);p.setString(3,selected.messageId());p.setString(4,selected.name());try(ResultSet r=p.executeQuery()){if(r.next())return new Gs1MailClient.Attachment(selected.name(),r.getString(1),r.getBytes(2));throw new SecurityException("Attachment does not belong to this request");}
        }catch(SQLException error){throw storeError();}
    }
    public static boolean officialSender(String sender){return sender!=null&&Set.of("mail@gs1ru.org","markirovka@gs1ru.org","server@gs1ru.org").contains(sender.toLowerCase(Locale.ROOT));}
    private static String membershipJson(Gs1Membership value){
        JsonObject json=new JsonObject();json.addProperty("inn",value.inn());json.addProperty("name",value.legalName());json.addProperty("member",value.member());json.addProperty("excluded",value.excluded());json.addProperty("expiry",value.expiry()==null?null:value.expiry().toString());json.addProperty("checkedAt",value.checkedAt().toString());json.addProperty("source",value.source());json.add("prefixes",PREFIX_JSON.toJsonTree(value.prefixes()));return json.toString();
    }
    public void saveMembership(Gs1Membership value){execute("INSERT INTO gs1_membership VALUES(?,?) ON CONFLICT(inn) DO UPDATE SET payload=excluded.payload",value.inn(),membershipJson(value));}
    public void saveMembership(Gs1Membership value,String environment){requireEnvironment(environment);execute("INSERT INTO gs1_membership_scoped VALUES(?,?,?) ON CONFLICT(inn,environment) DO UPDATE SET payload=excluded.payload",value.inn(),environment,membershipJson(value));}
    public Optional<Gs1Membership> membership(String inn){return membershipQuery(inn,null);}
    public Optional<Gs1Membership> membership(String inn,String environment){requireEnvironment(environment);return membershipQuery(inn,environment);}
    private static void requireEnvironment(String environment){if(!Set.of("production","sandbox","unsupported").contains(environment))throw new IllegalArgumentException("Invalid service environment");}
    private Optional<Gs1Membership> membershipQuery(String inn,String environment){
        Gs1Membership.requireInn(inn);
        String sql=environment==null?"SELECT payload FROM gs1_membership WHERE inn=?":"SELECT payload FROM gs1_membership_scoped WHERE inn=? AND environment=?";
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,inn);if(environment!=null)p.setString(2,environment);try(ResultSet r=p.executeQuery()){
                if(!r.next())return Optional.empty();JsonObject j=JsonParser.parseString(r.getString(1)).getAsJsonObject();
                if(!inn.equals(j.get("inn").getAsString()))return Optional.empty();
                List<Gs1Membership.Prefix> prefixes=List.of(PREFIX_JSON.fromJson(j.get("prefixes"),Gs1Membership.Prefix[].class));
                return Optional.of(new Gs1Membership(inn,j.get("name").getAsString(),j.get("member").isJsonNull()?null:j.get("member").getAsBoolean(),j.get("excluded").getAsBoolean(),j.get("expiry").isJsonNull()?null:LocalDate.parse(j.get("expiry").getAsString()),prefixes,Instant.parse(j.get("checkedAt").getAsString()),j.get("source").getAsString()));
            }
        }catch(SQLException error){throw storeError();}catch(RuntimeException corrupt){return Optional.empty();}
    }
    private int execute(String sql,Object... values){
        try(Connection c=connections.open();PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);return p.executeUpdate();}
        catch(SQLException error){throw storeError();}
    }
    private static Request readRequest(ResultSet r)throws SQLException{
        var stored=State.valueOf(r.getString("state"));var state=stored==State.SENT&&r.getBoolean("resolved")?State.RESOLVED:stored;
        return new Request(r.getString("id"),r.getString("inn"),r.getString("kind"),r.getString("recipient"),r.getString("subject"),r.getString("body"),state,Instant.parse(r.getString("created_at")));
    }
    private static ReminderConsent readConsent(ResultSet r)throws SQLException{return new ReminderConsent(r.getString("inn"),r.getString("request_id"),r.getString("account_digest"),r.getString("content_digest"),Instant.parse(r.getString("approved_at")),r.getBoolean("enabled"),r.getString("reminder_id"));}
    private static void requireDigest(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid reminder identity digest");}
    private static IllegalStateException storeError(){return new IllegalStateException("GS1 data store unavailable");}
}
