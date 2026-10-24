package com.vncode.app.features.print;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Saves stock references, never a second copy of raw KIZ or marketplace credentials. */
final class KizAttachmentResumeStore {
    private static final Gson JSON=new Gson();
    private record Ref(long order,int stock,boolean replace,String rejected,String hash){}
    record Pending(String supply,String name,List<OrderExportWorkflow.KizAttachmentAssignment> assignments){
        @Override public String toString(){return "PendingKizQueue[redacted]";}
    }
    static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.execute("""
            CREATE TABLE IF NOT EXISTS wb_kiz_attachment_resume(
              shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
              supply_id TEXT NOT NULL,owner TEXT NOT NULL,supply_name TEXT NOT NULL,
              refs_json TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(shop_id,supply_id))
            """);}
    }
    boolean save(Shop shop,String supply,String name,List<OrderExportWorkflow.KizAttachmentAssignment> assignments) {
        ListingIdentity.require(shop);
        try{return SqliteWriteTransaction.run(c->{
            initialize(c);var refs=new ArrayList<Ref>();
            try(var find=c.prepareStatement("SELECT raw_code FROM kiz_codes WHERE shop_id=? AND id=?")) {
                for(var assignment:assignments) {
                    var stock=assignment.sourceKiz();if(stock==null)return false;
                    find.setInt(1,shop.getId());find.setInt(2,stock.getId());
                    try(var row=find.executeQuery()) {
                        if(!row.next()||!hash(row.getString(1)).equals(hash(assignment.kizCode())))return false;
                    }
                    refs.add(new Ref(assignment.orderId(),stock.getId(),assignment.replaceExisting(),assignment.expectedRejectedHash(),hash(assignment.kizCode())));
                }
            }
            try(var write=c.prepareStatement("INSERT INTO wb_kiz_attachment_resume VALUES(?,?,?,?,?,?) ON CONFLICT(shop_id,supply_id) DO UPDATE SET owner=excluded.owner,supply_name=excluded.supply_name,refs_json=excluded.refs_json,updated_at=excluded.updated_at")) {
                write.setInt(1,shop.getId());write.setString(2,supply);write.setString(3,ListingIdentity.fingerprint(shop));write.setString(4,name);write.setString(5,JSON.toJson(refs));write.setString(6,Instant.now().toString());write.executeUpdate();
            }return true;
        });}catch(SQLException e){throw new IllegalStateException("KIZ_CHECKPOINT_UNAVAILABLE",e);}
    }
    List<Pending> pending(Shop shop) {
        ListingIdentity.require(shop);
        try(var c=Database.getConnection()) {
            try(var s=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='wb_kiz_attachment_resume'");var r=s.executeQuery()){if(!r.next())return List.of();}
            var pending=new ArrayList<Pending>();
            try(var select=c.prepareStatement("SELECT supply_id,supply_name,refs_json FROM wb_kiz_attachment_resume WHERE shop_id=? AND owner=?")) {
                select.setInt(1,shop.getId());select.setString(2,ListingIdentity.fingerprint(shop));
                try(var rows=select.executeQuery()){while(rows.next()) {
                    var refs=JSON.fromJson(rows.getString(3),Ref[].class);if(refs.length>100000)continue;
                    var assignments=new ArrayList<OrderExportWorkflow.KizAttachmentAssignment>();boolean complete=true;
                    try(var find=c.prepareStatement("SELECT raw_code,reservation_token FROM kiz_codes stock WHERE shop_id=? AND id=? AND COALESCE(legal_status,'')!='REJECTED' AND "+com.vncode.app.features.kiz.KizRejectedStock.eligible("stock"))) {
                        for(var ref:refs) {
                            find.setInt(1,shop.getId());find.setInt(2,ref.stock());
                            try(var row=find.executeQuery()) {
                                if(!row.next()||!hash(row.getString(1)).equals(ref.hash())){complete=false;break;}
                                var code=new Kiz(ref.stock(),row.getString(1),row.getString(2));
                                assignments.add(new OrderExportWorkflow.KizAttachmentAssignment(ref.order(),code.getCode(),code,ref.replace(),ref.rejected()));
                            }
                        }
                    }
                    if(complete&&!assignments.isEmpty())pending.add(new Pending(rows.getString(1),rows.getString(2),List.copyOf(assignments)));
                }}
            }return List.copyOf(pending);
        }catch(SQLException e){throw new IllegalStateException("KIZ_CHECKPOINT_UNAVAILABLE",e);}
    }
    void remove(Shop shop,String supply) {
        ListingIdentity.require(shop);
        try{SqliteWriteTransaction.run(c->{initialize(c);try(var p=c.prepareStatement("DELETE FROM wb_kiz_attachment_resume WHERE shop_id=? AND supply_id=? AND owner=?")){
            p.setInt(1,shop.getId());p.setString(2,supply);p.setString(3,ListingIdentity.fingerprint(shop));p.executeUpdate();}return null;});
        }catch(SQLException e){throw new IllegalStateException("KIZ_CHECKPOINT_UNAVAILABLE",e);}
    }
    private static String hash(String code){return com.vncode.app.features.kiz.status.Kn3Mark.fingerprint(code);}
}
