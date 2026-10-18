package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Seller-defined imported-stock groups, independent of the GTIN purchase mapping. */
public final class KizStockCategoryRepository {
    static final String VISIBLE="NOT EXISTS(SELECT 1 FROM kiz_stock_deleted_categories deleted WHERE deleted.category_id=category.id)";
    /** A cancelled print cannot revive unused inventory in a seller-deleted category. */
    public static String releasedStatus(String alias){
        return "CASE WHEN EXISTS(SELECT 1 FROM kiz_stock_classifications classified JOIN kiz_stock_deleted_categories deleted ON deleted.category_id=classified.category_id WHERE classified.code_id="+alias+".id) THEN 'ARCHIVED' ELSE 'AVAILABLE' END";
    }
    public record Deleted(int archived,int held){}
    /** Keep reservation proof and consumed history; retire only currently unused seller stock. */
    public Deleted delete(Shop shop,String category){
        ListingIdentity.require(shop);
        try{return com.vncode.app.config.SqliteWriteTransaction.run(c->{
            require(c,shop,category);String now=Instant.now().toString();int archived,held;
            try(var p=c.prepareStatement("SELECT count(*) FROM kiz_stock_classifications cl JOIN kiz_codes k ON k.id=cl.code_id WHERE cl.category_id=? AND k.shop_id=? AND k.status='RESERVED'")){
                p.setString(1,category);p.setInt(2,shop.getId());try(var r=p.executeQuery()){r.next();held=r.getInt(1);}
            }
            try(var p=c.prepareStatement("UPDATE kiz_codes SET status='ARCHIVED',updated_at=? WHERE shop_id=? AND status='AVAILABLE' AND id IN (SELECT code_id FROM kiz_stock_classifications WHERE category_id=?)")){
                p.setString(1,now);p.setInt(2,shop.getId());p.setString(3,category);archived=p.executeUpdate();
            }
            try(var p=c.prepareStatement("INSERT INTO kiz_stock_deleted_categories VALUES(?,?)")){p.setString(1,category);p.setString(2,now);p.executeUpdate();}
            return new Deleted(archived,held);
        });}catch(SQLException e){throw storage(e);}
    }
    public record Category(String id,String name,int ready,int products){@Override public String toString(){return name+" · "+ready;}}
    public Category create(Shop shop,String name){
        String label=name==null?"":name.strip();
        if(label.isBlank()||label.length()>100||label.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("KIZ_CATEGORY_NAME_INVALID");
        ListingIdentity.require(shop);String id=UUID.randomUUID().toString();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO kiz_stock_categories(id,shop_id,owner,name,created_at) VALUES(?,?,?,?,?)")){
            p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,ListingIdentity.fingerprint(shop));p.setString(4,label);p.setString(5,Instant.now().toString());p.executeUpdate();return new Category(id,label,0,0);
        }catch(SQLException e){throw storage(e);}
    }
    public List<Category> list(Shop shop){
        ListingIdentity.require(shop);
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            SELECT category.id,category.name,
              (SELECT count(*) FROM kiz_stock_classifications cl JOIN kiz_codes k ON k.id=cl.code_id
                WHERE cl.category_id=category.id AND k.shop_id=category.shop_id AND k.status='AVAILABLE'
                AND %s),
              (SELECT count(*) FROM kiz_stock_product_categories product WHERE product.category_id=category.id
                AND product.shop_id=category.shop_id AND product.owner=category.owner)
            FROM kiz_stock_categories category WHERE category.shop_id=? AND category.owner=? AND NOT EXISTS(SELECT 1 FROM kiz_stock_deleted_categories deleted WHERE deleted.category_id=category.id) ORDER BY category.name,category.id
            """.formatted(KizWarehouseRepository.eligibleCategory("k")))){
            String owner=ListingIdentity.fingerprint(shop);p.setString(1,owner);p.setInt(2,shop.getId());p.setString(3,owner);
            var out=new ArrayList<Category>();try(var r=p.executeQuery()){while(r.next())out.add(new Category(r.getString(1),r.getString(2),r.getInt(3),r.getInt(4)));}return List.copyOf(out);
        }catch(SQLException e){throw storage(e);}
    }
    public void assignProducts(Shop shop,String category,Collection<ProductKey> products){
        if(products==null||products.isEmpty()||products.size()>10000)throw new IllegalArgumentException("KIZ_CATEGORY_PRODUCT_LIMIT");
        ListingIdentity.require(shop);
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);require(c,shop,category);
            try(var p=c.prepareStatement("INSERT INTO kiz_stock_product_categories VALUES(?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,product_id) DO UPDATE SET owner=excluded.owner,category_id=excluded.category_id,updated_at=excluded.updated_at")){
                for(var key:products){
                    if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace()||key.cardId().isBlank())throw new SecurityException("KIZ_CATEGORY_FOREIGN_PRODUCT");
                    String sql=shop.getMarketplace()==Marketplace.OZON?"SELECT 1 FROM ozon_products WHERE shop_id=? AND product_id=? AND archived=0":"SELECT 1 FROM wb_product_cards WHERE shop_id=? AND nm_id=?";
                    try(var exists=c.prepareStatement(sql)){exists.setInt(1,shop.getId());exists.setString(2,key.cardId());try(var r=exists.executeQuery()){if(!r.next())throw new IllegalArgumentException("KIZ_CATEGORY_PRODUCT_MISSING");}}
                    p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());p.setString(3,key.cardId());p.setString(4,ListingIdentity.fingerprint(shop));p.setString(5,category);p.setString(6,Instant.now().toString());p.addBatch();
                }p.executeBatch();
            }c.commit();
        }catch(SQLException e){throw storage(e);}
    }
    /** Classify only unused imported codes; held/consumed codes keep their recorded identity. */
    public int classifyGtin(Shop shop,String category,String gtin){
        return classify(shop,category,"k.gtin=?",com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin));
    }
    public int classifyBatch(Shop shop,String category,String batch){
        return classify(shop,category,"i.batch_id=?",batch);
    }
    private int classify(Shop shop,String category,String predicate,String value){
        ListingIdentity.require(shop);
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);require(c,shop,category);int changed;
            try(var p=c.prepareStatement("""
                INSERT INTO kiz_stock_classifications(code_id,category_id,assigned_at)
                SELECT k.id,?,? FROM kiz_codes k JOIN kiz_import_entries i ON i.code_id=k.id
                JOIN kiz_import_batches b ON b.id=i.batch_id WHERE k.shop_id=? AND b.owner=? AND k.status='AVAILABLE' AND
                """+predicate+" ON CONFLICT(code_id) DO UPDATE SET category_id=excluded.category_id,assigned_at=excluded.assigned_at")){
                p.setString(1,category);p.setString(2,Instant.now().toString());p.setInt(3,shop.getId());p.setString(4,ListingIdentity.fingerprint(shop));p.setString(5,value);changed=p.executeUpdate();
            }c.commit();return changed;
        }catch(SQLException e){throw storage(e);}
    }
    public Map<String,String> bindings(Shop shop,Collection<String> products){
        ListingIdentity.require(shop);if(products.size()>10000)throw new IllegalArgumentException("KIZ_CATEGORY_PRODUCT_LIMIT");
        try(var c=Database.getConnection()){return bindings(c,shop.getId(),shop.getMarketplace(),products,ListingIdentity.fingerprint(shop));}
        catch(SQLException e){throw storage(e);}
    }
    public static Map<String,String> bindings(Connection c,int shop,Marketplace market,Collection<String> products,String owner)throws SQLException{
        var out=new LinkedHashMap<String,String>();var ids=products.stream().distinct().toList();
        for(int from=0;from<ids.size();from+=500){var batch=ids.subList(from,Math.min(from+500,ids.size()));
            try(var p=c.prepareStatement("SELECT product.product_id,product.category_id FROM kiz_stock_product_categories product JOIN kiz_stock_categories category ON category.id=product.category_id WHERE "+VISIBLE+" AND product.shop_id=? AND product.marketplace=? AND product.owner=? AND category.shop_id=product.shop_id AND category.owner=product.owner AND product.product_id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")")){
                p.setInt(1,shop);p.setString(2,market.name());p.setString(3,owner);for(int i=0;i<batch.size();i++)p.setString(i+4,batch.get(i));try(var r=p.executeQuery()){while(r.next())out.put(r.getString(1),r.getString(2));}
            }
        }return Map.copyOf(out);
    }
    static void require(Connection c,Shop shop,String category)throws SQLException{
        String owner=ListingIdentity.fingerprint(shop);if(!owner.equals(KizWarehouseRepository.currentOwner(c,shop.getId())))throw new SecurityException("KIZ_CATEGORY_OWNER_CHANGED");
        try(var p=c.prepareStatement("SELECT 1 FROM kiz_stock_categories category WHERE id=? AND shop_id=? AND owner=? AND "+VISIBLE)){p.setString(1,category);p.setInt(2,shop.getId());p.setString(3,owner);try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("KIZ_CATEGORY_FOREIGN_GROUP");}}
    }
    private static IllegalStateException storage(SQLException e){return new IllegalStateException("KIZ_CATEGORY_STORAGE",e);}
}
