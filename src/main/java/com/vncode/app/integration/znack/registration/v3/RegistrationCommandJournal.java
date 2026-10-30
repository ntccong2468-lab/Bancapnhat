package com.vncode.app.integration.znack.registration.v3;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;

/** V3 dispatch boundary shared by the existing 1.5.7 registration controls.
 * Business review remains with the caller. This journal never grants consent. */
public final class RegistrationCommandJournal {
    private static final String WORKER=workerIdentity(ProcessHandle.current().pid(),()->ProcessHandle.current().info().startInstant());
    private final java.nio.file.Path profile=com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize();
    public record Command(Action action,String resource,String contentHash) {
        public Command { Objects.requireNonNull(action);if(blank(resource)||blank(contentHash))throw new IllegalArgumentException("REGISTRATION_COMMAND_INVALID");contentHash=hash(contentHash); }
        @Override public String toString(){return "RegistrationCommand[redacted]";}
    }
    @FunctionalInterface public interface Send { List<String> send(Runnable dispatched)throws Exception; }
    @FunctionalInterface private interface Work<T>{T apply(Connection connection)throws SQLException;}
    private record Claim(String id,String receipt){}

    static String workerIdentity(long pid,java.util.function.Supplier<Optional<Instant>> start) {
        try {var instant=start.get();if(instant.isPresent())return pid+"@"+instant.get();}
        catch(RuntimeException unavailable) { /* Process metadata is optional on some runtimes. */ }
        return pid+"@unknown:"+UUID.randomUUID();
    }
    /** Missing metadata cannot prove that a live PID belongs to a different worker. */
    static boolean matchesLiveProcess(String recorded,Optional<Instant> current) {
        if(current.isEmpty())return true;
        try{return Instant.parse(recorded).equals(current.get());}
        catch(RuntimeException unavailable){return true;}
    }

    public static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_v3_dispatches(
                  id TEXT PRIMARY KEY,shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  owner_key TEXT NOT NULL,pool_key TEXT NOT NULL,action TEXT NOT NULL,resource TEXT NOT NULL,content_hash TEXT NOT NULL,
                  state TEXT NOT NULL,receipt TEXT NOT NULL DEFAULT '',worker TEXT NOT NULL,
                  attempts INTEGER NOT NULL DEFAULT 1,updated_at TEXT NOT NULL,
                  UNIQUE(owner_key,action,resource,content_hash))
                """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_znack_v3_dispatch_pending ON znack_v3_dispatches(owner_key,state,resource)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_znack_v3_reserved_gtin ON znack_v3_dispatches(substr('00000000000000'||receipt,-14)) WHERE action='ALLOCATE_GTIN' AND state='ACKNOWLEDGED' AND receipt<>''");
            s.execute("CREATE TABLE IF NOT EXISTS znack_v3_allocation_generations(shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,nm_id INTEGER NOT NULL,chrt_id INTEGER NOT NULL,generation TEXT NOT NULL,PRIMARY KEY(shop_id,chrt_id))");
        }
        boolean pool=false;
        try(var q=c.createStatement();var r=q.executeQuery("PRAGMA table_info(znack_v3_dispatches)")){while(r.next())if("pool_key".equals(r.getString("name")))pool=true;}
        if(!pool)try(var q=c.createStatement()){q.execute("ALTER TABLE znack_v3_dispatches ADD COLUMN pool_key TEXT NOT NULL DEFAULT ''");q.execute("UPDATE znack_v3_dispatches SET pool_key=owner_key WHERE pool_key=''");}
    }
    private <T>T transaction(Work<T> work){
        if(!profile.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize()))throw new IllegalStateException("REGISTRATION_PROFILE_CHANGED");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("BEGIN IMMEDIATE");
            try{T value=work.apply(c);s.execute("COMMIT");return value;}
            catch(SQLException|RuntimeException failure){try{s.execute("ROLLBACK");}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
        }catch(SQLException failure){throw new IllegalStateException("REGISTRATION_JOURNAL_UNAVAILABLE",failure);}
    }
    private void recover(Connection c)throws SQLException {
        var ids=new ArrayList<String>();
        try(var s=c.prepareStatement("SELECT id,worker FROM znack_v3_dispatches WHERE state='SENDING' AND worker<>?")){
            s.setString(1,WORKER);try(var r=s.executeQuery()){while(r.next())if(!alive(r.getString(2)))ids.add(r.getString(1));}
        }
        try(var s=c.prepareStatement("UPDATE znack_v3_dispatches SET state='UNKNOWN',updated_at=? WHERE id=? AND state='SENDING'")){
            for(var id:ids){s.setString(1,Instant.now().toString());s.setString(2,id);s.addBatch();}s.executeBatch();
        }
    }
    private static boolean alive(String worker){
        if(worker==null||!worker.contains("@"))return true;
        try{var parts=worker.split("@",2);long pid=Long.parseLong(parts[0]);
            var current=ProcessHandle.current();
            if(pid==current.pid())return matchesLiveProcess(parts[1],current.info().startInstant());
            var process=ProcessHandle.of(pid);
            return process.isPresent()&&process.get().isAlive()&&matchesLiveProcess(parts[1],process.get().info().startInstant());
        }catch(RuntimeException unknown){return true;}
    }
    public List<String> run(Owner owner,List<Command> requested,Send send)throws Exception {return run(owner,owner.key(),requested,send);}
    public List<String> run(Owner owner,String allocationPool,List<Command> requested,Send send)throws Exception {
        if(blank(allocationPool))throw new IllegalArgumentException("REGISTRATION_POOL_REQUIRED");
        var commands=List.copyOf(requested);
        if(commands.isEmpty()||commands.size()>10||commands.stream().map(Command::resource).distinct().count()!=commands.size())throw new IllegalArgumentException("REGISTRATION_COMMAND_BATCH_INVALID");
        var claims=transaction(c->{
            recover(c);var result=new ArrayList<Claim>();
            for(var command:commands){
                // Uncertainty is a property of the resource, not the payload or button used next.
                try(var q=c.prepareStatement("SELECT 1 FROM znack_v3_dispatches WHERE state IN ('SENDING','UNKNOWN') AND ((owner_key=? AND resource=?) OR (?='ALLOCATE_GTIN' AND action='ALLOCATE_GTIN' AND (pool_key=? OR owner_key=? OR pool_key=owner_key)))")){
                    q.setString(1,owner.key());q.setString(2,command.resource());q.setString(3,command.action().name());q.setString(4,allocationPool);q.setString(5,owner.key());
                    try(var r=q.executeQuery()){if(r.next())throw new IllegalStateException(command.action()==Action.ALLOCATE_GTIN?"GTIN_ALLOCATION_READBACK_REQUIRED":"CATALOG_FEED_READBACK_REQUIRED");}
                }
                String id=null,receipt=null;
                try(var q=c.prepareStatement("SELECT id,state,receipt FROM znack_v3_dispatches WHERE owner_key=? AND action=? AND resource=? AND content_hash=?")){
                    bind(q,owner,command);try(var r=q.executeQuery()){if(r.next()){id=r.getString(1);if("OBSERVED".equals(r.getString(2)))throw new IllegalStateException("CATALOG_FEED_READBACK_REQUIRED");if("ACKNOWLEDGED".equals(r.getString(2)))receipt=r.getString(3);}}
                }
                if(receipt==null){
                    if(id==null){id=UUID.randomUUID().toString();
                        try(var p=c.prepareStatement("INSERT INTO znack_v3_dispatches(id,shop_id,owner_key,action,resource,content_hash,state,worker,updated_at,pool_key) VALUES(?,?,?,?,?,?,'SENDING',?,?,?)")){
                            p.setString(1,id);p.setInt(2,owner.shopId());p.setString(3,owner.key());p.setString(4,command.action().name());p.setString(5,command.resource());p.setString(6,command.contentHash());p.setString(7,WORKER);p.setString(8,Instant.now().toString());p.setString(9,allocationPool);p.executeUpdate();}
                    }else try(var p=c.prepareStatement("UPDATE znack_v3_dispatches SET state='SENDING',worker=?,attempts=attempts+1,updated_at=? WHERE id=? AND state='REJECTED'")){
                        p.setString(1,WORKER);p.setString(2,Instant.now().toString());p.setString(3,id);if(p.executeUpdate()!=1)throw new IllegalStateException("CATALOG_FEED_READBACK_REQUIRED");}
                }
                result.add(new Claim(id,receipt));
            }
            long cached=result.stream().filter(claim->claim.receipt()!=null).count();
            if(cached!=0&&cached!=result.size())throw new IllegalStateException("SIGNING_DOCUMENT_CHANGED");
            return List.copyOf(result);
        });
        if(claims.getFirst().receipt()!=null)return claims.stream().map(Claim::receipt).toList();
        var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            var receipts=List.copyOf(send.send(()->dispatched.set(true)));
            if(receipts.size()!=claims.size()||receipts.stream().anyMatch(RegistrationJob::blank))throw new IllegalStateException("CATALOG_ACKNOWLEDGEMENT_INVALID");
            finish(claims,"ACKNOWLEDGED",receipts);return receipts;
        }catch(Exception failure){
            boolean rejected=!dispatched.get()||failure instanceof ZnackApiException http&&Set.of(400,401,403,413,429).contains(http.statusCode());
            try{finish(claims,rejected?"REJECTED":"UNKNOWN",Collections.nCopies(claims.size(),""));}catch(RuntimeException storage){failure.addSuppressed(storage);}
            throw failure;
        }
    }
    private void finish(List<Claim> claims,String state,List<String> receipts){
        transaction(c->{try(var p=c.prepareStatement("UPDATE znack_v3_dispatches SET state=?,receipt=?,updated_at=? WHERE id=? AND state='SENDING' AND worker=?")){
            for(int i=0;i<claims.size();i++){p.setString(1,state);p.setString(2,receipts.get(i));p.setString(3,Instant.now().toString());p.setString(4,claims.get(i).id());p.setString(5,WORKER);if(p.executeUpdate()!=1)throw new IllegalStateException("REGISTRATION_COMMAND_CHANGED");}
        }return null;});
    }
    /** Call only after exact identity/content have been independently read from Catalog. */
    public boolean reconcile(Owner owner,Command command,String verifiedReceipt){
        if(blank(verifiedReceipt))throw new IllegalArgumentException("CATALOG_READBACK_REQUIRED");
        return transaction(c->{recover(c);try(var p=c.prepareStatement("UPDATE znack_v3_dispatches SET state='ACKNOWLEDGED',receipt=?,updated_at=? WHERE owner_key=? AND action=? AND resource=? AND content_hash=? AND state='UNKNOWN'")){
            p.setString(1,verifiedReceipt);p.setString(2,Instant.now().toString());p.setString(3,owner.key());p.setString(4,command.action().name());p.setString(5,command.resource());p.setString(6,command.contentHash());return p.executeUpdate()==1;
        }});
    }
    /** An authoritative rejected response belongs to the exact acknowledged feed, not a timeout. */
    public boolean reject(Owner owner,Command command,String rejectedReceipt){
        if(command.action()!=Action.SUBMIT||blank(rejectedReceipt))return false;
        return transaction(c->{try(var p=c.prepareStatement("UPDATE znack_v3_dispatches SET state='REJECTED',updated_at=? WHERE owner_key=? AND action=? AND resource=? AND content_hash=? AND state='ACKNOWLEDGED' AND receipt=?")){
            p.setString(1,Instant.now().toString());p.setString(2,owner.key());p.setString(3,command.action().name());p.setString(4,command.resource());p.setString(5,command.contentHash());p.setString(6,rejectedReceipt);return p.executeUpdate()==1;
        }});
    }
    /** Exact card/content readback can resolve a lost feed receipt without inventing a feed ID. */
    public boolean observe(Owner owner,Command command){
        return transaction(c->{recover(c);try(var p=c.prepareStatement("UPDATE znack_v3_dispatches SET state='OBSERVED',updated_at=? WHERE owner_key=? AND action=? AND resource=? AND content_hash=? AND state='UNKNOWN'")){
            p.setString(1,Instant.now().toString());p.setString(2,owner.key());p.setString(3,command.action().name());p.setString(4,command.resource());p.setString(5,command.contentHash());return p.executeUpdate()==1;
        }});
    }
    public String allocationGeneration(int shopId,long nmId,long chrtId){
        return transaction(c->{
            try(var p=c.prepareStatement("INSERT OR IGNORE INTO znack_v3_allocation_generations VALUES(?,?,?,?)")){
                p.setInt(1,shopId);p.setLong(2,nmId);p.setLong(3,chrtId);p.setString(4,UUID.randomUUID().toString());p.executeUpdate();
            }
            try(var p=c.prepareStatement("SELECT nm_id,generation FROM znack_v3_allocation_generations WHERE shop_id=? AND chrt_id=?")){
                p.setInt(1,shopId);p.setLong(2,chrtId);try(var row=p.executeQuery()){if(!row.next()||row.getLong(1)!=nmId)throw new SecurityException("REGISTRATION_CHANGED");return row.getString(2);}
            }
        });
    }
    /** Must be called inside the same transaction that archives an explicitly replaced identity. */
    public static void advanceGeneration(Connection c,int shopId,long nmId,long chrtId)throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO znack_v3_allocation_generations VALUES(?,?,?,?) ON CONFLICT(shop_id,chrt_id) DO UPDATE SET nm_id=excluded.nm_id,generation=excluded.generation")){
            p.setInt(1,shopId);p.setLong(2,nmId);p.setLong(3,chrtId);p.setString(4,UUID.randomUUID().toString());p.executeUpdate();
        }
    }
    private static void bind(PreparedStatement p,Owner owner,Command command)throws SQLException {
        p.setString(1,owner.key());p.setString(2,command.action().name());p.setString(3,command.resource());p.setString(4,command.contentHash());
    }
}
