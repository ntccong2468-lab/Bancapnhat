package com.vncode.app.integration.ozon;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** A read-only registration scan; page members and their continuation commit together. */
final class OzonRegistrationCheckpoint {
    record State(String cursor,int count,boolean completed,int pages,int total) { }
    static final class Invalidated extends IllegalStateException {Invalidated(){super("OZON_REGISTRATION_CATALOG_CHANGED");}}
    private static final Gson JSON=new Gson();
    private final Shop shop;private final String owner,scope;
    OzonRegistrationCheckpoint(Shop shop,String scope){
        this.shop=Objects.requireNonNull(shop);this.owner=ListingIdentity.fingerprint(shop);this.scope=Objects.requireNonNull(scope);
    }
    private void guard(){
        ListingIdentity.require(shop);
        if(!scope.equals(RegisteredGtinProof.current(shop).source()))throw new SecurityException("SOURCE_CONFIGURATION_CHANGED");
    }
    private static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS ozon_registration_scans(shop_id INTEGER NOT NULL,owner TEXT NOT NULL,source TEXT NOT NULL,cursor TEXT NOT NULL DEFAULT '',count INTEGER NOT NULL DEFAULT 0,completed INTEGER NOT NULL DEFAULT 0,pages INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT 0,updated_at TEXT NOT NULL,PRIMARY KEY(shop_id,owner,source))");
            s.execute("CREATE TABLE IF NOT EXISTS ozon_registration_pages(shop_id INTEGER NOT NULL,owner TEXT NOT NULL,source TEXT NOT NULL,position INTEGER NOT NULL,cursor TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(shop_id,owner,source,position))");
            s.execute("CREATE TABLE IF NOT EXISTS ozon_registration_members(shop_id INTEGER NOT NULL,owner TEXT NOT NULL,source TEXT NOT NULL,product_id TEXT NOT NULL,PRIMARY KEY(shop_id,owner,source,product_id))");
        }
    }
    State open(){
        guard();return write(c->{initialize(c);
            try(var p=c.prepareStatement("SELECT cursor,count,completed,pages,total,updated_at FROM ozon_registration_scans WHERE shop_id=? AND owner=? AND source=?")){
                bind(p);try(var r=p.executeQuery()){if(r.next()){
                    Instant updated;try{updated=Instant.parse(r.getString(6));}catch(RuntimeException invalid){updated=Instant.EPOCH;}
                    if(updated.isAfter(Instant.now().minusSeconds(7200))&&!updated.isAfter(Instant.now().plusSeconds(300)))return new State(r.getString(1),r.getInt(2),r.getBoolean(3),r.getInt(4),r.getInt(5));
                }}
            }
            clear(c);
            try(var p=c.prepareStatement("INSERT INTO ozon_registration_scans(shop_id,owner,source,updated_at) VALUES(?,?,?,?)")){bind(p);p.setString(4,Instant.now().toString());p.executeUpdate();}
            return new State("",0,false,0,0);
        });
    }
    void replay(State state,Consumer<List<OzonProductDto>> observer){
        int count=0;
        for(int position=0;position<state.pages();position++){
            guard();String payload;
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT payload FROM ozon_registration_pages WHERE shop_id=? AND owner=? AND source=? AND position=?")){
                bind(p);p.setInt(4,position);try(var r=p.executeQuery()){if(!r.next())throw new Invalidated();payload=r.getString(1);}
            }catch(SQLException failure){throw new IllegalStateException("OZON_REGISTRATION_CHECKPOINT_STORAGE",failure);}
            List<OzonProductDto> rows=JSON.fromJson(payload,new TypeToken<List<OzonProductDto>>(){}.getType());
            if(rows==null||rows.size()>500||rows.stream().anyMatch(Objects::isNull))throw new Invalidated();
            count+=rows.size();observer.accept(List.copyOf(rows));
        }
        if(count!=state.count())throw new Invalidated();
    }
    State append(State previous,List<OzonProductDto> page,String cursor,boolean completed,int total){
        guard();if(page.size()>500||previous.count()+page.size()>100000)throw new IllegalStateException("SOURCE_PRODUCT_LIMIT");
        validate(previous,page,total);
        int effectiveTotal=total>0?total:previous.total();
        return write(c->{initialize(c);
            try(var p=c.prepareStatement("SELECT 1 FROM ozon_registration_members WHERE shop_id=? AND owner=? AND source=? AND product_id=?")){
                for(var row:page){bind(p);p.setString(4,row.productId());try(var r=p.executeQuery()){if(r.next())throw new Invalidated();}}
            }
            try(var p=c.prepareStatement("INSERT INTO ozon_registration_members(shop_id,owner,source,product_id) VALUES(?,?,?,?)")){
                for(var row:page){bind(p);p.setString(4,row.productId());p.addBatch();}p.executeBatch();
            }
            try(var p=c.prepareStatement("INSERT INTO ozon_registration_pages(shop_id,owner,source,position,cursor,payload) VALUES(?,?,?,?,?,?)")){
                bind(p);p.setInt(4,previous.pages());p.setString(5,cursor);p.setString(6,JSON.toJson(page));p.executeUpdate();
            }
            try(var p=c.prepareStatement("UPDATE ozon_registration_scans SET cursor=?,count=?,completed=?,pages=?,total=?,updated_at=? WHERE shop_id=? AND owner=? AND source=? AND cursor=? AND count=? AND completed=0 AND pages=?")){
                p.setString(1,cursor);p.setInt(2,previous.count()+page.size());p.setBoolean(3,completed);p.setInt(4,previous.pages()+1);p.setInt(5,effectiveTotal);p.setString(6,Instant.now().toString());p.setInt(7,shop.getId());p.setString(8,owner);p.setString(9,scope);p.setString(10,previous.cursor());p.setInt(11,previous.count());p.setInt(12,previous.pages());if(p.executeUpdate()!=1)throw new SQLException("OZON_REGISTRATION_CHECKPOINT_CHANGED");
            }
            return new State(cursor,previous.count()+page.size(),completed,previous.pages()+1,effectiveTotal);
        });
    }
    /** A changed first page invalidates the old cursor; it does not itself prove unchanged later pages. */
    boolean headMatches(State state,OzonJson.ProductPage observed){
        if(state.pages()==0)return true;
        guard();try(var c=Database.getConnection();var p=c.prepareStatement("SELECT cursor,payload FROM ozon_registration_pages WHERE shop_id=? AND owner=? AND source=? AND position=0")){
            bind(p);try(var r=p.executeQuery()){
                if(!r.next())throw new Invalidated();
                String cursor=r.getString(1);List<OzonProductDto> previous=JSON.fromJson(r.getString(2),new TypeToken<List<OzonProductDto>>(){}.getType());
                if(previous==null||previous.size()!=observed.items().size()||state.total()>0&&observed.total()>0&&state.total()!=observed.total()||!Objects.equals(cursor,Objects.requireNonNullElse(observed.lastId(),"")))return false;
                for(int i=0;i<previous.size();i++)if(!previous.get(i).productId().equals(observed.items().get(i).productId())||!previous.get(i).offerId().equals(observed.items().get(i).offerId()))return false;
                return true;
            }
        }catch(SQLException failure){throw new IllegalStateException("OZON_REGISTRATION_CHECKPOINT_STORAGE",failure);}
    }
    /** Reject a changed listing before it can overwrite a previously observed product page. */
    void validate(State previous,List<OzonProductDto> page,int total){
        guard();
        if(total>0&&previous.total()>0&&total!=previous.total()||total>0&&previous.count()+page.size()>total)throw new Invalidated();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM ozon_registration_members WHERE shop_id=? AND owner=? AND source=? AND product_id=?")){
            for(var row:page){bind(p);p.setString(4,row.productId());try(var r=p.executeQuery()){if(r.next())throw new Invalidated();}}
        }catch(SQLException failure){throw new IllegalStateException("OZON_REGISTRATION_CHECKPOINT_STORAGE",failure);}
    }
    void clear(){guard();write(c->{initialize(c);clear(c);return null;});}
    private void clear(Connection c)throws SQLException{
        for(String table:List.of("ozon_registration_members","ozon_registration_pages","ozon_registration_scans"))try(var p=c.prepareStatement("DELETE FROM "+table+" WHERE shop_id=? AND owner=? AND source=?")){bind(p);p.executeUpdate();}
    }
    private void bind(PreparedStatement p)throws SQLException{p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,scope);}
    private static <T>T write(SqliteWriteTransaction.Work<T> work){try{return SqliteWriteTransaction.run(work);}catch(SQLException failure){throw new IllegalStateException("OZON_REGISTRATION_CHECKPOINT_STORAGE",failure);}}
}
