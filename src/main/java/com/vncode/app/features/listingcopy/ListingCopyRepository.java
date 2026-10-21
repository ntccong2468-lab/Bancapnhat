package com.vncode.app.features.listingcopy;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductJson;
import java.sql.*;
import java.util.*;

/** Atomic claims precede all outgoing writes. Payloads contain product data, never shop keys. */
public final class ListingCopyRepository {
    public void insert(CopyJob job){try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO listing_copy_jobs(id,source_shop_id,destination_shop_id,marketplace,owner,state,approved_hash,payload,updated_at,summary,source_owner) VALUES(?,?,?,?,?,?,?,?,?,?,?)")){
        p.setString(1,job.id());p.setInt(2,job.sourceShopId());p.setInt(3,job.destinationShopId());p.setString(4,job.marketplace().name());p.setString(5,job.destinationOwner());p.setString(6,job.state().name());p.setString(7,null);p.setString(8,ProductJson.GSON.toJson(job));p.setString(9,job.updatedAt().toString());p.setString(10,ProductJson.GSON.toJson(job.summary()));p.setString(11,job.sourceOwner());p.executeUpdate();
    }catch(SQLException e){throw new IllegalStateException("Cannot save listing draft");}}
    public Optional<CopyJob> find(int shop,String id){try(var c=Database.getConnection()){return find(c,shop,id);}catch(SQLException e){throw new IllegalStateException("Cannot read listing job");}}
    private Optional<CopyJob> find(Connection c,int shop,String id)throws SQLException{try(var p=c.prepareStatement("SELECT state,payload FROM listing_copy_jobs WHERE destination_shop_id=? AND id=?")){p.setInt(1,shop);p.setString(2,id);try(var r=p.executeQuery()){if(!r.next())return Optional.empty();return Optional.of(ProductJson.GSON.fromJson(r.getString(2),CopyJob.class).state(CopyState.valueOf(r.getString(1))));}}}
    public List<CopyJob> list(int shop,String owner){return summaries("destination_shop_id=? AND owner=?",shop,owner);}
    public List<CopyJob> listSource(int shop,String owner){return summaries("source_shop_id=? AND source_owner=?",shop,owner);}
    private List<CopyJob> summaries(String predicate,int shop,String owner){try(var c=Database.getConnection();var p=c.prepareStatement("SELECT state,summary FROM listing_copy_jobs WHERE "+predicate+" ORDER BY updated_at DESC LIMIT 100")){p.setInt(1,shop);p.setString(2,owner);try(var r=p.executeQuery()){var list=new ArrayList<CopyJob>();while(r.next())list.add(ProductJson.GSON.fromJson(r.getString(2),CopyJob.class).state(CopyState.valueOf(r.getString(1))));return List.copyOf(list);}}catch(SQLException e){throw new IllegalStateException("Cannot read listing summaries");}}
    public boolean stage(CopyJob prior,CopyJob next){try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE listing_copy_jobs SET state=?,approved_hash=?,payload=?,updated_at=?,summary=? WHERE destination_shop_id=? AND id=? AND updated_at=? AND state IN ('DRAFT','VALIDATED','AWAITING_CONFIRMATION','NEEDS_DATA')")){
        p.setString(1,next.state().name());p.setString(2,next.preview()==null?null:next.preview().requestHash());p.setString(3,ProductJson.GSON.toJson(next));p.setString(4,next.updatedAt().toString());p.setString(5,ProductJson.GSON.toJson(next.summary()));p.setInt(6,next.destinationShopId());p.setString(7,next.id());p.setString(8,prior.updatedAt().toString());return p.executeUpdate()==1;
    }catch(SQLException e){throw new IllegalStateException("Cannot update listing preview");}}
    public boolean claim(int shop,String id,String hash){try(var c=Database.getConnection()){
        c.setAutoCommit(false);try(var p=c.prepareStatement("UPDATE listing_copy_jobs SET state='SENDING' WHERE destination_shop_id=? AND id=? AND approved_hash=? AND state='AWAITING_CONFIRMATION'")){p.setInt(1,shop);p.setString(2,id);p.setString(3,hash);if(p.executeUpdate()!=1){c.rollback();return false;}}
        var job=find(c,shop,id).orElseThrow();if(job.preview()==null||!job.preview().publishable()||!job.preview().requestHash().equals(hash)){c.rollback();return false;}
        try(var p=c.prepareStatement("INSERT INTO listing_copy_articles VALUES(?,?,?,?)")){for(String a:job.preview().articles()){p.setInt(1,shop);p.setString(2,job.marketplace().name());p.setString(3,a);p.setString(4,id);p.addBatch();}p.executeBatch();}catch(SQLException conflict){c.rollback();return false;}
        event(c,job,"CARD");c.commit();return true;
    }catch(SQLException e){throw new IllegalStateException("Cannot claim listing mutation");}}
    public boolean checkpoint(CopyJob job,Set<CopyState> expected){try(var c=Database.getConnection()){
        c.setAutoCommit(false);var existing=find(c,job.destinationShopId(),job.id());if(existing.isEmpty()||!expected.contains(existing.get().state())||phase(job)<phase(existing.get())){c.rollback();return false;}
        try(var p=c.prepareStatement("UPDATE listing_copy_jobs SET state=?,payload=?,updated_at=?,summary=? WHERE destination_shop_id=? AND id=? AND state=?")){p.setString(1,job.state().name());p.setString(2,ProductJson.GSON.toJson(job));p.setString(3,job.updatedAt().toString());p.setString(4,ProductJson.GSON.toJson(job.summary()));p.setInt(5,job.destinationShopId());p.setString(6,job.id());p.setString(7,existing.get().state().name());if(p.executeUpdate()!=1){c.rollback();return false;}}
        event(c,job,job.submission()==null?"CARD":job.submission().phase());if(job.state()==CopyState.RECONCILED||job.state()==CopyState.FAILED)try(var p=c.prepareStatement("DELETE FROM listing_copy_articles WHERE job_id=?")){p.setString(1,job.id());p.executeUpdate();}c.commit();return true;
    }catch(SQLException e){throw new IllegalStateException("Cannot checkpoint listing job");}}
    /** Releases only this owner's claimed CARD request when no HTTP submission has started. */
    public boolean releaseUnsent(CopyJob sending,String hash){
        if(sending.state()!=CopyState.SENDING||sending.submission()!=null||sending.preview()==null||!sending.preview().requestHash().equals(hash))return false;
        var next=sending.with(CopyState.AWAITING_CONFIRMATION,sending.source(),sending.options(),sending.preview(),null,sending.issues());
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);
            try(var p=c.prepareStatement("""
              UPDATE listing_copy_jobs SET state='AWAITING_CONFIRMATION',payload=?,updated_at=?,summary=?
              WHERE destination_shop_id=? AND id=? AND owner=? AND source_owner=? AND state='SENDING' AND approved_hash=?
                AND json_extract(payload,'$.submission') IS NULL
              """)){p.setString(1,ProductJson.GSON.toJson(next));p.setString(2,next.updatedAt().toString());p.setString(3,ProductJson.GSON.toJson(next.summary()));p.setInt(4,next.destinationShopId());p.setString(5,next.id());p.setString(6,next.destinationOwner());p.setString(7,next.sourceOwner());p.setString(8,hash);if(p.executeUpdate()!=1){c.rollback();return false;}}
            try(var p=c.prepareStatement("DELETE FROM listing_copy_articles WHERE job_id=?")){p.setString(1,next.id());p.executeUpdate();}
            event(c,next,"CARD");c.commit();return true;
        }catch(SQLException e){throw new IllegalStateException("Cannot release unsent listing copy");}
    }
    public boolean claimAssets(CopyJob prior,RemoteSubmission submission){
        if(prior.submission()!=null&&!prior.submission().phase().equals("CARD"))return false;var next=prior.with(CopyState.SENDING,prior.source(),prior.options(),prior.preview(),submission,List.of());
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);try(var p=c.prepareStatement("""
              UPDATE listing_copy_jobs SET state='SENDING',payload=?,updated_at=?,summary=? WHERE destination_shop_id=? AND id=? AND updated_at=? AND state IN ('REMOTE_PROCESSING','RECONCILE_REQUIRED') AND (json_extract(payload,'$.submission.phase') IS NULL OR json_extract(payload,'$.submission.phase')='CARD')
              """)){p.setString(1,ProductJson.GSON.toJson(next));p.setString(2,next.updatedAt().toString());p.setString(3,ProductJson.GSON.toJson(next.summary()));p.setInt(4,prior.destinationShopId());p.setString(5,prior.id());p.setString(6,prior.updatedAt().toString());if(p.executeUpdate()!=1){c.rollback();return false;}}
            event(c,next,"ASSETS_SENDING");c.commit();return true;
        }catch(SQLException e){throw new IllegalStateException("Cannot claim listing assets");}
    }
    public void recoverIncomplete(){try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE listing_copy_jobs SET state='RECONCILE_REQUIRED' WHERE state='SENDING'")){p.executeUpdate();}catch(SQLException e){throw new IllegalStateException("Cannot recover listing jobs");}}
    private static void event(Connection c,CopyJob job,String phase)throws SQLException{try(var p=c.prepareStatement("INSERT INTO listing_copy_events(job_id,state,phase,created_at) VALUES(?,?,?,?)")){p.setString(1,job.id());p.setString(2,job.state().name());p.setString(3,phase);p.setString(4,java.time.Instant.now().toString());p.executeUpdate();}}
    private static int phase(CopyJob j){return j.submission()==null?0:switch(j.submission().phase()){case "ASSETS_SENDING"->1;case "ASSETS_SENT"->2;default->0;};}
}
