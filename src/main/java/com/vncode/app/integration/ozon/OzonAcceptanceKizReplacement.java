package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.kiz.*;
import com.vncode.app.features.kiz.status.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Kiz;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Durable replacement of an exactly rejected mark, retaining its selected stock provenance. */
final class OzonAcceptanceKizReplacement {
    record Ticket(long id, String state, JsonObject payload,String oldHash,String newHash,boolean remoteRejected) {
        @Override public String toString(){return "AcceptanceReplacement["+id+","+state+",payload=redacted]";}
    }
    static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS ozon_accept_kiz_replacements(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,job_id INTEGER NOT NULL,
                  owner TEXT NOT NULL,old_code_hash TEXT NOT NULL,new_code_hash TEXT NOT NULL,
                  payload TEXT NOT NULL,state TEXT NOT NULL,remote_rejected INTEGER NOT NULL,
                  created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
                  FOREIGN KEY(job_id) REFERENCES ozon_exemplar_jobs(id) ON DELETE CASCADE)
                """);
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_ozon_accept_kiz_active ON ozon_accept_kiz_replacements(job_id) WHERE state IN ('PREPARED','SENDING','AWAITING')");
        }
    }
    Ticket active(Shop shop,OzonExemplarJob job) {
        if(job==null)return null;ListingIdentity.require(shop);
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT id,state,payload,old_code_hash,new_code_hash,remote_rejected FROM ozon_accept_kiz_replacements WHERE job_id=? AND owner=? AND state IN ('PREPARED','SENDING','AWAITING')")) {
            p.setLong(1,job.id());p.setString(2,ListingIdentity.fingerprint(shop));
            try(var r=p.executeQuery()){return r.next()?new Ticket(r.getLong(1),r.getString(2),JsonParser.parseString(r.getString(3)).getAsJsonObject(),r.getString(4),r.getString(5),r.getBoolean(6)):null;}
        }catch(SQLException e){throw storage(e);}
    }
    /** Only an exact mark observation closes an attempt. HTTP 200 alone cannot do so. */
    void settle(Shop shop,OzonExemplarJob job,List<Kn3Mark> marks) {
        try{SqliteWriteTransaction.run(c->{requireOwner(c,shop);
            try(var p=c.prepareStatement("SELECT id,new_code_hash,payload FROM ozon_accept_kiz_replacements WHERE job_id=? AND owner=? AND state IN ('PREPARED','SENDING','AWAITING')")){
                p.setLong(1,job.id());p.setString(2,ListingIdentity.fingerprint(shop));
                try(var r=p.executeQuery()){if(r.next()){
                    String hash=r.getString(2);var payload=JsonParser.parseString(r.getString(3)).getAsJsonObject();
                    for(var mark:marks)if(mark.fingerprint().equals(hash)&&sameSlot(payload,mark)
                            &&(mark.state()==Kn3Mark.State.REJECTED||mark.state()==Kn3Mark.State.ACCEPTED)){
                        update(c,shop,r.getLong(1),mark.state()==Kn3Mark.State.REJECTED?"REJECTED":"DONE");break;
                    }
                }}
            }return null;
        });}catch(SQLException e){throw storage(e);}
    }
    boolean prepare(Shop shop,OzonPostingDto posting,OzonExemplarJob job,List<Kn3Mark> marks,
                    JsonObject base,String remoteAnchor,KizSourceSelection sources,OzonExemplarJobRepository jobs)throws IOException {
        ListingIdentity.require(shop);
        if(job.shopId()!=shop.getId()||!job.postingNumber().equals(posting.postingNumber()))throw new SecurityException("OZON_ACCEPT_KIZ_BINDING_CHANGED");
        var bindings=jobs.bindings(job.id());
        if(marks.size()!=bindings.size()||bindings.stream().anyMatch(b->marks.stream().noneMatch(m->
                posting.postingNumber().equals(m.orderId())&&matches(b,m))))return false;
        var target=marks.stream().filter(m->m.state()==Kn3Mark.State.REJECTED&&bindings.stream().anyMatch(b->matches(b,m))).findFirst().orElse(null);
        if(target==null)return false;
        var old=bindings.stream().filter(b->matches(b,target)).findFirst().orElseThrow();
        final boolean purchased;
        final boolean downloaded;
        final String originalGtin;
        try(var c=Database.getConnection()){
            requireOwner(c,shop);
            var origin=origin(c,shop,job,old);
            downloaded=origin.downloaded();
            purchased=!origin.imported()&&(downloaded||sources!=null&&sources.purchased()&&!sources.sellerImported());
            originalGtin=origin.gtin();
            // A paid terminal receipt cannot authorize a second remote assignment.
            if(purchased&&job.stage()==OzonExemplarJobStage.ACCEPTED)return false;
            // A missing source snapshot does not turn known downloaded stock into a
            // seller category. An explicitly seller-only action may reconcile its
            // existing mark elsewhere, but cannot allocate a paid replacement here.
            if(purchased&&sources!=null&&!sources.purchased())return false;
            if(!purchased){
                if(sources!=null&&!sources.sellerImported())return false;
                // Keep the existing seller-imported category rule, independent of GTIN.
                var nativeIds=OzonCategoryStock.verifiedProducts(c,shop.getId(),posting.items());
                var item=posting.items().stream().filter(i->i.itemIndex()==old.itemIndex()).findFirst().orElseThrow();
                String product=nativeIds.get(item.sku());
                if(product==null||!new KizStockCategoryRepository().bindings(shop,List.of(product)).containsKey(product))return false;
            }
        }catch(SQLException e){throw storage(e);}
        // A definitive owned rejection is quarantined even when no paid replacement is
        // available. It must never re-enter the pool while a later purchase is pending.
        new Kn3Repository().observe(shop,posting.postingNumber(),marks);
        settle(shop,job,marks);
        try{return SqliteWriteTransaction.run(c->{
            requireOwner(c,shop);
            if(active(shop,job)!=null)throw new IllegalStateException("OZON_ACCEPT_KIZ_PENDING");
            KizCategoryAllocator.Selection categorySelection=null;
            Kiz code;
            if(purchased){
                code=purchasedCandidate(c,shop,posting,job,old,originalGtin,downloaded);
                if(code==null)return false;
            }else{
                var nativeIds=OzonCategoryStock.verifiedProducts(c,shop.getId(),posting.items());
                var item=posting.items().stream().filter(i->i.itemIndex()==old.itemIndex()).findFirst().orElseThrow();
                String product=nativeIds.get(item.sku());if(product==null)throw new SecurityException("KIZ_CATEGORY_PRODUCT_CHANGED");
                var selected=KizCategoryAllocator.select(c,shop.getId(),Marketplace.OZON,ListingIdentity.fingerprint(shop),
                        List.of(new KizCategoryAllocator.Demand(OzonCategoryStock.key(posting.postingNumber(),old.itemIndex()),product,1)));
                if(selected.isEmpty())return false;
                categorySelection=selected.getFirst();code=categorySelection.code();
            }
            String now=Instant.now().toString(),token="ozon:"+job.id();
            var payload=base.deepCopy();int changed=0;
            for(var p:payload.getAsJsonArray("products")){
                var row=p.getAsJsonObject();if(!target.productId().equals(OzonJson.text(row,"product_id")))continue;
                for(var e:row.getAsJsonArray("exemplars")){
                    var exemplar=e.getAsJsonObject();if(!target.exemplarId().equals(OzonJson.text(exemplar,"exemplar_id")))continue;
                    for(var m:exemplar.getAsJsonArray("marks")){
                        var mark=m.getAsJsonObject();if("mandatory_mark".equals(OzonJson.text(mark,"mark_type"))&&target.fingerprint().equals(Kn3Mark.fingerprint(OzonJson.text(mark,"mark")))){
                            mark.addProperty("mark",code.getCode());changed++;
                        }
                    }
                }
            }
            if(changed!=1)throw new SecurityException("OZON_ACCEPT_KIZ_REPLACEMENT_CHANGED");
            try(var p=c.prepareStatement("UPDATE kiz_codes SET status='RESERVED',reservation_token=?,reserved_at=?,reservation_recoverable=0,updated_at=? WHERE id=? AND shop_id=? AND status='AVAILABLE'")){
                p.setString(1,token);p.setString(2,now);p.setString(3,now);p.setInt(4,code.getId());p.setInt(5,shop.getId());if(p.executeUpdate()!=1)throw new IllegalStateException("KIZ_CATEGORY_STOCK_CHANGED");
            }
            // Paid marks keep exact-GTIN provenance; they must not gain category-only privileges.
            if(categorySelection!=null)KizCategoryAllocator.recordProof(c,shop.getId(),ListingIdentity.fingerprint(shop),token,categorySelection);
            try(var p=c.prepareStatement("UPDATE ozon_exemplars SET kiz_id=?,check_status=NULL,updated_at=? WHERE job_id=? AND item_index=? AND exemplar_index=? AND kiz_id=?")){
                p.setInt(1,code.getId());p.setString(2,now);p.setLong(3,job.id());p.setInt(4,old.itemIndex());p.setInt(5,old.exemplarIndex());p.setLong(6,old.kizId());if(p.executeUpdate()!=1)throw new IllegalStateException("OZON_ACCEPT_KIZ_BINDING_CHANGED");
            }
            try(var p=c.prepareStatement("INSERT INTO ozon_accept_kiz_replacements(job_id,owner,old_code_hash,new_code_hash,payload,state,remote_rejected,created_at,updated_at) VALUES(?,?,?,?,?,'PREPARED',?,?,?)")){
                p.setLong(1,job.id());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,remoteAnchor.isBlank()?target.fingerprint():remoteAnchor);p.setString(4,Kn3Mark.fingerprint(code.getCode()));p.setString(5,payload.toString());p.setBoolean(6,!remoteAnchor.isBlank());p.setString(7,now);p.setString(8,now);p.executeUpdate();
            }
            // Legacy imported ACCEPTED jobs may be local PDF receipts. Only that
            // existing path can reopen here with an exact rejected bound code and
            // an owned reservation committed alongside its replacement intent.
            // The general terminal-state transition policy remains unchanged.
            if(job.stage()!=OzonExemplarJobStage.ACCEPTED)
                OzonExemplarStateMachine.requireTransition(job.stage(),OzonExemplarJobStage.VALIDATED);
            try(var p=c.prepareStatement("UPDATE ozon_exemplar_jobs SET stage='VALIDATED',request_fingerprint=?,safe_error_code=NULL,updated_at=? WHERE id=? AND shop_id=? AND stage=? AND request_fingerprint IS ?")){
                p.setString(1,ListingIdentity.hash(payload.toString()));p.setString(2,now);p.setLong(3,job.id());p.setInt(4,shop.getId());p.setString(5,job.stage().name());p.setString(6,job.requestFingerprint());
                if(p.executeUpdate()!=1)throw new IllegalStateException("OZON_ACCEPT_KIZ_JOB_CHANGED");
            }
            return true;
        });}catch(SQLException e){throw storage(e);}
    }
    private record Origin(String gtin,boolean imported,boolean downloaded){}
    private static Origin origin(Connection c,Shop shop,OzonExemplarJob job,OzonExemplarJobRepository.KizBinding old)throws SQLException{
        try(var p=c.prepareStatement("""
            SELECT k.gtin,(EXISTS(SELECT 1 FROM kiz_import_entries imported WHERE imported.code_id=k.id)
                OR EXISTS(SELECT 1 FROM kiz_orders imported_order WHERE imported_order.id=k.order_id
                   AND imported_order.shop_id=k.shop_id AND imported_order.local_status='IMPORTED')),
              (COALESCE(k.block_id,'')<>'' OR EXISTS(SELECT 1 FROM znack_purchase_pipelines pipe
                 WHERE pipe.shop_id=k.shop_id AND pipe.order_id=k.order_id)
                OR EXISTS(SELECT 1 FROM kiz_orders ord WHERE ord.id=k.order_id AND ord.shop_id=k.shop_id
                 AND COALESCE(ord.external_order_id,'')<>'' AND ord.local_status<>'IMPORTED'))
            FROM kiz_codes k JOIN ozon_exemplars e ON e.kiz_id=k.id AND e.shop_id=k.shop_id
            WHERE k.id=? AND k.shop_id=? AND e.job_id=? AND e.posting_number=?
              AND e.item_index=? AND e.exemplar_index=? AND e.product_id=? AND e.exemplar_id=? AND k.raw_code=?
            """)){
            p.setLong(1,old.kizId());p.setInt(2,shop.getId());p.setLong(3,job.id());p.setString(4,job.postingNumber());
            p.setInt(5,old.itemIndex());p.setInt(6,old.exemplarIndex());p.setString(7,old.productId());p.setString(8,old.exemplarId());p.setString(9,old.rawCode());
            try(var r=p.executeQuery()){
                if(!r.next())throw new SecurityException("OZON_ACCEPT_KIZ_BINDING_CHANGED");
                return new Origin(r.getString(1),r.getBoolean(2),r.getBoolean(3));
            }
        }
    }
    private static Kiz purchasedCandidate(Connection c,Shop shop,OzonPostingDto posting,OzonExemplarJob job,
            OzonExemplarJobRepository.KizBinding old,String originalGtin,boolean downloaded)throws SQLException{
        var current=origin(c,shop,job,old);
        if(current.imported()||downloaded&&!current.downloaded()||!Objects.equals(originalGtin,current.gtin()))throw new SecurityException("OZON_ACCEPT_KIZ_BINDING_CHANGED");
        var item=posting.items().stream().filter(i->i.itemIndex()==old.itemIndex()&&i.productId().equals(old.productId())
                &&old.exemplarIndex()>=0&&old.exemplarIndex()<i.quantity()).findFirst().orElse(null);
        if(item==null||!OzonCategoryStock.verifiedProducts(c,shop.getId(),posting.items()).containsKey(item.sku()))return null;
        if(!Objects.equals(originalGtin,new OzonProductGtinMappingRepository().findResolvedBySku(shop.getId()).get(item.sku())))return null;
        try(var p=c.prepareStatement("""
            SELECT k.id,k.raw_code FROM kiz_codes k
            WHERE k.shop_id=? AND k.gtin=? AND k.status='AVAILABLE' AND k.legal_status='IN_CIRCULATION'
              AND NOT EXISTS(SELECT 1 FROM kiz_import_entries imported WHERE imported.code_id=k.id) AND %s
              AND NOT EXISTS(SELECT 1 FROM kiz_orders imported_order WHERE imported_order.id=k.order_id
                 AND imported_order.shop_id=k.shop_id AND imported_order.local_status='IMPORTED')
              AND (?=0 OR COALESCE(k.block_id,'')<>'' OR EXISTS(SELECT 1 FROM znack_purchase_pipelines pipe
                 WHERE pipe.shop_id=k.shop_id AND pipe.order_id=k.order_id)
                OR EXISTS(SELECT 1 FROM kiz_orders ord WHERE ord.id=k.order_id AND ord.shop_id=k.shop_id
                 AND COALESCE(ord.external_order_id,'')<>'' AND ord.local_status<>'IMPORTED'))
            ORDER BY %s,k.source_priority,k.id
            """.formatted(KizWarehouseRepository.eligibleExact("k"),OzonPurchasedKizStock.downloadedSql("k")))){
            p.setInt(1,shop.getId());p.setString(2,originalGtin);p.setString(3,ListingIdentity.fingerprint(shop));
            p.setBoolean(4,downloaded);
            try(var r=p.executeQuery()){while(r.next()){
                String raw=r.getString(2);
                if(originalGtin.equals(rawGtin(raw)))return new Kiz(r.getInt(1),raw);
            }}
        }
        return null;
    }
    private static String rawGtin(String raw){
        String value=Objects.requireNonNullElse(KizService.scannerSafeCode(raw),"");
        int start=value.startsWith("(01)")?4:value.startsWith("01")?2:-1;
        if(start<0||value.length()<start+14)return "";
        String gtin=value.substring(start,start+14);
        return gtin.chars().allMatch(ch->ch>='0'&&ch<='9')?gtin:"";
    }
    /** Claim and persist the mutation boundary together. Only the winning caller may send. */
    boolean claim(Shop shop,OzonExemplarJob job,Ticket ticket,String fingerprint) {
        try{return SqliteWriteTransaction.run(c->{requireOwner(c,shop);String now=Instant.now().toString();
            try(var p=c.prepareStatement("UPDATE ozon_accept_kiz_replacements SET state='SENDING',payload=?,updated_at=? WHERE id=? AND job_id=? AND owner=? AND state='PREPARED'")){
                p.setString(1,ticket.payload().toString());p.setString(2,now);p.setLong(3,ticket.id());p.setLong(4,job.id());p.setString(5,ListingIdentity.fingerprint(shop));if(p.executeUpdate()!=1)return false;
            }
            try(var p=c.prepareStatement("UPDATE ozon_exemplar_jobs SET stage='SET_PENDING',request_fingerprint=?,mutation_attempted_at=?,safe_error_code=NULL,updated_at=? WHERE id=? AND shop_id=? AND stage IN ('VALIDATED','VERIFYING','SET_PENDING','RECONCILE_REQUIRED')")){
                p.setString(1,fingerprint);p.setString(2,now);p.setString(3,now);p.setLong(4,job.id());p.setInt(5,shop.getId());if(p.executeUpdate()!=1)throw new IllegalStateException("OZON_ACCEPT_KIZ_JOB_CHANGED");
            }
            try(var p=c.prepareStatement("INSERT INTO ozon_action_log(shop_id,action_type,posting_number,status,request_fingerprint,created_at) VALUES(?,'accept_kiz',?,'pending',?,?)")){
                p.setInt(1,shop.getId());p.setString(2,job.postingNumber());p.setString(3,fingerprint);p.setString(4,now);p.executeUpdate();
            }return true;
        });}catch(SQLException e){throw storage(e);}
    }
    void state(Shop shop,Ticket ticket,String state){
        try{SqliteWriteTransaction.run(c->{requireOwner(c,shop);update(c,shop,ticket.id(),state);return null;});}catch(SQLException e){throw storage(e);}
    }
    private static void update(Connection c,Shop shop,long id,String state)throws SQLException{
        try(var p=c.prepareStatement("UPDATE ozon_accept_kiz_replacements SET state=?,updated_at=? WHERE id=? AND owner=? AND job_id IN (SELECT id FROM ozon_exemplar_jobs WHERE shop_id=?)")){
            p.setString(1,state);p.setString(2,Instant.now().toString());p.setLong(3,id);p.setString(4,ListingIdentity.fingerprint(shop));p.setInt(5,shop.getId());p.executeUpdate();
        }
    }
    static boolean matches(OzonExemplarJobRepository.KizBinding b,Kn3Mark m){return b.productId().equals(m.productId())&&b.exemplarId().equals(m.exemplarId())&&Kn3Mark.fingerprint(b.rawCode()).equals(m.fingerprint());}
    private static boolean sameSlot(JsonObject payload,Kn3Mark mark){
        for(var p:payload.getAsJsonArray("products")){var product=p.getAsJsonObject();if(!mark.productId().equals(OzonJson.text(product,"product_id")))continue;
            for(var e:product.getAsJsonArray("exemplars"))if(mark.exemplarId().equals(OzonJson.text(e.getAsJsonObject(),"exemplar_id")))return true;
        }return false;
    }
    private static void requireOwner(Connection c,Shop shop)throws SQLException{
        if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),Marketplace.OZON)))throw new SecurityException("KIZ_CATEGORY_OWNER_CHANGED");
    }
    private static IllegalStateException storage(SQLException e){return new IllegalStateException("OZON_ACCEPT_KIZ_REPLACEMENT_STORAGE",e);}
}
