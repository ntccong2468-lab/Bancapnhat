package com.vncode.app.features.finance;

import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owner- and range-scoped penalty checkpoint, isolated from legacy analytics cursors. */
public final class FinancePenaltySyncStore {
    private static final int MAX_PAGE=500;
    private static final long LEASE_SECONDS=120;
    private static final String ACTIVE="('QUEUED','WAITING','IN_PROGRESS')";

    public record Job(int shopId, String ownerFingerprint, LocalDate from, LocalDate to,
                      String runId, String attemptId, String cursor, String status,
                      Instant nextRunAt, Instant lastReadAt, String failureCode) {
        @Override public String toString() { return "FinancePenaltyJob[shop="+shopId+",status="+status+"]"; }
    }
    public record ScopeStatus(boolean verified, boolean complete, Instant lastReadAt,
                              String status, String failureCode, Instant nextRunAt) { }

    /** Schema is additive and created atomically; legacy rows and cursors are never changed. */
    static void createSchema(Connection c) throws SQLException {
        try (var s=c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS finance_penalty_sync_job (
                  shop_id INTEGER NOT NULL, owner_fingerprint TEXT NOT NULL, date_from TEXT NOT NULL, date_to TEXT NOT NULL,
                  run_id TEXT NOT NULL, attempt_id TEXT, cursor TEXT NOT NULL, status TEXT NOT NULL,
                  next_run_at TEXT, lease_until TEXT, last_read_at TEXT, failure_code TEXT, updated_at TEXT NOT NULL,
                  PRIMARY KEY(shop_id,owner_fingerprint,date_from,date_to))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS finance_penalty_sync_observation (
                  shop_id INTEGER NOT NULL, owner_fingerprint TEXT NOT NULL, run_id TEXT NOT NULL, rrd_id TEXT NOT NULL,
                  business_date TEXT NOT NULL, currency TEXT NOT NULL, operation_name TEXT, order_id TEXT,
                  product_id TEXT, vendor_code TEXT, sku TEXT, penalty_cost REAL NOT NULL,
                  PRIMARY KEY(shop_id,owner_fingerprint,run_id,rrd_id))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS finance_penalty_rate (
                  shop_id INTEGER NOT NULL, api_family TEXT NOT NULL, next_allowed_at TEXT NOT NULL,
                  PRIMARY KEY(shop_id,api_family))
                """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_penalty_sync_observation_date ON finance_penalty_sync_observation(shop_id,owner_fingerprint,run_id,business_date)");
        }
    }

    private static void ensureSchema() {
        AnalyticsDatabase.initialize();
        try (var c=AnalyticsDatabase.getConnection()) {
            c.setAutoCommit(false);
            try { createSchema(c); c.commit(); }
            catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot prepare penalty sync store",failure); }
    }

    /** Requesting an active range is idempotent. A finished/cancelled range starts a new isolated run. */
    public Job request(Shop shop,LocalDate from,LocalDate to,Instant now) {
        validate(shop,from,to); Objects.requireNonNull(now,"now");
        return ShopOperationCoordinator.withExclusiveShop(shop.getId(), () -> {
            ListingIdentity.require(shop); String owner=ListingIdentity.fingerprint(shop); ensureSchema();
            try(var c=AnalyticsDatabase.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Job existing=get(c,shop.getId(),owner,from,to);
                    if(existing!=null && isActive(existing.status())) { c.commit(); return existing; }
                    String run=UUID.randomUUID().toString();
                    try(var p=c.prepareStatement("""
                        INSERT INTO finance_penalty_sync_job(shop_id,owner_fingerprint,date_from,date_to,run_id,attempt_id,cursor,status,next_run_at,lease_until,last_read_at,failure_code,updated_at)
                        VALUES(?,?,?,?,?,NULL,'0','QUEUED',?,NULL,NULL,NULL,?)
                        ON CONFLICT(shop_id,owner_fingerprint,date_from,date_to) DO UPDATE SET
                        run_id=excluded.run_id,attempt_id=NULL,cursor='0',status='QUEUED',next_run_at=excluded.next_run_at,
                        lease_until=NULL,last_read_at=NULL,failure_code=NULL,updated_at=excluded.updated_at
                        """)) {
                        p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,from.toString());p.setString(4,to.toString());
                        p.setString(5,run);p.setString(6,now.toString());p.setString(7,now.toString());p.executeUpdate();
                    }
                    verify(shop,owner); Job result=get(c,shop.getId(),owner,from,to); c.commit(); return result;
                } catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
            } catch(SQLException failure){throw new IllegalStateException("Cannot queue penalty sync",failure);}
        });
    }

    /** Return one due job for the current credential; stale in-flight attempts become resumable after their lease. */
    public Job due(Shop shop,Instant now) {
        Objects.requireNonNull(shop,"shop");Objects.requireNonNull(now,"now");
        return withOwner(shop,owner->{
            try(var c=AnalyticsDatabase.getConnection();var p=c.prepareStatement("""
                SELECT * FROM finance_penalty_sync_job WHERE shop_id=? AND owner_fingerprint=?
                  AND status IN ('QUEUED','WAITING','IN_PROGRESS') ORDER BY updated_at,date_from,date_to
                """)) {
                p.setInt(1,shop.getId());p.setString(2,owner);
                try(var rows=p.executeQuery()) { while(rows.next()) {
                    Instant deadline=parse(rows.getString("IN_PROGRESS".equals(rows.getString("status"))?"lease_until":"next_run_at"));
                    if(deadline==null)throw new IllegalStateException("Penalty checkpoint has no due time");
                    if(!deadline.isAfter(now))return job(rows);
                }}
                return null;
            } catch(SQLException failure){throw new IllegalStateException("Cannot read penalty queue",failure);}
        });
    }

    /** Claim a page and reserve the shared family rate before any HTTP call. */
    public Job begin(Shop shop,Job expected,Instant now,Instant nextAllowed) {
        requireExpected(shop,expected);Objects.requireNonNull(now,"now");Objects.requireNonNull(nextAllowed,"nextAllowed");
        if(nextAllowed.isBefore(now))throw new IllegalArgumentException("Invalid request cooldown");
        return withOwner(shop,owner->{
            if(!owner.equals(expected.ownerFingerprint()))return null;
            try(var c=AnalyticsDatabase.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Job current=get(c,shop.getId(),owner,expected.from(),expected.to());
                    if(!sameJob(current,expected)||!isDue(c,current,now)){c.rollback();return null;}
                    Instant rate=getRate(c,shop.getId(),"FINANCE");
                    if(rate!=null&&rate.isAfter(now)){c.rollback();return null;}
                    String attempt=UUID.randomUUID().toString();
                    try(var p=c.prepareStatement("""
                        UPDATE finance_penalty_sync_job SET status='IN_PROGRESS',attempt_id=?,lease_until=?,next_run_at=NULL,
                        failure_code=NULL,updated_at=? WHERE shop_id=? AND owner_fingerprint=? AND date_from=? AND date_to=? AND run_id=?
                        """)) {
                        p.setString(1,attempt);p.setString(2,now.plusSeconds(LEASE_SECONDS).toString());p.setString(3,now.toString());
                        bindJob(p,4,current);p.setString(8,current.runId());if(p.executeUpdate()!=1){c.rollback();return null;}
                    }
                    extendRate(c,shop.getId(),"FINANCE",nextAllowed);verify(shop,owner);
                    Job result=get(c,shop.getId(),owner,expected.from(),expected.to());c.commit();return result;
                } catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
            } catch(SQLException failure){throw new IllegalStateException("Cannot claim penalty page",failure);}
        });
    }

    /** Page observations and checkpoint commit together; an old/cancelled attempt cannot commit. */
    public boolean commitPage(Shop shop,Job attempt,Collection<FinanceRawRow> rows,String nextCursor,boolean complete,Instant now) {
        return commitPage(shop,attempt,rows,nextCursor,complete,now,()->{});
    }
    boolean commitPage(Shop shop,Job attempt,Collection<FinanceRawRow> rows,String nextCursor,boolean complete,Instant now,Runnable beforeCommit) {
        requireAttempt(shop,attempt);Objects.requireNonNull(rows,"rows");Objects.requireNonNull(now,"now");
        Objects.requireNonNull(beforeCommit,"beforeCommit");
        if(!complete&&(nextCursor==null||nextCursor.isBlank()||nextCursor.equals(attempt.cursor())))
            throw new IllegalArgumentException("Penalty cursor did not advance");
        if(nextCursor!=null&&nextCursor.length()>8192)throw new IllegalArgumentException("Invalid penalty cursor");
        return withOwner(shop,owner->{
            if(!owner.equals(attempt.ownerFingerprint()))return false;
            try(var c=AnalyticsDatabase.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Job current=get(c,shop.getId(),owner,attempt.from(),attempt.to());
                    if(!sameAttempt(current,attempt)){c.rollback();return false;}
                    saveObservations(c,current,rows);
                    try(var p=c.prepareStatement("""
                       UPDATE finance_penalty_sync_job SET status=?,cursor=?,attempt_id=NULL,lease_until=NULL,next_run_at=?,
                       last_read_at=?,failure_code=NULL,updated_at=? WHERE shop_id=? AND owner_fingerprint=? AND date_from=? AND date_to=? AND run_id=? AND attempt_id=? AND status='IN_PROGRESS'
                       """)) {
                        p.setString(1,complete?"COMPLETE":"QUEUED");p.setString(2,complete?current.cursor():nextCursor);
                        p.setString(3,complete?null:now.toString());p.setString(4,now.toString());p.setString(5,now.toString());
                        bindJob(p,6,current);p.setString(10,current.runId());p.setString(11,current.attemptId());
                        if(p.executeUpdate()!=1){c.rollback();return false;}
                    }
                    beforeCommit.run();verify(shop,owner);c.commit();return true;
                } catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
            } catch(SQLException failure){throw new IllegalStateException("Cannot commit penalty page",failure);}
        });
    }

    /** Preserve the cursor and apply a server retry delay, never shortening the shared cooldown. */
    public boolean defer(Shop shop,Job attempt,Instant nextAllowed,String fixedCode) {
        requireAttempt(shop,attempt);Objects.requireNonNull(nextAllowed,"nextAllowed");
        if(fixedCode==null||!fixedCode.matches("[A-Z0-9_]{1,64}"))throw new IllegalArgumentException("Invalid failure code");
        return withOwner(shop,owner->{
            if(!owner.equals(attempt.ownerFingerprint()))return false;
            try(var c=AnalyticsDatabase.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Job current=get(c,shop.getId(),owner,attempt.from(),attempt.to());
                    if(!sameAttempt(current,attempt)){c.rollback();return false;}
                    try(var p=c.prepareStatement("""
                      UPDATE finance_penalty_sync_job SET status='WAITING',attempt_id=NULL,lease_until=NULL,next_run_at=?,
                      failure_code=?,updated_at=? WHERE shop_id=? AND owner_fingerprint=? AND date_from=? AND date_to=? AND run_id=? AND attempt_id=? AND status='IN_PROGRESS'
                      """)) {
                        p.setString(1,nextAllowed.toString());p.setString(2,fixedCode);p.setString(3,Instant.now().toString());
                        bindJob(p,4,current);p.setString(8,current.runId());p.setString(9,current.attemptId());
                        if(p.executeUpdate()!=1){c.rollback();return false;}
                    }
                    extendRate(c,shop.getId(),"FINANCE",nextAllowed);verify(shop,owner);c.commit();return true;
                } catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
            } catch(SQLException failure){throw new IllegalStateException("Cannot defer penalty page",failure);}
        });
    }

    /** A late HTTP result after cancellation cannot commit because its attempt ID is cleared. */
    public boolean cancel(Shop shop,LocalDate from,LocalDate to) {
        validate(shop,from,to);
        return ShopOperationCoordinator.withExclusiveShop(shop.getId(),()->{
            ListingIdentity.require(shop);String owner=ListingIdentity.fingerprint(shop);ensureSchema();
            try(var c=AnalyticsDatabase.getConnection()) {
                c.setAutoCommit(false);
                try(var p=c.prepareStatement("""
                   UPDATE finance_penalty_sync_job SET status='CANCELLED',attempt_id=NULL,lease_until=NULL,next_run_at=NULL,updated_at=?
                   WHERE shop_id=? AND owner_fingerprint=? AND date_from=? AND date_to=? AND status IN ('QUEUED','WAITING','IN_PROGRESS')
                   """)) {
                    p.setString(1,Instant.now().toString());p.setInt(2,shop.getId());p.setString(3,owner);p.setString(4,from.toString());p.setString(5,to.toString());
                    boolean result=p.executeUpdate()!=0;verify(shop,owner);c.commit();return result;
                } catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
            } catch(SQLException failure){throw new IllegalStateException("Cannot cancel penalty sync",failure);}
        });
    }

    public ScopeStatus status(Shop shop,LocalDate from,LocalDate to) {
        validate(shop,from,to);
        return withOwner(shop,owner->{try(var c=AnalyticsDatabase.getConnection()) {
            Job j=get(c,shop.getId(),owner,from,to);
            return j==null?new ScopeStatus(false,false,null,"NOT_STARTED",null,null):
                    new ScopeStatus(j.lastReadAt()!=null,"COMPLETE".equals(j.status()),j.lastReadAt(),j.status(),j.failureCode(),j.nextRunAt());
        } catch(SQLException failure){throw new IllegalStateException("Cannot read penalty status",failure);}});
    }

    public List<FinancePenaltyEntry> load(Shop shop,LocalDate from,LocalDate to,int limit,int offset) {
        validate(shop,from,to);if(limit<1||limit>MAX_PAGE||offset<0)throw new IllegalArgumentException("Invalid penalty page");
        return withOwner(shop,owner->{
            try(var c=AnalyticsDatabase.getConnection()) {
                Job current=get(c,shop.getId(),owner,from,to);if(current==null)return List.of();
                try(var p=c.prepareStatement("""
                   SELECT * FROM finance_penalty_sync_observation WHERE shop_id=? AND owner_fingerprint=? AND run_id=? AND penalty_cost != 0
                   ORDER BY business_date DESC,rrd_id DESC LIMIT ? OFFSET ?
                   """)) {
                    p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,current.runId());p.setInt(4,limit);p.setInt(5,offset);
                    List<FinancePenaltyEntry> result=new ArrayList<>();
                    try(var r=p.executeQuery()){while(r.next())result.add(new FinancePenaltyEntry(r.getString("rrd_id"),LocalDate.parse(r.getString("business_date")),r.getString("currency"),r.getString("operation_name"),r.getString("order_id"),r.getString("product_id"),r.getString("vendor_code"),r.getString("sku"),r.getDouble("penalty_cost")));}
                    return List.copyOf(result);
                }
            } catch(SQLException failure){throw new IllegalStateException("Cannot read penalty page",failure);}
        });
    }

    public long count(Shop shop,LocalDate from,LocalDate to) {
        validate(shop,from,to);
        return withOwner(shop,owner->{try(var c=AnalyticsDatabase.getConnection()) {
            Job current=get(c,shop.getId(),owner,from,to);if(current==null)return 0L;
            try(var p=c.prepareStatement("SELECT count(*) FROM finance_penalty_sync_observation WHERE shop_id=? AND owner_fingerprint=? AND run_id=? AND penalty_cost != 0")) {
                p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,current.runId());try(var r=p.executeQuery()){return r.next()?r.getLong(1):0L;}
            }
        } catch(SQLException failure){throw new IllegalStateException("Cannot count penalties",failure);}});
    }

    public Instant nextAllowed(int shopId,String family) {
        if(shopId<=0||family==null||!family.matches("[A-Z_]{1,32}"))throw new IllegalArgumentException("Invalid API family");
        ensureSchema();try(var c=AnalyticsDatabase.getConnection()){return getRate(c,shopId,family);}
        catch(SQLException failure){throw new IllegalStateException("Cannot read finance rate",failure);}
    }

    /** Reserve a shared cooldown without ever shortening a later reservation. */
    public void extendRate(int shopId,String family,Instant nextAllowed) {
        if(shopId<=0||family==null||!family.matches("[A-Z_]{1,32}")||nextAllowed==null)throw new IllegalArgumentException("Invalid API family or cooldown");
        ensureSchema();
        try(var c=AnalyticsDatabase.getConnection()) {
            c.setAutoCommit(false);
            try {
                // The first write takes SQLite's writer lock before reading the current value.
                try(var p=c.prepareStatement("INSERT OR IGNORE INTO finance_penalty_rate(shop_id,api_family,next_allowed_at) VALUES(?,?,?)")) {
                    p.setInt(1,shopId);p.setString(2,family);p.setString(3,nextAllowed.toString());p.executeUpdate();
                }
                extendRate(c,shopId,family,nextAllowed);c.commit();
            } catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
        } catch(SQLException failure){throw new IllegalStateException("Cannot reserve finance rate",failure);}
    }

    private static void saveObservations(Connection c,Job job,Collection<FinanceRawRow> rows) throws SQLException {
        var seen=new HashSet<String>();var zeros=new ArrayList<String>();
        try(var p=c.prepareStatement("""
          INSERT INTO finance_penalty_sync_observation(shop_id,owner_fingerprint,run_id,rrd_id,business_date,currency,operation_name,order_id,product_id,vendor_code,sku,penalty_cost)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(shop_id,owner_fingerprint,run_id,rrd_id) DO UPDATE SET
          business_date=excluded.business_date,currency=excluded.currency,operation_name=excluded.operation_name,order_id=excluded.order_id,
          product_id=excluded.product_id,vendor_code=excluded.vendor_code,sku=excluded.sku,penalty_cost=excluded.penalty_cost
          """)) {
            for(FinanceRawRow row:rows) {
                Objects.requireNonNull(row,"row");if(row.rrdId()==null||row.rrdId().isBlank()||row.businessDate()==null||row.currency()==null||!Double.isFinite(row.penaltyCost())||!seen.add(row.rrdId()))throw new IllegalArgumentException("Invalid penalty row");
                LocalDate day=LocalDate.parse(row.businessDate());if(day.isBefore(job.from())||day.isAfter(job.to()))throw new IllegalArgumentException("Penalty row outside requested range");
                if(row.penaltyCost()==0){zeros.add(row.rrdId());continue;}
                p.setInt(1,job.shopId());p.setString(2,job.ownerFingerprint());p.setString(3,job.runId());p.setString(4,row.rrdId());
                p.setString(5,row.businessDate());p.setString(6,row.currency());p.setString(7,row.operationName());p.setString(8,row.orderId());
                p.setString(9,row.nmId());p.setString(10,row.vendorCode());p.setString(11,row.sku());p.setDouble(12,row.penaltyCost());p.addBatch();
            }
            p.executeBatch();
        }
        for(int start=0;start<zeros.size();start+=500){var ids=zeros.subList(start,Math.min(start+500,zeros.size()));String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
            try(var p=c.prepareStatement("UPDATE finance_penalty_sync_observation SET penalty_cost=0 WHERE shop_id=? AND owner_fingerprint=? AND run_id=? AND rrd_id IN ("+marks+")")){
                p.setInt(1,job.shopId());p.setString(2,job.ownerFingerprint());p.setString(3,job.runId());for(int i=0;i<ids.size();i++)p.setString(i+4,ids.get(i));p.executeUpdate();
            }
        }
    }

    private static Job get(Connection c,int shopId,String owner,LocalDate from,LocalDate to)throws SQLException {
        try(var p=c.prepareStatement("SELECT * FROM finance_penalty_sync_job WHERE shop_id=? AND owner_fingerprint=? AND date_from=? AND date_to=?")){
            p.setInt(1,shopId);p.setString(2,owner);p.setString(3,from.toString());p.setString(4,to.toString());try(var r=p.executeQuery()){return r.next()?job(r):null;}
        }
    }
    private static Job job(ResultSet r)throws SQLException {return new Job(r.getInt("shop_id"),r.getString("owner_fingerprint"),LocalDate.parse(r.getString("date_from")),LocalDate.parse(r.getString("date_to")),r.getString("run_id"),r.getString("attempt_id"),r.getString("cursor"),r.getString("status"),parse(r.getString("next_run_at")),parse(r.getString("last_read_at")),r.getString("failure_code"));}
    private static Instant parse(String value){return value==null?null:Instant.parse(value);}
    private static boolean isActive(String state){return state!=null&&(state.equals("QUEUED")||state.equals("WAITING")||state.equals("IN_PROGRESS"));}
    private static boolean sameJob(Job a,Job b){return a!=null&&a.shopId()==b.shopId()&&a.ownerFingerprint().equals(b.ownerFingerprint())&&a.runId().equals(b.runId())&&a.from().equals(b.from())&&a.to().equals(b.to());}
    private static boolean sameAttempt(Job a,Job b){return sameJob(a,b)&&"IN_PROGRESS".equals(a.status())&&a.attemptId()!=null&&a.attemptId().equals(b.attemptId())&&Objects.equals(a.cursor(),b.cursor());}
    private static boolean isDue(Connection c,Job job,Instant now)throws SQLException {
        if(job==null||!isActive(job.status()))return false;
        try(var p=c.prepareStatement("SELECT next_run_at,lease_until FROM finance_penalty_sync_job WHERE shop_id=? AND owner_fingerprint=? AND date_from=? AND date_to=?")){
            bindJob(p,1,job);try(var r=p.executeQuery()){if(!r.next())return false;Instant date=parse(r.getString(job.status().equals("IN_PROGRESS")?2:1));if(date==null)throw new IllegalStateException("Penalty checkpoint has no due time");return !date.isAfter(now);}
        }
    }
    private static void bindJob(PreparedStatement p,int index,Job job)throws SQLException {p.setInt(index,job.shopId());p.setString(index+1,job.ownerFingerprint());p.setString(index+2,job.from().toString());p.setString(index+3,job.to().toString());}
    private static Instant getRate(Connection c,int shopId,String family)throws SQLException {try(var p=c.prepareStatement("SELECT next_allowed_at FROM finance_penalty_rate WHERE shop_id=? AND api_family=?")){p.setInt(1,shopId);p.setString(2,family);try(var r=p.executeQuery()){return r.next()?parse(r.getString(1)):null;}}}
    private static void extendRate(Connection c,int shopId,String family,Instant next)throws SQLException {
        Instant prior=getRate(c,shopId,family);if(prior!=null&&!next.isAfter(prior))return;
        try(var p=c.prepareStatement("INSERT INTO finance_penalty_rate(shop_id,api_family,next_allowed_at) VALUES(?,?,?) ON CONFLICT(shop_id,api_family) DO UPDATE SET next_allowed_at=excluded.next_allowed_at")){p.setInt(1,shopId);p.setString(2,family);p.setString(3,next.toString());p.executeUpdate();}
    }
    private static void verify(Shop shop,String owner){ListingIdentity.require(shop);if(!owner.equals(ListingIdentity.fingerprint(shop)))throw new SecurityException("Finance owner changed");}
    private static <T> T withOwner(Shop shop,java.util.function.Function<String,T> action){Objects.requireNonNull(shop,"shop");return ShopOperationCoordinator.withActiveShop(shop.getId(),()->{ListingIdentity.require(shop);String owner=ListingIdentity.fingerprint(shop);ensureSchema();T value=action.apply(owner);verify(shop,owner);return value;});}
    private static void validate(Shop shop,LocalDate from,LocalDate to){if(shop==null||shop.getId()<=0||from==null||to==null||from.isAfter(to))throw new IllegalArgumentException("Invalid penalty scope");}
    private static void requireExpected(Shop shop,Job job){Objects.requireNonNull(shop,"shop");Objects.requireNonNull(job,"job");if(shop.getId()!=job.shopId())throw new SecurityException("Finance owner changed");}
    private static void requireAttempt(Shop shop,Job job){requireExpected(shop,job);if(job.attemptId()==null||!"IN_PROGRESS".equals(job.status()))throw new IllegalArgumentException("Invalid penalty attempt");}
}
