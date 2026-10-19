package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Kiz;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** FIFO imported stock by seller category. Paid replenishment remains outside this allocator. */
public final class KizCategoryAllocator {
    public record Demand(String key,String productId,int quantity){
        public Demand{if(key==null||key.isBlank()||productId==null||productId.isBlank()||quantity<1||quantity>100000)throw new IllegalArgumentException("KIZ_CATEGORY_DEMAND_INVALID");}
    }
    public record Selection(String key,String productId,String category,Kiz code){
        @Override public String toString(){return "CategorySelection[code=redacted]";}
    }
    /** A red-code replacement may use any seller-imported pool of this shop. */
    public Kiz reserveReplacement(Shop shop){return reserveReplacement(shop,true,false);}
    public Kiz reserveReplacement(Shop shop,boolean imported,boolean purchased){
        LicenseRuntime.require(Permission.KIZ);ListingIdentity.require(shop);
        try{return com.vncode.app.config.SqliteWriteTransaction.run(c->{
            String owner=ListingIdentity.fingerprint(shop);if(!owner.equals(KizWarehouseRepository.currentOwner(c,shop.getId())))throw new SecurityException("KIZ_CATEGORY_OWNER_CHANGED");
            Kiz code=null;
            try(var p=c.prepareStatement("SELECT k.id,k.raw_code FROM kiz_codes k WHERE k.shop_id=? AND k.status='AVAILABLE' AND ((? AND EXISTS(SELECT 1 FROM kiz_import_entries entry JOIN kiz_import_batches batch ON batch.id=entry.batch_id WHERE entry.code_id=k.id AND batch.shop_id=k.shop_id AND batch.owner=?)) OR (? AND NOT EXISTS(SELECT 1 FROM kiz_import_entries entry WHERE entry.code_id=k.id))) AND "+KizRejectedStock.eligible("k")+" ORDER BY k.id LIMIT 1")){
                p.setInt(1,shop.getId());p.setBoolean(2,imported);p.setString(3,owner);p.setBoolean(4,purchased);try(var r=p.executeQuery()){if(r.next())code=new Kiz(r.getInt(1),r.getString(2));}
            }
            if(code==null)return null;String token=UUID.randomUUID().toString(),now=Instant.now().toString();
            try(var p=c.prepareStatement("UPDATE kiz_codes SET status='RESERVED',reservation_token=?,reserved_at=?,reservation_recoverable=1,updated_at=? WHERE id=? AND shop_id=? AND status='AVAILABLE'")){
                p.setString(1,token);p.setString(2,now);p.setString(3,now);p.setInt(4,code.getId());p.setInt(5,shop.getId());if(p.executeUpdate()!=1)throw new IllegalStateException("KIZ_CATEGORY_STOCK_CHANGED");
            }
            return new Kiz(code.getId(),code.getCode(),token);
        });}catch(SQLException e){throw new IllegalStateException("KIZ_CATEGORY_STORAGE",e);}
    }
    /** Partial stock is intentional: only the remaining quantities proceed to exact-GTIN planning. */
    public Map<String,List<Kiz>> reservePartial(Shop shop,List<Demand> demands){
        LicenseRuntime.require(Permission.KIZ);ListingIdentity.require(shop);validate(demands);
        String owner=ListingIdentity.fingerprint(shop),token=UUID.randomUUID().toString();
        try(var c=Database.getConnection();var tx=c.createStatement()){
            tx.execute("BEGIN IMMEDIATE");
            try{
                var selections=select(c,shop.getId(),shop.getMarketplace(),owner,demands);
                try(var p=c.prepareStatement("UPDATE kiz_codes SET status='RESERVED',reservation_token=?,reserved_at=?,reservation_recoverable=1,updated_at=? WHERE id=? AND shop_id=? AND status='AVAILABLE'")){
                    for(var selection:selections){
                        String now=Instant.now().toString();p.setString(1,token);p.setString(2,now);p.setString(3,now);p.setInt(4,selection.code().getId());p.setInt(5,shop.getId());
                        if(p.executeUpdate()!=1)throw new IllegalStateException("KIZ_CATEGORY_STOCK_CHANGED");
                        recordProof(c,shop.getId(),owner,token,selection);
                    }
                }
                tx.execute("COMMIT");var result=new LinkedHashMap<String,List<Kiz>>();
                for(var selection:selections)result.computeIfAbsent(selection.key(),k->new ArrayList<>()).add(new Kiz(selection.code().getId(),selection.code().getCode(),token));
                result.replaceAll((k,v)->List.copyOf(v));return Collections.unmodifiableMap(result);
            }catch(Exception failure){tx.execute("ROLLBACK");throw failure;}
        }catch(SQLException e){throw new IllegalStateException("KIZ_CATEGORY_STORAGE",e);}
    }
    /** Caller owns the write transaction. This method selects but does not claim code rows. */
    public static List<Selection> select(Connection c,int shop,Marketplace market,String owner,List<Demand> demands)throws SQLException{
        validate(demands);if(!owner.equals(KizWarehouseRepository.currentOwner(c,shop)))throw new SecurityException("KIZ_CATEGORY_OWNER_CHANGED");
        var bindings=KizStockCategoryRepository.bindings(c,shop,market,demands.stream().map(Demand::productId).toList(),owner);
        var groups=new LinkedHashMap<String,List<Demand>>();
        for(var demand:demands){var category=bindings.get(demand.productId());if(category!=null)groups.computeIfAbsent(category,k->new ArrayList<>()).add(demand);}
        var result=new ArrayList<Selection>();
        for(var group:groups.entrySet()){
            int quantity=group.getValue().stream().mapToInt(Demand::quantity).sum();var codes=new ArrayList<Kiz>();
            try(var p=c.prepareStatement("""
                SELECT k.id,k.raw_code FROM kiz_stock_classifications classification
                JOIN kiz_stock_categories category ON category.id=classification.category_id
                JOIN kiz_codes k ON k.id=classification.code_id
                WHERE classification.category_id=? AND category.shop_id=? AND category.owner=? AND k.shop_id=?
                  AND k.status='AVAILABLE' AND %s
                ORDER BY k.id LIMIT ?
                """.formatted(KizWarehouseRepository.eligibleCategory("k")))){
                p.setString(1,group.getKey());p.setInt(2,shop);p.setString(3,owner);p.setInt(4,shop);p.setString(5,owner);p.setInt(6,quantity);
                try(var r=p.executeQuery()){while(r.next())codes.add(new Kiz(r.getInt(1),r.getString(2)));}
            }
            int cursor=0;for(var demand:group.getValue())for(int n=0;n<demand.quantity()&&cursor<codes.size();n++)result.add(new Selection(demand.key(),demand.productId(),group.getKey(),codes.get(cursor++)));
        }
        return List.copyOf(result);
    }
    public static void recordProof(Connection c,int shop,String owner,String token,Selection selection)throws SQLException{
        try(var p=c.prepareStatement("INSERT INTO kiz_stock_category_reservations(code_id,token,category_id,product_id,allocation_key,shop_id,owner,created_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(code_id,token) DO NOTHING")){
            p.setInt(1,selection.code().getId());p.setString(2,token);p.setString(3,selection.category());p.setString(4,selection.productId());p.setString(5,selection.key());p.setInt(6,shop);p.setString(7,owner);p.setString(8,Instant.now().toString());p.executeUpdate();
        }
        if(!hasProof(c,shop,owner,selection.code().getId(),token,selection.key(),selection.productId()))throw new IllegalStateException("KIZ_CATEGORY_RESERVATION_CHANGED");
    }
    public static boolean hasProof(Connection c,int shop,String owner,long code,String token,String key,String product)throws SQLException{
        try(var p=c.prepareStatement("""
            SELECT 1 FROM kiz_stock_category_reservations proof
            JOIN kiz_stock_categories category ON category.id=proof.category_id
            JOIN kiz_codes k ON k.id=proof.code_id
            WHERE proof.code_id=? AND proof.token=? AND proof.allocation_key=? AND proof.product_id=?
              AND proof.shop_id=? AND proof.owner=? AND category.shop_id=proof.shop_id AND category.owner=proof.owner
              AND %s
            """.formatted(KizWarehouseRepository.eligibleCategory("k")))){
            p.setLong(1,code);p.setString(2,token);p.setString(3,key);p.setString(4,product);p.setInt(5,shop);p.setString(6,owner);p.setString(7,owner);try(var r=p.executeQuery()){return r.next();}
        }
    }
    private static void validate(List<Demand> demands){
        if(demands==null||demands.size()>10000||demands.stream().mapToLong(Demand::quantity).sum()>100000||demands.stream().map(Demand::key).distinct().count()!=demands.size())throw new IllegalArgumentException("KIZ_CATEGORY_DEMAND_LIMIT");
    }
}
