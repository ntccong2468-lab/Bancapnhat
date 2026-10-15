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
    public record Cached(String inn,String email,Instant checkedAt,String catalogError,String balanceError,Boolean applicationSent,boolean newApplication) {
        @Override public String toString(){return "Gs1Cached[redacted]";}
    }
    @FunctionalInterface interface Source {Result read(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope)throws Exception;}
    private final Source source;
    public Gs1AutomaticSyncService(){this(new Remote());}
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
        }
    }
    public Result refresh(Shop shop)throws Exception {
        ListingIdentity.require(shop);LicenseRuntime.require(Permission.GS1);
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var scope=RegisteredGtinProof.scope(shop,settings,Instant.now());RegisteredGtinProof.requireProduction(scope);
        var result=source.read(shop,settings,scope);
        ListingIdentity.require(shop);
        if(!scope.source().equals(RegisteredGtinProof.current(shop).source()))throw new IllegalStateException("GS1_ACCOUNT_CHANGED");
        if(result.membership()!=null&&!scope.inn().equals(result.membership().inn()))throw new SecurityException("GS1_FOREIGN_ENTERPRISE");
        for(var balance:result.balances())if(!scope.inn().equals(balance.scope().inn())||!"production".equals(balance.scope().environment()))throw new SecurityException("GS1_FOREIGN_BALANCE");
        var repository=new Gs1Repository();
        if(result.membership()!=null){repository.saveMembership(result.membership(),"production");repository.completeMembershipRequests(result.membership(),"production");}
        var balances=new Gs1BalanceRepository();for(var balance:result.balances())balances.save(balance);
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            INSERT INTO gs1_sync_status(shop_id,owner,registry_source,inn,profile_email,checked_at,catalog_error,balance_error,application_sent,application_new)
            VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(shop_id) DO UPDATE SET owner=excluded.owner,registry_source=excluded.registry_source,
            inn=excluded.inn,profile_email=CASE WHEN excluded.catalog_error='' THEN excluded.profile_email
              WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.profile_email ELSE '' END,
            checked_at=excluded.checked_at,catalog_error=excluded.catalog_error,balance_error=excluded.balance_error,
            application_sent=CASE WHEN excluded.catalog_error='' THEN excluded.application_sent WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.application_sent ELSE NULL END,
            application_new=CASE WHEN excluded.catalog_error='' THEN excluded.application_new WHEN gs1_sync_status.registry_source=excluded.registry_source THEN gs1_sync_status.application_new ELSE 0 END
            """)){
            p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,scope.source());p.setString(4,scope.inn());
            p.setString(5,safeEmail(result.email()));p.setString(6,Instant.now().toString());p.setString(7,safeCode(result.catalogError()));p.setString(8,safeCode(result.balanceError()));if(result.applicationSent()==null)p.setNull(9,Types.INTEGER);else p.setInt(9,result.applicationSent()?1:0);p.setInt(10,result.newApplication()?1:0);p.executeUpdate();
        }
        return result;
    }
    public Optional<Cached> cached(Shop shop){
        try{
            var scope=RegisteredGtinProof.current(shop);
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT * FROM gs1_sync_status WHERE shop_id=? AND owner=? AND registry_source=?")){
                p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,scope.source());try(var r=p.executeQuery()){
                    return r.next()?Optional.of(new Cached(r.getString("inn"),r.getString("profile_email"),Instant.parse(r.getString("checked_at")),r.getString("catalog_error"),r.getString("balance_error"),r.getObject("application_sent")==null?null:r.getInt("application_sent")!=0,r.getInt("application_new")!=0)):Optional.empty();
                }
            }
        }catch(SQLException e){throw new IllegalStateException("GS1_SYNC_STORAGE",e);}
        catch(IllegalStateException|SecurityException unverified){return Optional.empty();}
    }
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
        private record Session(NationalCatalogGs1Client.Session catalog,ChestnyZnakBalanceClient.ServiceSession cash,Instant expires) {}
        private final Map<String,Session> sessions=new LinkedHashMap<>();
        private final NationalCatalogGs1Client catalogClient;
        private final ChestnyZnakBalanceClient cashClient;
        private final SignerFactory signerFactory;
        private final Clock clock;
        Remote(){this(new NationalCatalogGs1Client(),new ChestnyZnakBalanceClient(),Remote::configuredSigner,Clock.systemUTC());}
        Remote(NationalCatalogGs1Client catalogClient,ChestnyZnakBalanceClient cashClient,SignerFactory signerFactory,Clock clock){
            this.catalogClient=Objects.requireNonNull(catalogClient);this.cashClient=Objects.requireNonNull(cashClient);this.signerFactory=Objects.requireNonNull(signerFactory);this.clock=Objects.requireNonNull(clock);
        }
        private static ZnackSignatureProvider configuredSigner(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope){
            var registration=ZnackRegistrationScope.fromSettings(settings);
            var provider=ZnackSigningSession.guardReadAuthentication(shop.getId(),scope.fingerprint(),new CryptoProSignatureProvider(settings.cryptcpPath(),scope.fingerprint(),Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds())));
            return registration.verifyingSigner(shop,provider);
        }
        @Override public synchronized Result read(Shop shop,ZnackModels.Settings settings,RegisteredGtinProof.Scope scope)throws Exception {
            var signer=signerFactory.create(shop,settings,scope);
            var old=sessions.get(scope.source());if(old!=null&&!old.expires().isAfter(clock.instant()))old=null;
            var catalog=old==null?null:old.catalog();var cash=old==null?null:old.cash();
            Gs1Membership member=null;String email="",catalogError="",balanceError="";Boolean applicationSent=null;boolean newApplication=false;
            try{
                if(catalog==null)catalog=catalogClient.login(scope.inn(),scope.fingerprint(),signer);
                NationalCatalogGs1Client.AccountSnapshot snapshot;
                try{snapshot=catalog.accountSnapshot();}
                catch(NationalCatalogGs1Client.AuthenticationException expired){catalog=catalogClient.login(scope.inn(),scope.fingerprint(),signer);snapshot=catalog.accountSnapshot();}
                member=snapshot.membership();email=profileEmail(snapshot.form());newApplication=snapshot.newApplication();
                var sent=snapshot.form().get("isSent");applicationSent=sent!=null&&sent.isJsonPrimitive()&&sent.getAsJsonPrimitive().isBoolean()?sent.getAsBoolean():null;
            }catch(Exception unavailable){if(unavailable instanceof InterruptedException){Thread.currentThread().interrupt();throw unavailable;}catalog=null;catalogError=com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code();}
            try{
                var client=cashClient;if(cash==null||!cash.expiresAt().isAfter(clock.instant()))cash=client.authenticate(scope.inn(),scope.fingerprint(),signer);
                List<BalanceSnapshot> values;
                try{values=client.readAccounts(cash);}
                catch(ChestnyZnakBalanceClient.AuthenticationException expired){cash=client.authenticate(scope.inn(),scope.fingerprint(),signer);values=client.readAccounts(cash);}
                retain(scope.source(),new Session(catalog,cash,clock.instant().plusSeconds(600)));return new Result(member,email,values,catalogError,"",applicationSent,newApplication);
            }catch(Exception unavailable){if(unavailable instanceof InterruptedException){Thread.currentThread().interrupt();throw unavailable;}cash=null;balanceError=com.vncode.app.features.sync.SyncFailure.of(unavailable,0).code();}
            retain(scope.source(),new Session(catalog,cash,clock.instant().plusSeconds(600)));return new Result(member,email,List.of(),catalogError,balanceError,applicationSent,newApplication);
        }
        private void retain(String scope,Session session){sessions.put(scope,session);while(sessions.size()>10)sessions.remove(sessions.keySet().iterator().next());}
    }
}
