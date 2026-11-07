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
    private static final String WORKER=ProcessHandle.current().pid()+"@"+ProcessHandle.current().info().startInstant().orElseThrow();
    private final java.nio.file.Path profile=com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize();
    public record Command(Action action,String resource,String contentHash) {
        public Command { Objects.requireNonNull(action);if(blank(resource)||blank(contentHash))throw new IllegalArgumentException("REGISTRATION_COMMAND_INVALID");contentHash=hash(contentHash); }
        @Override public String toString(){return "RegistrationCommand[redacted]";}
    }
    @FunctionalInterface public interface Send { List<String> send(Runnable dispatched)throws Exception; }
    @FunctionalInterface private interface Work<T>{T apply(Connection connection)throws SQLException;}
    private record Claim(String id,String receipt){}

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
        try{return com.vncode.app.config.SqliteWriteTransaction.run(work::apply);}
        catch(SQLException failure){throw new IllegalStateException("REGISTRATION_JOURNAL_UNAVAILABLE",failure);}
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
        try{var parts=worker.split("@",2);var process=ProcessHandle.of(Long.parseLong(parts[0]));
            return process.isPresent()&&process.get().isAlive()&&process.get().info().startInstant().map(start->start.toString().equals(parts[1])).orElse(true);
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
                if(command.action()==Action.ALLOCATE_GTIN)requireNoUnresolvedLegacyAllocation(c,owner,allocationPool,command.resource());
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
    /** The allocation pool is shared by both marketplaces for the same Catalog participant. */
    public static String allocationPool(String inn,String environmentUrl) {
        if(inn==null||!inn.matches("[0-9]{10}|[0-9]{12}"))throw new IllegalArgumentException("REGISTRATION_POOL_REQUIRED");
        try{
            var url=okhttp3.HttpUrl.get(environmentUrl);
            boolean production=Set.of(okhttp3.HttpUrl.get(com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API).host(),
                    okhttp3.HttpUrl.get(com.vncode.app.integration.znack.ZnackModels.PRODUCTION_NATIONAL_CATALOG).host()).contains(url.host());
            boolean sandbox=Set.of("markirovka.sandbox.crptech.ru",okhttp3.HttpUrl.get(com.vncode.app.integration.znack.ZnackModels.SANDBOX_NATIONAL_CATALOG).host()).contains(url.host());
            if(!url.isHttps()||url.port()!=443||!url.username().isEmpty()||!url.password().isEmpty()||!(production||sandbox))throw new IllegalArgumentException();
            return hash(inn+"|"+(production?"production":"sandbox"));
        }catch(RuntimeException invalid){throw new IllegalArgumentException("REGISTRATION_POOL_REQUIRED");}
    }
    /** Resource identity is stable, unambiguous and does not expose seller product IDs. */
    public static String ozonAllocationResource(String productId,String offerId) {
        if(blank(productId)||blank(offerId))throw new IllegalArgumentException("REGISTRATION_COMMAND_INVALID");
        return "OZON:"+hash(productId.length()+":"+productId+offerId.length()+":"+offerId);
    }
    /** Old native checkpoints predate the shared journal. A lost receipt is not permission to
     * allocate again, including from another marketplace or another process. */
    private static void requireNoUnresolvedLegacyAllocation(Connection c,Owner owner,String pool,String resource)throws SQLException {
        try(var p=c.prepareStatement("""
            SELECT r.shop_id,r.nm_id,r.chrt_id,s.participant_inn,s.environment
            FROM znack_card_registrations r LEFT JOIN znack_card_registration_scopes s
              ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id
            WHERE r.status IN ('ALLOCATING_GTIN','GTIN_REVIEW_REQUIRED')
              AND TRIM(COALESCE(r.gtin,''))='' AND TRIM(COALESCE(r.feed_id,''))=''
            """);var rows=p.executeQuery()) {
            while(rows.next()) {
                if(rows.getInt(1)==owner.shopId()&&resource.equals(rows.getLong(2)+"/"+rows.getLong(3)))continue;
                if(samePoolOrUnknown(pool,rows.getString(4),rows.getString(5)))throw new IllegalStateException("GTIN_ALLOCATION_READBACK_REQUIRED");
            }
        }
        try(var p=c.prepareStatement("""
            SELECT shop_id,product_id,offer_id,scope_json FROM ozon_card_registrations
            WHERE status IN ('ALLOCATING_GTIN','GTIN_REVIEW_REQUIRED') AND gtin='' AND feed_id=''
            """);var rows=p.executeQuery()) {
            while(rows.next()) {
                if(rows.getInt(1)==owner.shopId()&&resource.equals(ozonAllocationResource(rows.getString(2),rows.getString(3))))continue;
                try{
                    var json=com.google.gson.JsonParser.parseString(rows.getString(4)).getAsJsonObject();
                    if(samePoolOrUnknown(pool,json.get("inn").getAsString(),json.get("environment").getAsString()))
                        throw new IllegalStateException("GTIN_ALLOCATION_READBACK_REQUIRED");
                }catch(IllegalStateException blocked){throw blocked;}
                catch(RuntimeException unknown){throw new IllegalStateException("GTIN_ALLOCATION_READBACK_REQUIRED");}
            }
        }
    }
    private static boolean samePoolOrUnknown(String expected,String inn,String environment) {
        try{return expected.equals(allocationPool(inn,environment));}
        catch(RuntimeException unknown){return true;}
    }
    /** Read the exact acknowledged result inside the same transaction as a native checkpoint.
     * UNKNOWN, SENDING, mismatched owner/resource/generation and blank receipts cannot recover. */
    public static Optional<String> acknowledged(Connection c,Owner owner,Command command)throws SQLException {
        if(command.action()!=Action.ALLOCATE_GTIN)throw new IllegalArgumentException("REGISTRATION_COMMAND_INVALID");
        return acknowledgedReceipt(c,owner,command);
    }
    /** Exact immutable SUBMIT payload only; an unknown or merely sent request is not an ACK. */
    public static Optional<String> acknowledgedSubmission(Connection c,Owner owner,Command command)throws SQLException {
        if(command.action()!=Action.SUBMIT)throw new IllegalArgumentException("REGISTRATION_COMMAND_INVALID");
        return acknowledgedReceipt(c,owner,command);
    }
    private static Optional<String> acknowledgedReceipt(Connection c,Owner owner,Command command)throws SQLException {
        try(var p=c.prepareStatement("SELECT receipt FROM znack_v3_dispatches WHERE owner_key=? AND action=? AND resource=? AND content_hash=? AND state='ACKNOWLEDGED'")){
            bind(p,owner,command);try(var r=p.executeQuery()){
                if(!r.next()||blank(r.getString(1)))return Optional.empty();
                return Optional.of(r.getString(1));
            }
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
