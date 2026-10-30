package com.vncode.app.integration.ozon;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.features.kiz.KizSourceSelection;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Durable native package intent/receipt; no HTTP or KIZ reassignment in a write transaction. */
final class OzonUnitShipRepository {
    private static final Gson JSON=new Gson();
    record Receipt(String owner,OzonPostingDto source,String request,String fingerprint,List<String> children,String state,
            Map<String,OzonPostingDto> readbacks,int nextChild,KizSourceSelection sources) { }

    static void initialize(Connection connection)throws SQLException {
        try(var statement=connection.createStatement()){
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_unit_ship_receipts(
                        shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                        posting_number TEXT NOT NULL,owner TEXT NOT NULL,source_json TEXT NOT NULL,
                        request_json TEXT NOT NULL,request_fingerprint TEXT NOT NULL,
                        children_json TEXT NOT NULL DEFAULT '[]',state TEXT NOT NULL,updated_at TEXT NOT NULL,
                        parent_in_children INTEGER NOT NULL DEFAULT 1,
                        readback_json TEXT NOT NULL DEFAULT '{}',next_child INTEGER NOT NULL DEFAULT 0,source_mask INTEGER,
                        PRIMARY KEY(shop_id,posting_number))
                    """);
        }
        // Additive for an interrupted candidate installation; never rewrite the receipt.
        var columns=new HashSet<String>();try(var statement=connection.createStatement();var result=statement.executeQuery("PRAGMA table_info(ozon_unit_ship_receipts)")){while(result.next())columns.add(result.getString("name"));}
        try(var statement=connection.createStatement()){
            if(!columns.contains("readback_json"))statement.execute("ALTER TABLE ozon_unit_ship_receipts ADD COLUMN readback_json TEXT NOT NULL DEFAULT '{}'");
            if(!columns.contains("next_child"))statement.execute("ALTER TABLE ozon_unit_ship_receipts ADD COLUMN next_child INTEGER NOT NULL DEFAULT 0");
            // NULL means an older operation whose source was not recorded. Preserve
            // its historical all-sources behavior; never invent a past selection.
            if(!columns.contains("source_mask"))statement.execute("ALTER TABLE ozon_unit_ship_receipts ADD COLUMN source_mask INTEGER");
        }
    }

    Receipt find(Shop shop,String posting){
        // The service calls this on its worker, never from a JavaFX constructor.
        try{SqliteWriteTransaction.run(connection->{initialize(connection);return null;});}
        catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
        try(var connection=Database.getConnection()){return find(connection,shop,posting);}
        catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
    }
    /** A child of an unfinished split must not start a second acceptance intent. */
    Receipt pendingParentOfChild(Shop shop,String child){
        child=OzonApiClient.requireExternalId(child,"posting number");
        try{SqliteWriteTransaction.run(connection->{initialize(connection);return null;});}
        catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
        try(var connection=Database.getConnection();var statement=connection.prepareStatement("""
                SELECT r.posting_number FROM ozon_unit_ship_receipts r
                WHERE r.shop_id=? AND r.posting_number<>? AND r.state IN ('PENDING','AMBIGUOUS','ACCEPTED')
                  AND EXISTS(SELECT 1 FROM json_each(r.children_json) child WHERE child.value=?) LIMIT 2
                """)){
            statement.setInt(1,shop.getId());statement.setString(2,child);statement.setString(3,child);
            try(var rows=statement.executeQuery()){
                if(!rows.next())return null;
                String parent=rows.getString(1);
                if(rows.next())throw new IllegalStateException("OZON_SPLIT_MULTIPLE_PARENTS");
                // find checks the original transaction owner; a changed connection
                // cannot cause this child to bypass an older incomplete operation.
                return find(connection,shop,parent);
            }
        }catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
    }
    /** Unknown split result: match a potential child conservatively by original order and warehouse. */
    Receipt pendingParentForPosting(Shop shop,OzonPostingDto posting){
        if(posting.orderId().isBlank()||posting.warehouseId().isBlank())return null;
        try{SqliteWriteTransaction.run(connection->{initialize(connection);return null;});}
        catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
        try(var connection=Database.getConnection();var statement=connection.prepareStatement("""
                SELECT r.posting_number FROM ozon_unit_ship_receipts r
                WHERE r.shop_id=? AND r.posting_number<>? AND r.state IN ('PENDING','AMBIGUOUS','ACCEPTED')
                  AND json_extract(r.source_json,'$.orderId')=? AND json_extract(r.source_json,'$.warehouseId')=?
                """)){
            statement.setInt(1,shop.getId());statement.setString(2,posting.postingNumber());
            statement.setString(3,posting.orderId());statement.setString(4,posting.warehouseId());
            Receipt match=null;
            try(var rows=statement.executeQuery()){
                while(rows.next()){
                    var candidate=find(connection,shop,rows.getString(1));
                    if(OzonPackageBuilder.unitCount(candidate.source())<=1)continue;
                    if(match!=null)throw new IllegalStateException("OZON_SPLIT_MULTIPLE_PARENTS");
                    match=candidate;
                }
            }
            return match;
        }catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
    }
    private Receipt find(Connection connection,Shop shop,String posting)throws SQLException {
        try(var statement=connection.prepareStatement("SELECT * FROM ozon_unit_ship_receipts WHERE shop_id=? AND posting_number=?")){
            statement.setInt(1,shop.getId());statement.setString(2,posting);
            try(var result=statement.executeQuery()){
                if(!result.next())return null;
                String owner=result.getString("owner");
                if(!owner.equals(ListingIdentity.fingerprint(shop)))throw new SecurityException("OZON_SPLIT_OWNER_CHANGED");
                var children=JSON.fromJson(result.getString("children_json"),String[].class);
                return new Receipt(owner,JSON.fromJson(result.getString("source_json"),OzonPostingDto.class),
                        result.getString("request_json"),result.getString("request_fingerprint"),List.of(children),result.getString("state"),
                        JSON.fromJson(result.getString("readback_json"),new com.google.gson.reflect.TypeToken<Map<String,OzonPostingDto>>(){}.getType()),result.getInt("next_child"),decodeSources(result.getObject("source_mask")));
            }
        }
    }

    List<Receipt> pending(Shop shop){
        try(var connection=Database.getConnection()){
            try(var statement=connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='ozon_unit_ship_receipts'");var result=statement.executeQuery()){if(!result.next())return List.of();}
            var columns=new HashSet<String>();
            try(var statement=connection.createStatement();var result=statement.executeQuery("PRAGMA table_info(ozon_unit_ship_receipts)")){while(result.next())columns.add(result.getString("name"));}
            // An older candidate may already contain pending receipts. Migrate its
            // checkpoint columns on this worker before loading them, without a
            // write transaction on ordinary ticks or shops that never split.
            if(!columns.contains("readback_json")||!columns.contains("next_child")||!columns.contains("source_mask"))SqliteWriteTransaction.run(writer->{initialize(writer);return null;});
            // One oldest receipt per turn; progress touches updated_at, so a bad receipt
            // cannot monopolize subsequent turns when other receipts are waiting.
            try(var statement=connection.prepareStatement("SELECT posting_number FROM ozon_unit_ship_receipts WHERE shop_id=? AND owner=? AND state IN ('PENDING','AMBIGUOUS','ACCEPTED') ORDER BY updated_at,posting_number LIMIT 1")){
                statement.setInt(1,shop.getId());statement.setString(2,ListingIdentity.fingerprint(shop));
                try(var result=statement.executeQuery()){return result.next()?List.of(find(connection,shop,result.getString(1))):List.of();}
            }
        }catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
    }

    Map<String,List<String>> completed(Shop shop,Collection<String> sources){
        if(sources.isEmpty())return Map.of();
        var requested=List.copyOf(new LinkedHashSet<>(sources));
        if(requested.size()>10000)throw new IllegalArgumentException("Too many shipment receipts");
        var found=new LinkedHashMap<String,List<String>>();
        try(var connection=Database.getConnection()){
            try(var statement=connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='ozon_unit_ship_receipts'");var result=statement.executeQuery()){if(!result.next())return Map.of();}
            for(int offset=0;offset<requested.size();offset+=300){
                var page=requested.subList(offset,Math.min(offset+300,requested.size()));
                String placeholders=String.join(",",Collections.nCopies(page.size(),"?"));
                try(var statement=connection.prepareStatement("SELECT posting_number,children_json FROM ozon_unit_ship_receipts WHERE shop_id=? AND owner=? AND state='COMPLETE' AND posting_number IN ("+placeholders+")")){
                    statement.setInt(1,shop.getId());statement.setString(2,ListingIdentity.fingerprint(shop));
                    for(int i=0;i<page.size();i++)statement.setString(i+3,page.get(i));
                    try(var result=statement.executeQuery()){while(result.next())found.put(result.getString(1),List.of(JSON.fromJson(result.getString(2),String[].class)));}
                }
            }
            return Map.copyOf(found);
        }catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
    }

    void readbackProgress(Shop shop,String posting,Map<String,OzonPostingDto> readbacks,int nextChild){
        try{SqliteWriteTransaction.run(connection->{requireOwner(connection,shop);
            try(var statement=connection.prepareStatement("UPDATE ozon_unit_ship_receipts SET readback_json=?,next_child=?,updated_at=? WHERE shop_id=? AND posting_number=? AND owner=?")){
                statement.setString(1,JSON.toJson(readbacks));statement.setInt(2,nextChild);statement.setString(3,Instant.now().toString());
                statement.setInt(4,shop.getId());statement.setString(5,posting);statement.setString(6,ListingIdentity.fingerprint(shop));
                if(statement.executeUpdate()!=1)throw new SQLException("OZON_SPLIT_CHECKPOINT_MISSING");
            }return null;
        });}catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
    }

    boolean begin(Shop shop,OzonPostingDto source,String request,String fingerprint,int units){
        return begin(shop,source,request,fingerprint,units,null);
    }
    boolean begin(Shop shop,OzonPostingDto source,String request,String fingerprint,int units,KizSourceSelection sources){
        try{return SqliteWriteTransaction.run(connection->{
            requireOwner(connection,shop);
            var previous=find(connection,shop,source.postingNumber());
            if(previous!=null&&!previous.state().equals("REJECTED"))return false;
            try(var legacy=connection.prepareStatement("SELECT status FROM ozon_action_log WHERE shop_id=? AND action_type='ship' AND posting_number=? ORDER BY id DESC LIMIT 1")){
                legacy.setInt(1,shop.getId());legacy.setString(2,source.postingNumber());
                try(var row=legacy.executeQuery()){if(row.next()&&Set.of("pending","ambiguous","success","reconciled").contains(row.getString(1)))throw new IllegalStateException("OZON_SPLIT_LEGACY_RECONCILE");}
            }
            if(units>1&&hasBoundData(connection,shop,source.postingNumber()))throw new IllegalStateException("OZON_SPLIT_HAS_BOUND_KIZ");
            try(var statement=connection.prepareStatement("""
                    INSERT INTO ozon_unit_ship_receipts(shop_id,posting_number,owner,source_json,request_json,
                        request_fingerprint,children_json,state,updated_at,source_mask) VALUES(?,?,?,?,?,?,'[]','PENDING',?,?)
                    ON CONFLICT(shop_id,posting_number) DO UPDATE SET owner=excluded.owner,
                        source_json=excluded.source_json,request_json=excluded.request_json,
                        request_fingerprint=excluded.request_fingerprint,children_json='[]',state='PENDING',parent_in_children=1,readback_json='{}',next_child=0,updated_at=excluded.updated_at,source_mask=excluded.source_mask
                    """)){
                Object[] values={shop.getId(),source.postingNumber(),ListingIdentity.fingerprint(shop),JSON.toJson(source),request,fingerprint,Instant.now().toString(),encodeSources(sources)};
                for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);statement.executeUpdate();
            }
            return true;
        });}catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
    }

    private static Integer encodeSources(KizSourceSelection sources){
        if(sources==null)return null;
        if(!sources.anySelected())throw new IllegalArgumentException("KIZ_SOURCE_REQUIRED");
        return (sources.sellerImported()?1:0)|(sources.purchased()?2:0);
    }
    private static KizSourceSelection decodeSources(Object raw){
        if(raw==null)return null;
        int mask=((Number)raw).intValue();
        if(mask<1||mask>3)throw new IllegalStateException("OZON_SPLIT_SOURCE_INVALID");
        return new KizSourceSelection((mask&1)!=0,(mask&2)!=0);
    }

    void update(Shop shop,String posting,String state,List<String> children){
        try{SqliteWriteTransaction.run(connection->{requireOwner(connection,shop);
            try(var statement=connection.prepareStatement("UPDATE ozon_unit_ship_receipts SET state=?,children_json=COALESCE(?,children_json),updated_at=?,parent_in_children=COALESCE(?,parent_in_children) WHERE shop_id=? AND posting_number=? AND owner=?")){
                statement.setString(1,state);statement.setString(2,children==null?null:JSON.toJson(children));statement.setString(3,Instant.now().toString());
                statement.setObject(4,children==null?null:children.contains(posting)?1:0);
                statement.setInt(5,shop.getId());statement.setString(6,posting);statement.setString(7,ListingIdentity.fingerprint(shop));
                if(statement.executeUpdate()!=1)throw new SQLException("OZON_SPLIT_CHECKPOINT_MISSING");
            }return null;
        });}catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
    }

    /** Bind the verified remote children and completion to one owner-checked local commit. */
    void complete(Shop shop,String posting,List<OzonPostingDto> children){
        var numbers=children.stream().map(OzonPostingDto::postingNumber).toList();
        try{SqliteWriteTransaction.run(connection->{requireOwner(connection,shop);
            for(var child:children)OzonPostingRepository.upsert(connection,shop.getId(),child);
            try(var statement=connection.prepareStatement("UPDATE ozon_unit_ship_receipts SET state='COMPLETE',children_json=?,parent_in_children=?,updated_at=? WHERE shop_id=? AND posting_number=? AND owner=?")){
                statement.setString(1,JSON.toJson(numbers));statement.setInt(2,numbers.contains(posting)?1:0);statement.setString(3,Instant.now().toString());
                statement.setInt(4,shop.getId());statement.setString(5,posting);statement.setString(6,ListingIdentity.fingerprint(shop));
                if(statement.executeUpdate()!=1)throw new SQLException("OZON_SPLIT_CHECKPOINT_MISSING");
            }return null;
        });}catch(SQLException error){throw new IllegalStateException("OZON_SPLIT_STORAGE",error);}
    }

    private static boolean hasBoundData(Connection connection,Shop shop,String posting)throws SQLException {
        for(String query:List.of(
                "SELECT 1 FROM ozon_exemplars WHERE shop_id=? AND posting_number=? LIMIT 1",
                "SELECT 1 FROM ozon_exemplar_jobs WHERE shop_id=? AND posting_number=? AND stage<>'CREATED' LIMIT 1",
                "SELECT 1 FROM ozon_label_jobs WHERE shop_id=? AND posting_number=? LIMIT 1",
                "SELECT 1 FROM kn3_marks WHERE shop_id=? AND order_id=? AND marketplace='OZON' AND code<>'' LIMIT 1",
                "SELECT 1 FROM kn3_replacements WHERE shop_id=? AND order_id=? AND marketplace='OZON' AND new_code<>'' LIMIT 1")){
            try(var statement=connection.prepareStatement(query)){statement.setInt(1,shop.getId());statement.setString(2,posting);try(var result=statement.executeQuery()){if(result.next())return true;}}
        }return false;
    }
    private static void requireOwner(Connection connection,Shop shop)throws SQLException {
        if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(connection,shop.getId(),Marketplace.OZON)))throw new SecurityException("OZON_SPLIT_OWNER_CHANGED");
    }
}
