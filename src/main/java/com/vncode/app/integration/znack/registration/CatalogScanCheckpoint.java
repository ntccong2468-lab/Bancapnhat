package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import java.time.Instant;
import java.util.*;

/** A read cursor only: it can never allocate a GTIN, submit a feed or authorize signing. */
final class CatalogScanCheckpoint {
    record State(int nextPage,Integer total,boolean listed,List<String> gtins,int nextCard,
                 List<RegisteredGtin> cards,List<NationalCatalogGtinSource.CardIssue> issues,int pageSize){
        State(int nextPage,Integer total,boolean listed,List<String> gtins,int nextCard,List<RegisteredGtin> cards,List<NationalCatalogGtinSource.CardIssue> issues){this(nextPage,total,listed,gtins,nextCard,cards,issues,0);}
        State{gtins=List.copyOf(gtins);cards=List.copyOf(cards);issues=List.copyOf(issues);}
    }
    private static final Gson JSON=new GsonBuilder().registerTypeAdapter(Instant.class,
            new com.google.gson.TypeAdapter<Instant>(){
                public void write(com.google.gson.stream.JsonWriter w,Instant v)throws java.io.IOException{w.value(v.toString());}
                public Instant read(com.google.gson.stream.JsonReader r)throws java.io.IOException{return Instant.parse(r.nextString());}
            }).create();
    private static void write(SqliteWriteTransaction.Work<Void> work){
        try{SqliteWriteTransaction.run(work);}catch(java.sql.SQLException error){throw new IllegalStateException("CATALOG_CURSOR_STORAGE",error);}
    }
    private final int shop;private final String source;
    CatalogScanCheckpoint(int shop,String source){this.shop=shop;this.source=source;}
    private void ensure(){write(c->{try(var p=c.createStatement()){p.execute("CREATE TABLE IF NOT EXISTS znack_catalog_scan_cursor(shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,source TEXT NOT NULL,state_json TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(shop_id,source))");}return null;});}
    State load(){
        ensure();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT state_json FROM znack_catalog_scan_cursor WHERE shop_id=? AND source=? AND updated_at>?")){
            p.setInt(1,shop);p.setString(2,source);p.setString(3,Instant.now().minusSeconds(7200).toString());
            try(var r=p.executeQuery()){return r.next()?JSON.fromJson(r.getString(1),State.class):null;}
        }catch(java.sql.SQLException|JsonParseException error){throw new IllegalStateException("CATALOG_CURSOR_STORAGE",error);}
    }
    void save(State state){write(c->{try(var p=c.prepareStatement("INSERT INTO znack_catalog_scan_cursor VALUES(?,?,?,?) ON CONFLICT(shop_id,source) DO UPDATE SET state_json=excluded.state_json,updated_at=excluded.updated_at")){
        p.setInt(1,shop);p.setString(2,source);p.setString(3,JSON.toJson(state));p.setString(4,Instant.now().toString());p.executeUpdate();}return null;});}
    void clear(){write(c->{try(var p=c.prepareStatement("DELETE FROM znack_catalog_scan_cursor WHERE shop_id=? AND source=?")){p.setInt(1,shop);p.setString(2,source);p.executeUpdate();}return null;});}
}
