package com.vncode.app.integration.znack.registration.v3;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;

/** Every checkpoint and its event commit together. No network call executes inside a transaction. */
public final class RegistrationStore {
    private static final Gson JSON = new Gson();
    private static final String WORKER=ProcessHandle.current().pid()+"@"+ProcessHandle.current().info().startInstant().orElse(Instant.now());
    private final java.nio.file.Path profile = com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize();
    public record Operation(String id, String jobId, String action, String requestHash, String status, int revision, int attempts) { }
    public record Event(long sequence, String state, String detail, String occurredAt) { }
    public RegistrationStore(){recoverInterrupted();}

    public static void initialize(Connection c) throws SQLException {
        RegistrationCommandJournal.initialize(c);
        try (var s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_v3_jobs(
                  id TEXT PRIMARY KEY,shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  owner_key TEXT NOT NULL,nm_id INTEGER NOT NULL,chrt_id INTEGER NOT NULL,
                  revision INTEGER NOT NULL,document TEXT NOT NULL,
                  UNIQUE(owner_key,nm_id,chrt_id))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_v3_remote_operations(
                  id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES znack_v3_jobs(id) ON DELETE CASCADE,
                  action TEXT NOT NULL,request_hash TEXT NOT NULL,status TEXT NOT NULL,
                  revision INTEGER NOT NULL,started_at TEXT NOT NULL,finished_at TEXT,
                  UNIQUE(job_id,action,request_hash))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_v3_events(
                  sequence INTEGER PRIMARY KEY AUTOINCREMENT,job_id TEXT NOT NULL REFERENCES znack_v3_jobs(id) ON DELETE CASCADE,
                  state TEXT NOT NULL,detail TEXT NOT NULL,occurred_at TEXT NOT NULL)
                """);
            s.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_znack_v3_owner_gtin
                ON znack_v3_jobs(owner_key,json_extract(document,'$.data.gtin'))
                WHERE json_extract(document,'$.data.gtin') IS NOT NULL AND json_extract(document,'$.data.gtin')<>''
                """);
        }
        boolean workerColumn=false,checkpointColumn=false,attemptsColumn=false;
        try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(znack_v3_remote_operations)")){
            while(r.next()){
                if("worker_id".equals(r.getString("name")))workerColumn=true;
                if("checkpoint_json".equals(r.getString("name")))checkpointColumn=true;
                if("attempts".equals(r.getString("name")))attemptsColumn=true;
            }
        }
        if(!workerColumn)try(var s=c.createStatement()){s.execute("ALTER TABLE znack_v3_remote_operations ADD COLUMN worker_id TEXT NOT NULL DEFAULT ''");}
        if(!checkpointColumn)try(var s=c.createStatement()){s.execute("ALTER TABLE znack_v3_remote_operations ADD COLUMN checkpoint_json TEXT NOT NULL DEFAULT ''");}
        if(!attemptsColumn)try(var s=c.createStatement()){s.execute("ALTER TABLE znack_v3_remote_operations ADD COLUMN attempts INTEGER NOT NULL DEFAULT 1");}
    }
    private void recoverInterrupted(){
        transaction(c->{
            var abandoned=new ArrayList<String>();var snapshots=new LinkedHashMap<String,RegistrationJob>();
            try(var s=c.prepareStatement("SELECT o.id,o.worker_id,j.document FROM znack_v3_remote_operations o JOIN znack_v3_jobs j ON j.id=o.job_id WHERE o.status='SENDING' AND o.worker_id<>?")){
                s.setString(1,WORKER);try(var r=s.executeQuery()){while(r.next())if(!workerAlive(r.getString(2))){abandoned.add(r.getString(1));var job=decode(r.getString(3));snapshots.put(job.id(),job);}}
            }
            try(var p=c.prepareStatement("UPDATE znack_v3_remote_operations SET status='UNKNOWN',finished_at=? WHERE id=? AND status='SENDING'")){
                for(var id:abandoned){p.setString(1,Instant.now().toString());p.setString(2,id);p.addBatch();}p.executeBatch();
            }
            for(var job:snapshots.values())if(job.state().permits(RegistrationState.REMOTE_RESULT_UNKNOWN))
                save(c,job,job.next(RegistrationState.REMOTE_RESULT_UNKNOWN,job.data().withDetail("V3_REMOTE_RESULT_UNKNOWN")));
            return null;
        });
    }
    private static boolean workerAlive(String identity){
        // Unknown legacy markers are not evidence that a current worker has stopped.
        if(identity==null||!identity.contains("@"))return true;
        try{
            int split=identity.indexOf('@');long pid=Long.parseLong(identity.substring(0,split));
            var process=ProcessHandle.of(pid);if(process.isEmpty()||!process.orElseThrow().isAlive())return false;
            return process.orElseThrow().info().startInstant().map(time->time.toString().equals(identity.substring(split+1))).orElse(true);
        }catch(RuntimeException unavailable){return true;}
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) {
        if (!profile.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize())) throw new SecurityException("V3_PROFILE_CHANGED");
        try (var c=Database.getConnection(); var s=c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            try { T result=work.run(c); s.execute("COMMIT"); return result; }
            catch (SQLException|RuntimeException failure) { s.execute("ROLLBACK"); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException("V3_STORAGE_FAILURE",failure); }
    }
    public RegistrationJob create(Owner owner, Product product) {
        return transaction(c->{
            var job=new RegistrationJob(UUID.randomUUID().toString(),owner,product,RegistrationState.PRODUCT_IMPORTED,0,Data.empty(),Instant.now().toString());
            try(var q=c.prepareStatement("SELECT document FROM znack_v3_jobs WHERE owner_key=? AND nm_id=? AND chrt_id=?")) {
                q.setString(1,owner.key());q.setLong(2,product.nmId());q.setLong(3,product.chrtId());
                try(var r=q.executeQuery()){if(r.next())return decode(r.getString(1));}
            }
            try(var p=c.prepareStatement("INSERT INTO znack_v3_jobs VALUES(?,?,?,?,?,?,?)")) {
                p.setString(1,job.id());p.setInt(2,owner.shopId());p.setString(3,owner.key());p.setLong(4,product.nmId());p.setLong(5,product.chrtId());p.setInt(6,0);p.setString(7,JSON.toJson(job));p.executeUpdate();
            }
            event(c,job);return job;
        });
    }
    private RegistrationJob require(Connection c, Owner owner, String id) throws SQLException {
        try(var p=c.prepareStatement("SELECT document FROM znack_v3_jobs WHERE id=? AND owner_key=?")) {
            p.setString(1,id);p.setString(2,owner.key());
            try(var r=p.executeQuery()){if(r.next())return decode(r.getString(1));}
        }
        throw new SecurityException("V3_JOB_OWNER_CHANGED");
    }
    private static RegistrationJob decode(String text) { return JSON.fromJson(text,RegistrationJob.class); }
    public RegistrationJob get(Owner owner,String id){return transaction(c->require(c,owner,id));}
    public List<RegistrationJob> list(Owner owner) {
        return transaction(c->{var jobs=new ArrayList<RegistrationJob>();
            try(var p=c.prepareStatement("SELECT document FROM znack_v3_jobs WHERE owner_key=? ORDER BY rowid DESC LIMIT 10000")){
                p.setString(1,owner.key());try(var r=p.executeQuery()){while(r.next())jobs.add(decode(r.getString(1)));}
            }return List.copyOf(jobs);
        });
    }
    public RegistrationJob save(RegistrationJob expected,RegistrationJob next){return transaction(c->save(c,expected,next));}
    /** A readback may clear only the unknown actions it actually proves; never releases a live send. */
    public RegistrationJob reconcile(RegistrationJob expected,RegistrationJob next,Set<Action> proven) {
        return transaction(c->{
            var result=save(c,expected,next);
            try(var p=c.prepareStatement("UPDATE znack_v3_remote_operations SET status='RECONCILED',finished_at=? WHERE job_id=? AND action=? AND status='UNKNOWN'")){
                for(var action:proven){p.setString(1,Instant.now().toString());p.setString(2,expected.id());p.setString(3,action.name());p.addBatch();}p.executeBatch();
            }return result;
        });
    }
    private RegistrationJob save(Connection c,RegistrationJob expected,RegistrationJob next)throws SQLException {
        if(!expected.id().equals(next.id())||!expected.owner().equals(next.owner())||!expected.product().equals(next.product())
                ||next.revision()!=expected.revision()+1||!expected.state().permits(next.state()))throw new IllegalStateException("V3_SNAPSHOT_CHANGED");
        var current=require(c,expected.owner(),expected.id());
        if(!current.equals(expected))throw new IllegalStateException("V3_REVISION_CHANGED");
        try(var p=c.prepareStatement("UPDATE znack_v3_jobs SET revision=?,document=? WHERE id=? AND revision=? AND owner_key=?")){
            p.setInt(1,next.revision());p.setString(2,JSON.toJson(next));p.setString(3,expected.id());p.setInt(4,expected.revision());p.setString(5,expected.owner().key());
            if(p.executeUpdate()!=1)throw new IllegalStateException("V3_REVISION_CHANGED");
        }event(c,next);return next;
    }
    private void event(Connection c,RegistrationJob job)throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO znack_v3_events(job_id,state,detail,occurred_at) VALUES(?,?,?,?)")){
            p.setString(1,job.id());p.setString(2,job.state().name());
            p.setString(3,com.vncode.app.integration.znack.ZnackSanitizer.message(job.data().detail()));p.setString(4,job.updatedAt());p.executeUpdate();
        }
    }
    public Operation begin(RegistrationJob expected,Action action,String hash,RegistrationState sending) {
        return transaction(c->{
            if(action==Action.ALLOCATE_GTIN)try(var p=c.prepareStatement("""
                SELECT 1 FROM znack_v3_remote_operations o JOIN znack_v3_jobs j ON j.id=o.job_id
                WHERE j.owner_key=? AND o.action='ALLOCATE_GTIN' AND o.status IN ('SENDING','UNKNOWN')
                """)){
                p.setString(1,expected.owner().key());try(var r=p.executeQuery()){if(r.next())throw new IllegalStateException("V3_ALLOCATION_POOL_RECONCILIATION_REQUIRED");}
            }
            try(var p=c.prepareStatement("SELECT 1 FROM znack_v3_remote_operations WHERE job_id=? AND (status IN ('SENDING','UNKNOWN') OR (action=? AND request_hash=? AND status<>'REJECTED'))")){
                p.setString(1,expected.id());p.setString(2,action.name());p.setString(3,hash);
                try(var r=p.executeQuery()){if(r.next())throw new IllegalStateException("V3_RECONCILIATION_REQUIRED");}
            }
            String reuse=null;int attempts=1;
            try(var p=c.prepareStatement("SELECT id,attempts FROM znack_v3_remote_operations WHERE job_id=? AND action=? AND request_hash=? AND status='REJECTED'")){
                p.setString(1,expected.id());p.setString(2,action.name());p.setString(3,hash);
                try(var r=p.executeQuery()){if(r.next()){reuse=r.getString(1);attempts=r.getInt(2)+1;}}
            }
            var active=save(c,expected,expected.next(sending,expected.data().withDetail("")));
            var op=new Operation(reuse==null?UUID.randomUUID().toString():reuse,expected.id(),action.name(),hash,"SENDING",active.revision(),attempts);
            if(reuse!=null){
                try(var p=c.prepareStatement("UPDATE znack_v3_remote_operations SET status='SENDING',revision=?,attempts=?,started_at=?,finished_at=NULL,worker_id=?,checkpoint_json=? WHERE id=? AND status='REJECTED'")){
                    p.setInt(1,op.revision());p.setInt(2,op.attempts());p.setString(3,Instant.now().toString());p.setString(4,WORKER);p.setString(5,JSON.toJson(active));p.setString(6,reuse);
                    if(p.executeUpdate()!=1)throw new IllegalStateException("V3_OPERATION_CHANGED");
                }return op;
            }
            try(var p=c.prepareStatement("INSERT INTO znack_v3_remote_operations(id,job_id,action,request_hash,status,revision,started_at,worker_id,checkpoint_json) VALUES(?,?,?,?,?,?,?,?,?)")){
                p.setString(1,op.id());p.setString(2,op.jobId());p.setString(3,op.action());p.setString(4,op.requestHash());p.setString(5,op.status());p.setInt(6,op.revision());p.setString(7,Instant.now().toString());p.setString(8,WORKER);p.setString(9,JSON.toJson(active));p.executeUpdate();
            }return op;
        });
    }
    public RegistrationJob finish(Operation operation,RegistrationJob next,String status){
        if(!Set.of("ACKNOWLEDGED","UNKNOWN","RECONCILED","REJECTED").contains(status))throw new IllegalArgumentException("V3_OPERATION_STATUS_INVALID");
        return transaction(c->{
            var active=require(c,next.owner(),operation.jobId());
            if(active.revision()!=operation.revision()||next.revision()!=active.revision()+1)throw new IllegalStateException("V3_REVISION_CHANGED");
            try(var p=c.prepareStatement("UPDATE znack_v3_remote_operations SET status=?,finished_at=?,checkpoint_json=? WHERE id=? AND job_id=? AND status='SENDING' AND revision=?")){
                p.setString(1,status);p.setString(2,Instant.now().toString());p.setString(3,JSON.toJson(next));p.setString(4,operation.id());p.setString(5,operation.jobId());p.setInt(6,operation.revision());
                if(p.executeUpdate()!=1)throw new IllegalStateException("V3_OPERATION_CHANGED");
            }return save(c,active,next);
        });
    }
    public List<Operation> operations(Owner owner,String id){return transaction(c->{
        require(c,owner,id);var out=new ArrayList<Operation>();
        try(var p=c.prepareStatement("SELECT id,job_id,action,request_hash,status,revision,attempts FROM znack_v3_remote_operations WHERE job_id=? ORDER BY rowid")){
            p.setString(1,id);try(var r=p.executeQuery()){while(r.next())out.add(new Operation(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),r.getInt(7)));}
        }return List.copyOf(out);
    });}
    public Set<String> claimedGtins(Owner owner){return transaction(c->{
        var codes=new HashSet<String>();
        try(var p=c.prepareStatement("SELECT json_extract(document,'$.data.gtin') FROM znack_v3_jobs WHERE owner_key=? UNION SELECT receipt FROM znack_v3_dispatches WHERE action='ALLOCATE_GTIN' AND state='ACKNOWLEDGED' AND receipt<>''")){
            p.setString(1,owner.key());try(var r=p.executeQuery()){while(r.next()){String code=r.getString(1);if(code!=null&&!code.isBlank())codes.add(code);}}
        }return Set.copyOf(codes);
    });}
    public List<Event> events(Owner owner,String id){return transaction(c->{
        require(c,owner,id);var out=new ArrayList<Event>();
        try(var p=c.prepareStatement("SELECT sequence,state,detail,occurred_at FROM znack_v3_events WHERE job_id=? ORDER BY sequence")){
            p.setString(1,id);try(var r=p.executeQuery()){while(r.next())out.add(new Event(r.getLong(1),r.getString(2),r.getString(3),r.getString(4)));}
        }return List.copyOf(out);
    });}
}
