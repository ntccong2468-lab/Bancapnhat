package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class SyncStreamStore {
    public record State(String owner,long revision,boolean requested,String checkpoint,Instant lastSuccess,Instant lastComplete,Instant next,int failures,String error) {}
    public State ensure(Shop shop,String stream,Instant now) {
        String owner=ListingIdentity.fingerprint(shop);
        try(var c=Database.getConnection()) {
            var current=read(c,shop.getId(),stream);if(current!=null&&current.owner().equals(owner))return current;
            try(var p=c.prepareStatement("INSERT INTO sync_streams(shop_id,stream,owner,next_attempt) VALUES(?,?,?,?) ON CONFLICT(shop_id,stream) DO UPDATE SET owner=excluded.owner,revision=sync_streams.revision+1,requested=1,checkpoint='',last_success=NULL,last_complete=NULL,failures=0,error_code='',next_attempt=excluded.next_attempt WHERE sync_streams.owner<>excluded.owner")){
                p.setInt(1,shop.getId());p.setString(2,stream);p.setString(3,owner);p.setString(4,now.toString());p.executeUpdate();
            }
            var state=read(c,shop.getId(),stream);if(state==null)throw new SQLException("Missing stream");return state;
        }catch(SQLException e){throw new IllegalStateException("SYNC_STORAGE",e);}
    }
    private static State read(Connection c,int shop,String stream)throws SQLException{
        try(var p=c.prepareStatement("SELECT * FROM sync_streams WHERE shop_id=? AND stream=?")){
            p.setInt(1,shop);p.setString(2,stream);try(var r=p.executeQuery()){return r.next()?new State(r.getString("owner"),r.getLong("revision"),r.getInt("requested")!=0,r.getString("checkpoint"),time(r.getString("last_success")),time(r.getString("last_complete")),time(r.getString("next_attempt")),r.getInt("failures"),r.getString("error_code")):null;}
        }
    }
    /** Safe to call inside a publication transaction: this is the durable handoff. */
    public static void request(Connection c,int shopId,String stream) throws SQLException {
        String owner;
        try(var p=c.prepareStatement("SELECT marketplace FROM shops WHERE id=?")){p.setInt(1,shopId);try(var r=p.executeQuery()){if(!r.next())return;owner=ListingIdentity.fingerprint(c,shopId,com.vncode.app.integration.marketplace.Marketplace.valueOf(r.getString(1)));}}
        try(var p=c.prepareStatement("INSERT INTO sync_streams(shop_id,stream,owner,requested,next_attempt) VALUES(?,?,?,1,?) ON CONFLICT(shop_id,stream) DO UPDATE SET requested=1,revision=sync_streams.revision+1")){
            p.setInt(1,shopId);p.setString(2,stream);p.setString(3,owner);p.setString(4,Instant.now().toString());p.executeUpdate();
        }
    }
    public void request(Shop shop,String stream){try(var c=Database.getConnection()){ListingIdentity.require(shop);request(c,shop.getId(),stream);}catch(SQLException e){throw new IllegalStateException("SYNC_STORAGE",e);}}
    public void attempt(Shop shop,String stream,State expected,Instant now){update(shop,stream,expected,"last_attempt=?",p->p.setString(1,now.toString()));}
    public void finish(Shop shop,String stream,State expected,String checkpoint,boolean complete,Instant now,Instant next,SyncFailure error){
        update(shop,stream,expected,"checkpoint=?,last_success=CASE WHEN ?=1 THEN ? ELSE last_success END,last_complete=CASE WHEN ?=1 THEN ? ELSE last_complete END,next_attempt=?,failures=?,error_code=?,requested=CASE WHEN revision=? THEN 0 ELSE requested END",p->{
            p.setString(1,checkpoint);p.setInt(2,error==null?1:0);p.setString(3,now.toString());p.setInt(4,error==null&&complete?1:0);p.setString(5,now.toString());p.setString(6,next.toString());p.setInt(7,error==null?0:expected.failures()+1);p.setString(8,error==null?"":error.code());p.setLong(9,expected.revision());
        });
    }
    private void update(Shop shop,String stream,State expected,String assignments,Binder binder){
        try(var c=Database.getConnection()){
            ListingIdentity.require(shop);
            try(var p=c.prepareStatement("UPDATE sync_streams SET "+assignments+" WHERE shop_id=? AND stream=? AND owner=?")){
                binder.bind(p);int n=p.getParameterMetaData().getParameterCount();p.setInt(n-2,shop.getId());p.setString(n-1,stream);p.setString(n,expected.owner());p.executeUpdate();
            }
        }catch(SQLException e){throw new IllegalStateException("SYNC_STORAGE",e);}
    }
    private static Instant time(String s){return s==null?null:Instant.parse(s);}
    private interface Binder{void bind(PreparedStatement p)throws SQLException;}
}
