package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Incremental read cache scoped to one seller connection; never authorizes a mutation. */
final class CatalogVersionCheckpoint {
    record State(int offset,Integer total,boolean listed){}
    record Entry(String id,String etag,JsonObject card,Instant observedAt){}
    private final int shop;private final String source;
    CatalogVersionCheckpoint(int shop,String source){this.shop=shop;this.source=source;}
    State load(boolean resume){return write(c->{
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS znack_catalog_version_scans(shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,source TEXT NOT NULL,offset INTEGER NOT NULL DEFAULT 0,total INTEGER,listed INTEGER NOT NULL DEFAULT 0,updated_at TEXT NOT NULL,PRIMARY KEY(shop_id,source))");
            s.execute("CREATE TABLE IF NOT EXISTS znack_catalog_version_cards(shop_id INTEGER NOT NULL,source TEXT NOT NULL,position INTEGER NOT NULL,good_id TEXT NOT NULL,etag TEXT NOT NULL,card_json TEXT,observed_at TEXT,PRIMARY KEY(shop_id,source,good_id),UNIQUE(shop_id,source,position),FOREIGN KEY(shop_id,source) REFERENCES znack_catalog_version_scans(shop_id,source) ON DELETE CASCADE)");
        }
        try(var p=c.prepareStatement("SELECT offset,total,listed,updated_at FROM znack_catalog_version_scans WHERE shop_id=? AND source=?")){
            bind(p);try(var r=p.executeQuery()){
                if(r.next()&&resume&&Instant.parse(r.getString(4)).isAfter(Instant.now().minusSeconds(7200)))return new State(r.getInt(1),r.getObject(2)==null?null:r.getInt(2),r.getBoolean(3));
            }
        }
        clear(c);
        try(var p=c.prepareStatement("INSERT INTO znack_catalog_version_scans(shop_id,source,updated_at) VALUES(?,?,?)")){bind(p);p.setString(3,Instant.now().toString());p.executeUpdate();}
        return new State(0,null,false);
    });}
    void append(State expected,int total,List<Entry> entries){write(c->{
        try(var p=c.prepareStatement("INSERT INTO znack_catalog_version_cards(shop_id,source,position,good_id,etag) VALUES(?,?,?,?,?)")){
            int position=expected.offset();for(var entry:entries){bind(p);p.setInt(3,position++);p.setString(4,entry.id());p.setString(5,entry.etag());p.addBatch();}p.executeBatch();
        }
        try(var p=c.prepareStatement("UPDATE znack_catalog_version_scans SET offset=?,total=?,listed=?,updated_at=? WHERE shop_id=? AND source=? AND offset=?")){
            int next=expected.offset()+entries.size();p.setInt(1,next);p.setInt(2,total);p.setBoolean(3,next==total);p.setString(4,Instant.now().toString());p.setInt(5,shop);p.setString(6,source);p.setInt(7,expected.offset());if(p.executeUpdate()!=1)throw new SQLException("CATALOG_INDEX_CHANGED");
        }return null;
    });}
    List<Entry> entries(boolean pending){
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT good_id,etag,card_json,observed_at FROM znack_catalog_version_cards WHERE shop_id=? AND source=?"+(pending?" AND card_json IS NULL":"")+" ORDER BY position"+(pending?" LIMIT 25":""))){
            bind(p);var out=new ArrayList<Entry>();try(var r=p.executeQuery()){while(r.next())out.add(new Entry(r.getString(1),r.getString(2),r.getString(3)==null?null:JsonParser.parseString(r.getString(3)).getAsJsonObject(),r.getString(4)==null?null:Instant.parse(r.getString(4))));}return List.copyOf(out);
        }catch(SQLException error){throw new IllegalStateException("CATALOG_INDEX_STORAGE",error);}
    }
    int pendingCount(){try(var c=Database.getConnection();var p=c.prepareStatement("SELECT count(*) FROM znack_catalog_version_cards WHERE shop_id=? AND source=? AND card_json IS NULL")){bind(p);try(var r=p.executeQuery()){r.next();return r.getInt(1);}}catch(SQLException error){throw new IllegalStateException("CATALOG_INDEX_STORAGE",error);}}
    void observed(List<Entry> entries){write(c->{
        try(var p=c.prepareStatement("UPDATE znack_catalog_version_cards SET card_json=?,observed_at=? WHERE shop_id=? AND source=? AND good_id=? AND etag=? AND card_json IS NULL")){
            for(var entry:entries){p.setString(1,entry.card().toString());p.setString(2,entry.observedAt().toString());p.setInt(3,shop);p.setString(4,source);p.setString(5,entry.id());p.setString(6,entry.etag());if(p.executeUpdate()!=1)throw new SQLException("CATALOG_INDEX_CHANGED");}
        }
        try(var p=c.prepareStatement("UPDATE znack_catalog_version_scans SET updated_at=? WHERE shop_id=? AND source=?")){p.setString(1,Instant.now().toString());p.setInt(2,shop);p.setString(3,source);p.executeUpdate();}return null;
    });}
    void clear(){write(c->{clear(c);return null;});}
    private void clear(Connection c)throws SQLException{
        // Explicit child deletion also supports legacy connections without foreign_keys enabled.
        for(String table:List.of("znack_catalog_version_cards","znack_catalog_version_scans"))try(var p=c.prepareStatement("DELETE FROM "+table+" WHERE shop_id=? AND source=?")){bind(p);p.executeUpdate();}
    }
    private void bind(PreparedStatement p)throws SQLException{p.setInt(1,shop);p.setString(2,source);}
    private static <T>T write(SqliteWriteTransaction.Work<T> work){try{return SqliteWriteTransaction.run(work);}catch(SQLException error){throw new IllegalStateException("CATALOG_INDEX_STORAGE",error);}}
}
