package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.shared.AppPaths;
import java.sql.*;
import java.util.UUID;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;

/** Credential read freshness, deliberately separate from physical ownership and paid journals. */
public final class CatalogReadRevision {
    private static final ConcurrentHashMap<String,Object> LOCKS=new ConcurrentHashMap<>();
    private CatalogReadRevision(){}
    /** Only short local persistence belongs under this lock; never remote reads or signatures. */
    public static Object writeLock(int shop){return LOCKS.computeIfAbsent(AppPaths.appDataDir().toAbsolutePath().normalize()+"/"+shop,key->new Object());}
    private static String key(int shop){return "catalog.read.revision."+shop;}
    private static String get(Connection c,String key)throws SQLException{
        try(var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")){p.setString(1,key);try(var r=p.executeQuery()){return r.next()?r.getString(1):"";}}
    }
    private static void put(Connection c,String key,String value)throws SQLException{
        try(var p=c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){p.setString(1,key);p.setString(2,value);p.executeUpdate();}
    }
    public static String current(Connection c,int shop)throws SQLException{return get(c,key(shop));}
    public static String current(int shop){
        try(var c=Database.getConnection()){return current(c,shop);}
        catch(SQLException failure){throw new IllegalStateException("CATALOG_KEY_STORAGE_FAILED",failure);}
    }
    /** Called in the same transaction as an actual saved credential change/removal. */
    static void advance(Connection c,int shop)throws SQLException{put(c,key(shop),UUID.randomUUID().toString());}
    public static String source(String physicalSource,String revision){return revision.isEmpty()?physicalSource:physicalSource+"#read:"+revision;}
    public static void requireUnchanged(int shop,String expected){if(!expected.equals(current(shop)))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");}
    public static void withCurrent(int shop,String expected,Runnable persistence){synchronized(writeLock(shop)){requireUnchanged(shop,expected);persistence.run();requireUnchanged(shop,expected);}}
    public static boolean complete(int shop){
        try(var c=Database.getConnection()){String revision=get(c,key(shop));return revision.isEmpty()||revision.equals(get(c,key(shop)+".complete"));}
        catch(SQLException failure){throw new IllegalStateException("CATALOG_KEY_STORAGE_FAILED",failure);}
    }
    public static void requireComplete(int shop){if(!complete(shop))throw new IllegalStateException("SOURCE_SYNC_REQUIRED");}
    /** The list also carries the revision of an empty source, where no card can carry metadata. */
    private static final class ObservedRows extends AbstractList<RegisteredGtin> implements RandomAccess {
        final String revision;final List<RegisteredGtin> rows;
        ObservedRows(String revision,List<RegisteredGtin> rows){this.revision=revision;this.rows=List.copyOf(rows);}
        public RegisteredGtin get(int index){return rows.get(index);}public int size(){return rows.size();}
    }
    public static List<RegisteredGtin> copyObservation(List<RegisteredGtin> rows){return rows instanceof ObservedRows?rows:List.copyOf(rows);}
    public static boolean observedAt(RegisteredGtin row,String revision){return revision.equals(row.readRevision());}
    public static List<RegisteredGtin> observed(List<RegisteredGtin> rows,String revision){
        if(rows instanceof ObservedRows observed&&!revision.equals(observed.revision))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
        var stamped=new ArrayList<RegisteredGtin>(rows.size());
        for(var row:rows){
            if(!row.readRevision().isEmpty()&&!revision.equals(row.readRevision()))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
            stamped.add(new RegisteredGtin(row.gtin(),row.article(),row.color(),row.size(),row.tradeUnit(),row.published(),row.source(),row.syncedAt(),row.details(),revision));
        }return new ObservedRows(revision,stamped);
    }
    /** Runs inside the repository writer transaction before replacing or merging any rows. */
    public static void requireObservation(Connection c,int shop,List<RegisteredGtin> rows)throws SQLException{
        String revision=current(c,shop);
        if(rows instanceof ObservedRows observed){if(!revision.equals(observed.revision))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");}
        else if(rows.isEmpty()&&!revision.isEmpty())throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
        for(var row:rows)if(!observedAt(row,revision))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
    }
    /** Only a complete paired source receipt can restore new-write proof after rotation. */
    public static void completed(int shop,String expected){withCurrent(shop,expected,()->{
        try{SqliteWriteTransaction.run(c->{if(!expected.equals(get(c,key(shop))))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");put(c,key(shop)+".complete",expected);return null;});}
        catch(SQLException failure){throw new IllegalStateException("CATALOG_KEY_STORAGE_FAILED",failure);}
    });}
}
