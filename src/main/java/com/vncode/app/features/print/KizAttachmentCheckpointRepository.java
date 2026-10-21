package com.vncode.app.features.print;

import com.vncode.app.config.Database;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;

/** Additive side table: stores hashes and phases, never raw marks, tokens or upstream bodies. */
final class KizAttachmentCheckpointRepository implements KizAttachmentCoordinator.AttachmentJournal {
    private boolean initialized;
    private synchronized void initialize()throws IOException{
        if(initialized)return;
        try(Connection c=Database.getConnection();Statement statement=c.createStatement()){
            statement.execute("""
                CREATE TABLE IF NOT EXISTS wb_kiz_attachment_checkpoints(
                  shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                  order_id INTEGER NOT NULL CHECK(order_id>0),
                  supply_id TEXT NOT NULL,code_hash TEXT NOT NULL CHECK(length(code_hash)=64),
                  phase TEXT NOT NULL CHECK(phase IN ('REMOVE_PENDING','READ_EMPTY','ATTACH_PENDING','CONFIRMED','RECONCILE','REJECTED','OTHER_CODE')),
                  updated_at TEXT NOT NULL,PRIMARY KEY(shop_id,order_id))
                """);initialized=true;
        }catch(SQLException|RuntimeException failure){throw new IOException("KIZ_CHECKPOINT_UNAVAILABLE");}
    }
    public KizAttachmentCoordinator.Checkpoint read(KizAttachmentCoordinator.OrderKey key)throws IOException{
        initialize();
        try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("SELECT code_hash,phase FROM wb_kiz_attachment_checkpoints WHERE shop_id=? AND order_id=?")){
            ps.setInt(1,key.shopId());ps.setLong(2,key.orderId());
            try(ResultSet rs=ps.executeQuery()){return rs.next()?new KizAttachmentCoordinator.Checkpoint(rs.getString(1),rs.getString(2)):null;}
        }catch(SQLException|RuntimeException failure){throw new IOException("KIZ_CHECKPOINT_UNAVAILABLE");}
    }
    public void write(KizAttachmentCoordinator.OrderKey key,String supply,String codeHash,String phase)throws IOException{
        initialize();
        try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("""
                INSERT INTO wb_kiz_attachment_checkpoints(shop_id,order_id,supply_id,code_hash,phase,updated_at) VALUES(?,?,?,?,?,?)
                ON CONFLICT(shop_id,order_id) DO UPDATE SET supply_id=excluded.supply_id,code_hash=excluded.code_hash,phase=excluded.phase,updated_at=excluded.updated_at
                """)){
            ps.setInt(1,key.shopId());ps.setLong(2,key.orderId());ps.setString(3,supply);ps.setString(4,codeHash);ps.setString(5,phase);ps.setString(6,Instant.now().toString());ps.executeUpdate();
        }catch(SQLException|RuntimeException failure){throw new IOException("KIZ_CHECKPOINT_UNAVAILABLE");}
    }
    /** Bounded scoped preflight, so a restart never prints/consumes another mark over an uncertain write. */
    void requireNoPending(int shopId,java.util.List<Long> orderIds)throws IOException{
        if(orderIds.isEmpty())return;initialize();
        try(Connection c=Database.getConnection()){
            for(int start=0;start<orderIds.size();start+=900){
                var batch=orderIds.subList(start,Math.min(start+900,orderIds.size()));
                String placeholders=String.join(",",java.util.Collections.nCopies(batch.size(),"?"));
                try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM wb_kiz_attachment_checkpoints WHERE shop_id=? AND phase IN ('REMOVE_PENDING','ATTACH_PENDING','RECONCILE') AND order_id IN ("+placeholders+") LIMIT 1")){
                    ps.setInt(1,shopId);for(int index=0;index<batch.size();index++)ps.setLong(index+2,batch.get(index));
                    try(ResultSet rs=ps.executeQuery()){if(rs.next())throw new IOException("KIZ_RECONCILIATION_REQUIRED");}
                }
            }
        }catch(SQLException|RuntimeException failure){throw new IOException("KIZ_CHECKPOINT_UNAVAILABLE");}
    }
}
