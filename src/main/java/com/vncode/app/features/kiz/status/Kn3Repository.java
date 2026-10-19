package com.vncode.app.features.kiz.status;

import com.vncode.app.config.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Short writes; raw codes stay in the same private local database as print history. */
public final class Kn3Repository {
    private static final java.util.concurrent.atomic.AtomicBoolean STARTUP_RECOVERED=new java.util.concurrent.atomic.AtomicBoolean();
    /** Call before startup workers: PREPARED proves no HTTP attempt began. Never replay SENDING. */
    public static void recoverAtStartup(){if(!STARTUP_RECOVERED.compareAndSet(false,true))return;try{recoverUnsent();}catch(RuntimeException e){STARTUP_RECOVERED.set(false);throw e;}}
    static int recoverUnsent(){
        try{return SqliteWriteTransaction.run(c->{
            try(var p=c.prepareStatement("UPDATE kiz_codes SET status="+com.vncode.app.features.kiz.KizStockCategoryRepository.releasedStatus("kiz_codes")+",reservation_token=NULL,reserved_at=NULL,reservation_recoverable=NULL,updated_at=? WHERE status='RESERVED' AND id IN(SELECT s.code_id FROM kn3_replacement_stock s JOIN kn3_replacements r ON r.id=s.replacement_id WHERE r.state='PREPARED') AND EXISTS(SELECT 1 FROM kn3_replacement_stock s JOIN kn3_replacements r ON r.id=s.replacement_id WHERE r.state='PREPARED' AND r.shop_id=kiz_codes.shop_id AND s.code_id=kiz_codes.id AND s.reservation_token=kiz_codes.reservation_token)")){p.setString(1,Instant.now().toString());p.executeUpdate();}
            try(var p=c.prepareStatement("UPDATE kn3_replacements SET state='FAILED',updated_at=? WHERE state='PREPARED'")){p.setString(1,Instant.now().toString());return p.executeUpdate();}
        });}catch(SQLException e){throw new IllegalStateException("FBS_KN3_STORAGE",e);}
    }
    public static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS kn3_marks(shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,marketplace TEXT NOT NULL,owner TEXT NOT NULL,order_id TEXT NOT NULL,product_id TEXT NOT NULL,exemplar_id TEXT NOT NULL,code TEXT NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,checked_at TEXT NOT NULL,PRIMARY KEY(shop_id,marketplace,order_id,product_id,exemplar_id))");
            s.execute("CREATE TABLE IF NOT EXISTS kn3_replacements(id TEXT PRIMARY KEY,shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,marketplace TEXT NOT NULL,owner TEXT NOT NULL,order_id TEXT NOT NULL,product_id TEXT NOT NULL,exemplar_id TEXT NOT NULL,old_hash TEXT NOT NULL,new_code TEXT NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL DEFAULT '{}',updated_at TEXT NOT NULL)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS kn3_replacement_active ON kn3_replacements(shop_id,marketplace,order_id) WHERE state IN ('PREPARED','SENDING','AWAITING','RECONCILE')");
            s.execute("CREATE TABLE IF NOT EXISTS kn3_replacement_stock(replacement_id TEXT PRIMARY KEY REFERENCES kn3_replacements(id) ON DELETE CASCADE,code_id INTEGER NOT NULL,reservation_token TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS kn3_stock_code ON kn3_replacement_stock(code_id,reservation_token)");
            s.execute("CREATE INDEX IF NOT EXISTS kn3_replacement_history ON kn3_replacements(shop_id,marketplace,owner,order_id)");
        }
    }
    public void observe(Shop shop,String order,List<Kn3Mark> marks){
        ListingIdentity.require(shop);String owner=ListingIdentity.fingerprint(shop);String now=Instant.now().toString();
        if(marks.stream().anyMatch(m->!order.equals(m.orderId())))throw new SecurityException("FBS_FOREIGN_ORDER");
        try{SqliteWriteTransaction.run(c->{requireOwner(c,shop);
            try(var d=c.prepareStatement("DELETE FROM kn3_marks WHERE shop_id=? AND marketplace=? AND order_id=?")){d.setInt(1,shop.getId());d.setString(2,shop.getMarketplace().name());d.setString(3,order);d.executeUpdate();}
            var rows=marks.isEmpty()?List.of(new Kn3Mark(order,"","","",Kn3Mark.State.EMPTY,"")):marks;
            try(var p=c.prepareStatement("INSERT INTO kn3_marks VALUES(?,?,?,?,?,?,?,?,?,?)")){
                for(var m:rows){Object[] values={shop.getId(),shop.getMarketplace().name(),owner,order,m.productId(),m.exemplarId(),m.code(),m.state().name(),m.reason(),now};for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.addBatch();}p.executeBatch();
            }
            // Acknowledgement is not completion. Only readback of the exact new mark settles it.
            try(var p=c.prepareStatement("SELECT id,new_code,product_id,exemplar_id FROM kn3_replacements WHERE shop_id=? AND marketplace=? AND owner=? AND order_id=? AND state IN ('PREPARED','SENDING','AWAITING','RECONCILE')")){
                p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());p.setString(3,owner);p.setString(4,order);
                try(var r=p.executeQuery()){if(r.next()){String id=r.getString(1),code=r.getString(2),product=r.getString(3),exemplar=r.getString(4);var current=rows.stream().filter(m->m.productId().equals(product)&&m.exemplarId().equals(exemplar)&&m.fingerprint().equals(Kn3Mark.fingerprint(code))).findFirst();if(current.isPresent())dispatch(c,id,current.get().state()==Kn3Mark.State.ACCEPTED?"DONE":current.get().state()==Kn3Mark.State.REJECTED?"FAILED":"AWAITING",true);}}
            }return null;
        });}catch(SQLException e){throw new IllegalStateException("FBS_KN3_STORAGE",e);}
    }
    public Map<String,List<Kn3Mark>> read(Connection c,int shop,Marketplace market,Collection<String> ids)throws SQLException {
        String owner=ListingIdentity.fingerprint(c,shop,market);var all=new ArrayList<>(new LinkedHashSet<>(ids));var result=new HashMap<String,List<Kn3Mark>>();
        for(int offset=0;offset<all.size();offset+=400){var part=all.subList(offset,Math.min(offset+400,all.size()));
            try(var p=c.prepareStatement("SELECT * FROM kn3_marks WHERE shop_id=? AND marketplace=? AND owner=? AND order_id IN ("+String.join(",",Collections.nCopies(part.size(),"?"))+")")){
                p.setInt(1,shop);p.setString(2,market.name());p.setString(3,owner);for(int i=0;i<part.size();i++)p.setString(i+4,part.get(i));try(var r=p.executeQuery()){while(r.next()){var m=new Kn3Mark(r.getString("order_id"),r.getString("product_id"),r.getString("exemplar_id"),r.getString("code"),Kn3Mark.State.valueOf(r.getString("state")),r.getString("reason"));result.computeIfAbsent(m.orderId(),k->new ArrayList<>()).add(m);}}
            }
            try(var p=c.prepareStatement("SELECT * FROM kn3_replacements WHERE shop_id=? AND marketplace=? AND owner=? AND state IN ('PREPARED','SENDING','AWAITING','RECONCILE') AND order_id IN ("+String.join(",",Collections.nCopies(part.size(),"?"))+")")){
                p.setInt(1,shop);p.setString(2,market.name());p.setString(3,owner);for(int i=0;i<part.size();i++)p.setString(i+4,part.get(i));try(var r=p.executeQuery()){while(r.next()){String order=r.getString("order_id"),product=r.getString("product_id"),exemplar=r.getString("exemplar_id"),code=r.getString("new_code");var rows=result.computeIfAbsent(order,k->new ArrayList<>());rows.removeIf(m->m.productId().equals(product)&&m.exemplarId().equals(exemplar));rows.add(new Kn3Mark(order,product,exemplar,code,Kn3Mark.State.PENDING,r.getString("state").equals("RECONCILE")?"RECONCILE_REQUIRED":"REPLACEMENT_PENDING"));}}
            }
        }result.replaceAll((k,v)->List.copyOf(v));return Map.copyOf(result);
    }
    public List<Kn3Mark> read(Shop shop,String order){ListingIdentity.require(shop);try(var c=Database.getConnection()){return read(c,shop.getId(),shop.getMarketplace(),List.of(order)).getOrDefault(order,List.of());}catch(SQLException e){throw new IllegalStateException("FBS_KN3_STORAGE",e);}}
    public void unavailable(Shop shop,Collection<String> orders,String reason){
        try{SqliteWriteTransaction.run(c->{requireOwner(c,shop);try(var p=c.prepareStatement("UPDATE kn3_marks SET state='UNAVAILABLE',reason=?,checked_at=? WHERE shop_id=? AND marketplace=? AND owner=? AND order_id=?")){
            for(String order:orders){p.setString(1,Kn3Mark.safe(reason));p.setString(2,Instant.now().toString());p.setInt(3,shop.getId());p.setString(4,shop.getMarketplace().name());p.setString(5,ListingIdentity.fingerprint(shop));p.setString(6,order);p.addBatch();}p.executeBatch();}return null;});}catch(SQLException e){throw new IllegalStateException("FBS_KN3_STORAGE",e);}
    }
    public void enrichWb(Connection c,int shop,List<Order> orders)throws SQLException {
        var marks=read(c,shop,Marketplace.WILDBERRIES,orders.stream().map(o->o.getId().toString()).toList());
        for(var order:orders){var rows=marks.getOrDefault(order.getId().toString(),List.of());order.setKn3Marks(rows);
            if(!rows.isEmpty()){var mark=rows.getFirst();if(!mark.code().isBlank()){order.setKiz(mark.code());order.setRequiresKiz(true);}else if(mark.state()==Kn3Mark.State.EMPTY)order.setKiz("");}
        }
    }
    public String begin(Shop shop,Kn3Mark old,String newCode,String payload){
        return begin(shop,old,newCode,payload,null);
    }
    public String begin(Shop shop,Kn3Mark old,String newCode,String payload,Kiz stock){
        ListingIdentity.require(shop);String id=UUID.randomUUID().toString();
        try{SqliteWriteTransaction.run(c->{requireOwner(c,shop);try(var p=c.prepareStatement("INSERT INTO kn3_replacements(id,shop_id,marketplace,owner,order_id,product_id,exemplar_id,old_hash,new_code,state,payload,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'PREPARED',?,?)")){
            Object[] values={id,shop.getId(),shop.getMarketplace().name(),ListingIdentity.fingerprint(shop),old.orderId(),old.productId(),old.exemplarId(),old.fingerprint(),newCode,payload,Instant.now().toString()};for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}
            if(stock!=null){
                try(var p=c.prepareStatement("UPDATE kiz_codes SET reservation_recoverable=0 WHERE id=? AND shop_id=? AND status='RESERVED' AND reservation_token=? AND raw_code=?")){p.setInt(1,stock.getId());p.setInt(2,shop.getId());p.setString(3,stock.getReservationToken());p.setString(4,stock.getCode());if(p.executeUpdate()!=1||!Kn3Mark.fingerprint(stock.getCode()).equals(Kn3Mark.fingerprint(newCode)))throw new SecurityException("FBS_KN3_STOCK_CHANGED");}
                try(var p=c.prepareStatement("INSERT INTO kn3_replacement_stock VALUES(?,?,?)")){p.setString(1,id);p.setInt(2,stock.getId());p.setString(3,stock.getReservationToken());p.executeUpdate();}
            }
            return null;});return id;
        }catch(SQLException e){throw new IllegalStateException("FBS_KN3_REPLACEMENT_BUSY",e);}
    }
    public void dispatch(Shop shop,String id,String state){try{SqliteWriteTransaction.run(c->{requireOwner(c,shop);try(var p=c.prepareStatement("SELECT 1 FROM kn3_replacements WHERE id=? AND shop_id=? AND owner=?")){p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,ListingIdentity.fingerprint(shop));try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("FBS_KN3_OWNER_CHANGED");}}dispatch(c,id,state);return null;});}catch(SQLException e){throw new IllegalStateException("FBS_KN3_STORAGE",e);}}
    public static void dispatchWb(int shop,String token,long order,String code,String state){
        var owner=new Shop(shop,"",Marketplace.WILDBERRIES,null,token);ListingIdentity.require(owner);
        try{SqliteWriteTransaction.run(c->{requireOwner(c,owner);var ids=new ArrayList<String>();try(var p=c.prepareStatement("SELECT id FROM kn3_replacements WHERE shop_id=? AND marketplace='WILDBERRIES' AND owner=? AND order_id=? AND new_code=? AND state IN ('PREPARED','SENDING','AWAITING','RECONCILE')")){p.setInt(1,shop);p.setString(2,ListingIdentity.fingerprint(owner));p.setString(3,Long.toString(order));p.setString(4,code);try(var r=p.executeQuery()){while(r.next())ids.add(r.getString(1));}}for(String id:ids)dispatch(c,id,state);return null;});}catch(SQLException e){throw new IllegalStateException("FBS_KN3_STORAGE",e);}
    }

    private static void dispatch(Connection c,String id,String state)throws SQLException {
        dispatch(c,id,state,false);
    }
    private static void dispatch(Connection c,String id,String state,boolean readback)throws SQLException {
        try(var p=c.prepareStatement("UPDATE kn3_replacements SET state=?,updated_at=? WHERE id=? AND state NOT IN ('DONE','FAILED')")){p.setString(1,state);p.setString(2,Instant.now().toString());p.setString(3,id);p.executeUpdate();}
        boolean consumed=readback||state.equals("DONE");
        if(Set.of("DONE","FAILED").contains(state))try(var p=c.prepareStatement("UPDATE kiz_codes SET status="+(consumed?"'CONSUMED'":com.vncode.app.features.kiz.KizStockCategoryRepository.releasedStatus("kiz_codes"))+",consumed_at=?,updated_at=?,reservation_token=NULL,reserved_at=NULL,reservation_recoverable=NULL WHERE status='RESERVED' AND id IN(SELECT code_id FROM kn3_replacement_stock WHERE replacement_id=?) AND EXISTS(SELECT 1 FROM kn3_replacement_stock s JOIN kn3_replacements r ON r.id=s.replacement_id WHERE r.id=? AND r.shop_id=kiz_codes.shop_id AND s.code_id=kiz_codes.id AND s.reservation_token=kiz_codes.reservation_token)")){p.setString(1,consumed?Instant.now().toString():null);p.setString(2,Instant.now().toString());p.setString(3,id);p.setString(4,id);p.executeUpdate();}
    }
    private static void requireOwner(Connection c,Shop shop)throws SQLException {if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("FBS_KN3_OWNER_CHANGED");}
}
