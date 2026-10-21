package com.vncode.app.features.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.gs1.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.ZnackRegistrationScope;
import com.vncode.app.integration.znack.signature.*;
import com.vncode.app.models.Shop;
import com.google.gson.JsonObject;
import java.time.*;
import java.sql.*;
import java.util.*;

/** Read-only enterprise synchronization. No browser, business form submission or email send. */
public final class Gs1AutomaticSyncService {
    public record Result(Gs1Membership membership,String email,List<BalanceSnapshot> balances,String catalogError,String balanceError,Boolean applicationSent,boolean newApplication) {
        public Result(Gs1Membership membership,String email,List<BalanceSnapshot> balances,String catalogError,String balanceError){this(membership,email,balances,catalogError,balanceError,null,false);}
        public Result {balances=List.copyOf(balances);catalogError=Objects.requireNonNullElse(catalogError,"");balanceError=Objects.requireNonNullElse(balanceError,"");}
        public boolean complete(){return catalogError.isBlank()&&balanceError.isBlank();}
        @Override public String toString(){return "Gs1SyncResult[complete="+complete()+"]";}
    }
    public record Cached(String inn,String email,Instant checkedAt,String catalogError,String balanceError,Boolean applicationSent,boolean newApplication,Gs1Membership membership,List<BalanceSnapshot> balances,Instant catalogCheckedAt,Instant balanceCheckedAt) {
        public Cached {balances=List.copyOf(balances);}
        @Override public String toString(){return "Gs1Cached[redacted]";}
    }
    @FunctionalInterface interface Source {Result read(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope)throws Exception;}
    public record Progress(String source,String step,String state,String error) {}
    private static final class Shared {static final Remote REMOTE=new Remote();}
    private final Source source;
    public Gs1AutomaticSyncService(){this(Shared.REMOTE);}
    Gs1AutomaticSyncService(Source source){this.source=Objects.requireNonNull(source);}
    public static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.execute("""
            CREATE TABLE IF NOT EXISTS gs1_sync_status(
              shop_id INTEGER PRIMARY KEY REFERENCES shops(id) ON DELETE CASCADE,
              owner TEXT NOT NULL,registry_source TEXT NOT NULL,inn TEXT NOT NULL,
              profile_email TEXT NOT NULL DEFAULT '',checked_at TEXT NOT NULL,
              catalog_error TEXT NOT NULL DEFAULT '',balance_error TEXT NOT NULL DEFAULT '',
              application_sent INTEGER,application_new INTEGER NOT NULL DEFAULT 0)
            """);}
        var columns=new HashSet<String>();try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(gs1_sync_status)")){while(r.next())columns.add(r.getString("name"));}
        try(var s=c.createStatement()){
            if(!columns.contains("application_sent"))s.execute("ALTER TABLE gs1_sync_status ADD COLUMN application_sent INTEGER");
            if(!columns.contains("application_new"))s.execute("ALTER TABLE gs1_sync_status ADD COLUMN application_new INTEGER NOT NULL DEFAULT 0");
            for(String column:List.of("membership_json","balances_json","catalog_checked_at","balance_checked_at"))
                if(!columns.contains(column))s.execute("ALTER TABLE gs1_sync_status ADD COLUMN "+column+" TEXT");
        }
    }
    public record Account(NationalCatalogGs1Client.Session session,NationalCatalogGs1Client.AccountSnapshot snapshot){@Override public String toString(){return "Gs1Account[redacted]";}}
    public NationalCatalogGs1Client.Session catalogSession(Shop shop)throws java.io.IOException {return catalogAccount(shop).session();}
    public Account catalogAccount(Shop shop)throws java.io.IOException {
        try{
            ListingIdentity.require(shop);LicenseRuntime.require(Permission.GS1);
            var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
            var scope=RegisteredGtinProof.scope(shop,settings,Instant.now());RegisteredGtinProof.requireProduction(scope);
            if(!(source instanceof Remote remote))throw new IllegalStateException("GS1_SESSION_UNAVAILABLE");
            return remote.catalog(shop,settings,scope);
        }catch(java.io.IOException error){throw error;}catch(RuntimeException error){throw error;}catch(Exception error){throw new java.io.IOException("GS1_SESSION_UNAVAILABLE",error);}
    }
    public Result refresh(Shop shop)throws Exception {return refresh(shop,p->{});}
    public Result refresh(Shop shop,java.util.function.Consumer<Progress> progress)throws Exception {
        ListingIdentity.require(shop);LicenseRuntime.require(Permission.GS1);
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var scope=RegisteredGtinProof.scope(shop,settings,Instant.now());RegisteredGtinProof.requireProduction(scope);
        var result=source instanceof Remote remote?remote.read(shop,settings,scope,progress):source.read(shop,settings,scope);
        if(result.membership()==null&&result.catalogError().isBlank())result=new Result(null,result.email(),result.balances(),"GS1_CATALOG_RESPONSE_INCOMPLETE",result.balanceError(),result.applicationSent(),result.newApplication());
        ListingIdentity.require(shop);
        if(!scope.source().equals(RegisteredGtinProof.current(shop).source()))throw new IllegalStateException("GS1_ACCOUNT_CHANGED");
        if(result.membership()!=null&&!scope.inn().equals(result.membership().inn()))throw new SecurityException("GS1_FOREIGN_ENTERPRISE");
        for(var balance:result.balances())if(!scope.inn().equals(balance.scope().inn())||!"production".equals(balance.scope().environment()))throw new SecurityException("GS1_FOREIGN_BALANCE");
        var repository=new Gs1Repository();
        if(result.membership()!=null&&result.catalogError().isBlank()){repository.saveMembership(result.membership(),"production");repository.completeMembershipRequests(result.membership(),"production");}
        var balances=new Gs1BalanceRepository();if(result.balanceError().isBlank())for(var balance:result.balances())balances.save(balance);
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            INSERT INTO gs1_sync_status(shop_id,owner,registry_source,inn,profile_email,checked_at,catalog_error,balance_error,application_sent,application_new,membership_json,balances_json,catalog_checked_at,balance_checked_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(shop_id) DO UPDATE SET owner=excluded.owner,registry_source=excluded.registry_source,
            inn=excluded.inn,profile_email=CASE WHEN excluded.catalog_error='' THEN excluded.profile_email
              WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.profile_email ELSE '' END,
            checked_at=excluded.checked_at,catalog_error=excluded.catalog_error,balance_error=excluded.balance_error,
            application_sent=CASE WHEN excluded.catalog_error='' THEN excluded.application_sent WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.application_sent ELSE NULL END,
            application_new=CASE WHEN excluded.catalog_error='' THEN excluded.application_new WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.application_new ELSE 0 END,
            membership_json=CASE WHEN excluded.catalog_error='' THEN excluded.membership_json WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.membership_json ELSE NULL END,
            balances_json=CASE WHEN excluded.balance_error='' THEN excluded.balances_json WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.balances_json ELSE NULL END,
            catalog_checked_at=CASE WHEN excluded.catalog_error='' THEN excluded.catalog_checked_at WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.catalog_checked_at ELSE NULL END,
            balance_checked_at=CASE WHEN excluded.balance_error='' THEN excluded.balance_checked_at WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.balance_checked_at ELSE NULL END
            """)){
            p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,scope.source());p.setString(4,scope.inn());
            p.setString(5,safeEmail(result.email()));p.setString(6,Instant.now().toString());p.setString(7,safeCode(result.catalogError()));p.setString(8,safeCode(result.balanceError()));if(result.applicationSent()==null)p.setNull(9,Types.INTEGER);else p.setInt(9,result.applicationSent()?1:0);p.setInt(10,result.newApplication()?1:0);
            p.setString(11,result.membership()==null?null:Gs1CacheJson.JSON.toJson(result.membership()));p.setString(12,Gs1CacheJson.JSON.toJson(result.balances()));
            p.setString(13,result.catalogError().isBlank()?Instant.now().toString():null);p.setString(14,result.balanceError().isBlank()?Instant.now().toString():null);p.executeUpdate();
        }
        return result;
    }
    /** Persist a failed attempt without freshening either successful source timestamp. */
    void failed(Shop shop,String expectedSource,String error)throws SQLException {
        var scope=RegisteredGtinProof.current(shop);if(!expectedSource.equals(scope.source()))throw new IllegalStateException("GS1_ACCOUNT_CHANGED");
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            INSERT INTO gs1_sync_status(shop_id,owner,registry_source,inn,checked_at,catalog_error,balance_error)
            VALUES(?,?,?,?,?,?,?) ON CONFLICT(shop_id) DO UPDATE SET owner=excluded.owner,registry_source=excluded.registry_source,
              inn=excluded.inn,checked_at=excluded.checked_at,catalog_error=excluded.catalog_error,balance_error=excluded.balance_error,
              profile_email=CASE WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.profile_email ELSE '' END,
              application_sent=CASE WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.application_sent ELSE NULL END,
              application_new=CASE WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.application_new ELSE 0 END,
              membership_json=CASE WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.membership_json ELSE NULL END,
              balances_json=CASE WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.balances_json ELSE NULL END,
              catalog_checked_at=CASE WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.catalog_checked_at ELSE NULL END,
              balance_checked_at=CASE WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.balance_checked_at ELSE NULL END
            """)){
            p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,scope.source());p.setString(4,scope.inn());p.setString(5,Instant.now().toString());p.setString(6,safeCode(error));p.setString(7,safeCode(error));p.executeUpdate();
        }
    }
    public Optional<Cached> cached(Shop shop){
        try{
            var scope=RegisteredGtinProof.current(shop);
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT * FROM gs1_sync_status WHERE shop_id=? AND owner=? AND registry_source=?")){
                p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,scope.source());try(var r=p.executeQuery()){
                    return r.next()?Optional.of(new Cached(r.getString("inn"),r.getString("profile_email"),Instant.parse(r.getString("checked_at")),r.getString("catalog_error"),r.getString("balance_error"),r.getObject("application_sent")==null?null:r.getInt("application_sent")!=0,r.getInt("application_new")!=0,
                        Gs1CacheJson.JSON.fromJson(r.getString("membership_json"),Gs1Membership.class),cachedBalances(r.getString("balances_json")),instant(r.getString("catalog_checked_at")),instant(r.getString("balance_checked_at")))):Optional.empty();
                }
            }
        }catch(SQLException e){throw new IllegalStateException("GS1_SYNC_STORAGE",e);}
        catch(RuntimeException unverified){return Optional.empty();}
    }
    private static Instant instant(String value){return value==null?null:Instant.parse(value);}
    private static List<BalanceSnapshot> cachedBalances(String json){return json==null?List.of():List.of(Gs1CacheJson.JSON.fromJson(json,BalanceSnapshot[].class));}
    private static String safeCode(String code){return code!=null&&code.matches("[A-Z][A-Z0-9_]{0,79}")?code:code==null||code.isBlank()?"":"GS1_SYNC_FAILED";}
    static String safeEmail(String email){return email!=null&&email.length()<=254&&email.matches("[^\\s<>@]+@[^\\s<>@]+\\.[^\\s<>@]+")?email:"";}
    static String profileEmail(JsonObject form){
        for(String section:List.of("applicant","contacts","contact"))if(form.has(section)&&form.get(section).isJsonObject()){
            var value=form.getAsJsonObject(section);for(String key:List.of("email","eMail","contactEmail")){String found=safeEmail(NationalCatalogGs1Client.text(value,key));if(!found.isBlank())return found;}
        }
        return safeEmail(NationalCatalogGs1Client.text(form,"email"));
    }
    @FunctionalInterface interface SignerFactory {ZnackSignatureProvider create(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope);}
    static final class Remote implements Source {
        private static final ThreadLocal<Long> DEADLINE=new ThreadLocal<>();
        private static final class Session {
            final java.util.concurrent.locks.ReentrantLock lock=new java.util.concurrent.locks.ReentrantLock();
            NationalCatalogGs1Client.Session catalog;ChestnyZnakBalanceClient.ServiceSession cash;
            Instant expires=Instant.MIN;int users;
        }
        private final Map<String,Session> sessions=new LinkedHashMap<>(16,.75f,true);
        private final NationalCatalogGs1Client catalogClient;
        private final ChestnyZnakBalanceClient cashClient;
        private final SignerFactory signerFactory;
        private final Clock clock;
        private final Duration budget;
        Remote(){this(new NationalCatalogGs1Client(),new ChestnyZnakBalanceClient(),Remote::configuredSigner,Clock.systemUTC());}
        Remote(NationalCatalogGs1Client catalogClient,ChestnyZnakBalanceClient cashClient,SignerFactory signerFactory,Clock clock){
            this(catalogClient,cashClient,signerFactory,clock,Duration.ofMinutes(2));
        }
        Remote(NationalCatalogGs1Client catalogClient,ChestnyZnakBalanceClient cashClient,SignerFactory signerFactory,Clock clock,Duration budget){
            this.catalogClient=Objects.requireNonNull(catalogClient);this.cashClient=Objects.requireNonNull(cashClient);this.signerFactory=Objects.requireNonNull(signerFactory);this.clock=Objects.requireNonNull(clock);
            if(budget==null||budget.isNegative()||budget.isZero()||budget.compareTo(Duration.ofMinutes(2))>0)throw new IllegalArgumentException("GS1_SYNC_BUDGET");this.budget=budget;
        }
        private static ZnackSignatureProvider configuredSigner(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope){
            var registration=ZnackRegistrationScope.fromSettings(settings);
            var provider=ZnackSigningSession.guardReadAuthentication(shop.getId(),scope.fingerprint(),new CryptoProSignatureProvider(settings.cryptcpPath(),scope.fingerprint(),Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds())));
            return registration.verifyingSigner(shop,provider);
        }
        private synchronized Session acquire(String source){
            String key=System.getProperty("vncode.appdata.dir","")+"/"+source;
            Session entry=sessions.get(key);
            if(entry==null){
                if(sessions.size()>=10){var idle=sessions.entrySet().stream().filter(e->e.getValue().users==0).findFirst();if(idle.isEmpty())throw new IllegalStateException("GS1_SESSION_LIMIT");sessions.remove(idle.get().getKey());}
                entry=new Session();sessions.put(key,entry);
            }
            entry.users++;return entry;
        }
        private synchronized void release(Session entry){entry.users--;}
        private void lock(Session entry)throws java.io.InterruptedIOException {
            try{if(!entry.lock.tryLock(90,java.util.concurrent.TimeUnit.SECONDS))throw new java.net.SocketTimeoutException("GS1_SESSION_BUSY");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("GS1_SYNC_CANCELLED");}
        }
        private void resetExpired(Session entry){if(!entry.expires.isAfter(clock.instant())){entry.catalog=null;entry.cash=null;entry.expires=clock.instant().plusSeconds(600);}}
        private static void guard(Shop shop,RegisteredGtinProof.Scope scope){
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("GS1_SYNC_CANCELLED");
            Long deadline=DEADLINE.get();if(deadline!=null&&System.nanoTime()>deadline)throw new IllegalStateException("GS1_SYNC_TIMEOUT");
            LicenseRuntime.require(Permission.GS1);
            if(!scope.source().equals(RegisteredGtinProof.current(shop).source()))throw new IllegalStateException("GS1_ACCOUNT_CHANGED");
        }
        private NationalCatalogGs1Client.AccountSnapshot account(Session entry,Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope,java.util.function.Consumer<Progress> progress)throws Exception {
            var signer=signerFactory.create(shop,settings,scope);guard(shop,scope);
            if(entry.catalog==null){progress.accept(new Progress("CATALOG","AUTHENTICATING_CATALOG","RUNNING",""));entry.catalog=catalogClient.login(scope.inn(),scope.fingerprint(),signer,()->guard(shop,scope));}
            progress.accept(new Progress("CATALOG","READING_CATALOG","RUNNING",""));
            try{return entry.catalog.accountSnapshot();}
            catch(NationalCatalogGs1Client.AuthenticationException expired){
                progress.accept(new Progress("CATALOG","REAUTHENTICATING_CATALOG","RUNNING",""));
                entry.catalog=catalogClient.login(scope.inn(),scope.fingerprint(),signer,()->guard(shop,scope));return entry.catalog.accountSnapshot();
            }
        }
        /** Probe/reconnect with reads only. A business write is never replayed after a 401. */
        Account catalog(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope)throws Exception {
            Session entry=acquire(scope.source());boolean locked=false;Long previous=DEADLINE.get();DEADLINE.set(System.nanoTime()+budget.toNanos());
            try{lock(entry);locked=true;resetExpired(entry);var snapshot=account(entry,shop,settings,scope,p->{});guard(shop,scope);return new Account(entry.catalog,snapshot);}
            catch(Exception e){if(locked)entry.catalog=null;throw e;}
            finally{if(locked)entry.lock.unlock();release(entry);if(previous==null)DEADLINE.remove();else DEADLINE.set(previous);}
        }
        @Override public Result read(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope)throws Exception{return read(shop,settings,scope,p->{});}
        Result read(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope,java.util.function.Consumer<Progress> progress)throws Exception {
            Session entry=acquire(scope.source());boolean locked=false;Long previous=DEADLINE.get();DEADLINE.set(System.nanoTime()+budget.toNanos());
            var phases=new HashMap<String,String>();
            java.util.function.Consumer<Progress> tracked=value->{phases.put(value.source(),value.step());progress.accept(value);};
            try{lock(entry);locked=true;resetExpired(entry);
                Gs1Membership member=null;String email="",catalogError="",balanceError="";Boolean applicationSent=null;boolean newApplication=false;
                try{
                    var snapshot=account(entry,shop,settings,scope,tracked);guard(shop,scope);
                    member=snapshot.membership();email=profileEmail(snapshot.form());newApplication=snapshot.newApplication();
                    var sent=snapshot.form().get("isSent");applicationSent=sent!=null&&sent.isJsonPrimitive()&&sent.getAsJsonPrimitive().isBoolean()?sent.getAsBoolean():null;
                    tracked.accept(new Progress("CATALOG","CATALOG_COMPLETE","SUCCEEDED",""));
                }catch(Exception unavailable){interrupted(unavailable);entry.catalog=null;catalogError=com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code();tracked.accept(new Progress("CATALOG",phases.getOrDefault("CATALOG","CHECKING_SETTINGS"),"FAILED",catalogError));}
                try{
                    guard(shop,scope);var signer=signerFactory.create(shop,settings,scope);
                    if(entry.cash==null||!entry.cash.expiresAt().isAfter(clock.instant())){tracked.accept(new Progress("BALANCE","AUTHENTICATING_BALANCE","RUNNING",""));entry.cash=cashClient.authenticate(scope.inn(),scope.fingerprint(),signer);}
                    tracked.accept(new Progress("BALANCE","READING_BALANCE","RUNNING",""));
                    List<BalanceSnapshot> values;
                    try{values=cashClient.readAccounts(entry.cash);}
                    catch(ChestnyZnakBalanceClient.AuthenticationException expired){tracked.accept(new Progress("BALANCE","REAUTHENTICATING_BALANCE","RUNNING",""));entry.cash=cashClient.authenticate(scope.inn(),scope.fingerprint(),signer);values=cashClient.readAccounts(entry.cash);}
                    guard(shop,scope);tracked.accept(new Progress("BALANCE","BALANCE_COMPLETE","SUCCEEDED",""));return new Result(member,email,values,catalogError,"",applicationSent,newApplication);
                }catch(Exception unavailable){interrupted(unavailable);entry.cash=null;balanceError=com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code();tracked.accept(new Progress("BALANCE",phases.getOrDefault("BALANCE","CHECKING_SETTINGS"),"FAILED",balanceError));}
                return new Result(member,email,List.of(),catalogError,balanceError,applicationSent,newApplication);
            }finally{if(locked)entry.lock.unlock();release(entry);if(previous==null)DEADLINE.remove();else DEADLINE.set(previous);}
        }
        private static void interrupted(Exception error)throws Exception {
            if(error instanceof InterruptedException){Thread.currentThread().interrupt();throw error;}
            if(Thread.currentThread().isInterrupted()||error instanceof java.util.concurrent.CancellationException)throw error;
        }
    }
}
