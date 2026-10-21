package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local shipment sources and category totals; never authenticates to Znack. */
public class ShipmentKizRepository {
    public record CategoryStock(String id,String name,int products,int ready,int held,int consumed) {}

    /** Merely discovering an empty category must not mark its products as requiring KIZ. */
    public Set<String> assignmentCategories(Shop shop) {
        try(var c=Database.getConnection()) {
            String owner=require(c,shop,"categories");var result=new HashSet<String>();
            try(var p=c.prepareStatement("""
                SELECT category.id FROM kiz_stock_categories category WHERE NOT EXISTS(SELECT 1 FROM kiz_stock_deleted_categories deleted WHERE deleted.category_id=category.id) AND category.shop_id=? AND category.owner=? AND (
                  NOT EXISTS(SELECT 1 FROM kiz_wb_category_sources auto WHERE auto.category_id=category.id) AND NOT EXISTS(SELECT 1 FROM kiz_ozon_category_sources auto WHERE auto.category_id=category.id)
                  OR EXISTS(SELECT 1 FROM kiz_stock_classifications classified WHERE classified.category_id=category.id))
                """)) {
                p.setInt(1,shop.getId());p.setString(2,owner);try(var r=p.executeQuery()){while(r.next())result.add(r.getString(1));}
            }return Set.copyOf(result);
        }catch(SQLException failure){throw storage(failure);}
    }

    public KizSourceSelection selection(Shop shop,String shipment) {
        try(var c=Database.getConnection()) {
            String owner=require(c,shop,shipment);
            try(var p=c.prepareStatement("SELECT seller_imported,purchased FROM kiz_shipment_sources WHERE shop_id=? AND owner=? AND shipment_id=?")) {
                p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,shipment);
                try(var r=p.executeQuery()){return r.next()?new KizSourceSelection(r.getInt(1)==1,r.getInt(2)==1):KizSourceSelection.SELLER_ONLY;}
            }
        }catch(SQLException failure){throw storage(failure);}
    }
    public void saveSelection(Shop shop,String shipment,KizSourceSelection selection) {
        Objects.requireNonNull(selection);
        try(var c=Database.getConnection()) {
            String owner=require(c,shop,shipment);
            try(var p=c.prepareStatement("INSERT INTO kiz_shipment_sources VALUES(?,?,?,?,?) ON CONFLICT(shop_id,owner,shipment_id) DO UPDATE SET seller_imported=excluded.seller_imported,purchased=excluded.purchased")) {
                p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,shipment);p.setInt(4,selection.sellerImported()?1:0);p.setInt(5,selection.purchased()?1:0);p.executeUpdate();
            }
        }catch(SQLException failure){throw storage(failure);}
    }

    /** Uses native WB subject IDs, not translated names; existing seller-defined bindings survive. */
    public List<CategoryStock> refreshCategories(Shop shop) {
        try { return com.vncode.app.config.SqliteWriteTransaction.run(c->{
                String owner=require(c,shop,"categories"),now=Instant.now().toString();
                if(shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON){return refreshOzon(c,shop,owner,now);}
                var subjects=new LinkedHashMap<Long,String>();
                try(var p=c.prepareStatement("SELECT subject_id,MIN(subject_name) FROM wb_product_cards WHERE shop_id=? AND subject_id>0 AND trim(COALESCE(subject_name,''))<>'' GROUP BY subject_id ORDER BY subject_id")) {
                    p.setInt(1,shop.getId());try(var r=p.executeQuery()){while(r.next())subjects.put(r.getLong(1),r.getString(2));}
                }
                for(var subject:subjects.entrySet()) {
                    String id=UUID.nameUUIDFromBytes((shop.getId()+"|"+owner+"|WB|"+subject.getKey()).getBytes(StandardCharsets.UTF_8)).toString();
                    if(deleted(c,id))continue;
                    String label=subject.getValue()+" · WB "+subject.getKey();
                    try(var p=c.prepareStatement("INSERT INTO kiz_stock_categories VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name")) {
                        p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,owner);p.setString(4,label);p.setString(5,now);p.executeUpdate();
                    }
                    try(var p=c.prepareStatement("INSERT INTO kiz_wb_category_sources VALUES(?,?,?,?) ON CONFLICT(shop_id,owner,subject_id) DO NOTHING")) {
                        p.setInt(1,shop.getId());p.setString(2,owner);p.setLong(3,subject.getKey());p.setString(4,id);p.executeUpdate();
                    }
                    try(var p=c.prepareStatement("""
                        INSERT INTO kiz_stock_product_categories(shop_id,marketplace,product_id,owner,category_id,updated_at)
                        SELECT shop_id,'WILDBERRIES',CAST(nm_id AS TEXT),?,?,? FROM wb_product_cards WHERE shop_id=? AND subject_id=?
                        ON CONFLICT(shop_id,marketplace,product_id) DO UPDATE SET owner=excluded.owner,category_id=excluded.category_id,updated_at=excluded.updated_at
                        WHERE kiz_stock_product_categories.owner<>excluded.owner OR EXISTS(
                          SELECT 1 FROM kiz_wb_category_sources auto WHERE auto.category_id=kiz_stock_product_categories.category_id)
                        """)) {
                        p.setString(1,owner);p.setString(2,id);p.setString(3,now);p.setInt(4,shop.getId());p.setLong(5,subject.getKey());p.executeUpdate();
                    }
                }
                var rows=new ArrayList<CategoryStock>();
                try(var p=c.prepareStatement("""
                    SELECT category.id,category.name,
                      (SELECT count(*) FROM kiz_stock_product_categories product WHERE product.category_id=category.id AND product.shop_id=category.shop_id AND product.owner=category.owner),
                      COALESCE(sum(k.status='AVAILABLE'),0),COALESCE(sum(k.status='RESERVED'),0),COALESCE(sum(k.status='CONSUMED'),0)
                    FROM kiz_stock_categories category
                    LEFT JOIN kiz_stock_classifications classified ON classified.category_id=category.id
                    LEFT JOIN kiz_import_entries entry ON entry.code_id=classified.code_id
                    LEFT JOIN kiz_import_batches batch ON batch.id=entry.batch_id AND batch.owner=category.owner AND batch.shop_id=category.shop_id
                    LEFT JOIN kiz_codes k ON k.id=entry.code_id AND k.shop_id=category.shop_id AND batch.id IS NOT NULL
                    WHERE NOT EXISTS(SELECT 1 FROM kiz_stock_deleted_categories deleted WHERE deleted.category_id=category.id) AND category.shop_id=? AND category.owner=? GROUP BY category.id ORDER BY category.name,category.id
                    """)) {
                    p.setInt(1,shop.getId());p.setString(2,owner);try(var r=p.executeQuery()){while(r.next())rows.add(new CategoryStock(r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getInt(6)));}
                }
                return List.copyOf(rows);
        });}catch(SQLException failure){throw storage(failure);}
    }
    /** Lightweight read for live shipment totals; never takes a write transaction. */
    public List<CategoryStock> stocks(Shop shop){
        try(var c=Database.getConnection()){
            String owner=require(c,shop,"categories");var rows=new ArrayList<CategoryStock>();
            try(var p=c.prepareStatement("""
                SELECT category.id,category.name,
                  (SELECT count(*) FROM kiz_stock_product_categories product WHERE product.category_id=category.id AND product.shop_id=category.shop_id AND product.owner=category.owner),
                  COALESCE(sum(k.status='AVAILABLE'),0),COALESCE(sum(k.status='RESERVED'),0),COALESCE(sum(k.status='CONSUMED'),0)
                FROM kiz_stock_categories category
                LEFT JOIN kiz_stock_classifications classified ON classified.category_id=category.id
                LEFT JOIN kiz_import_entries entry ON entry.code_id=classified.code_id
                LEFT JOIN kiz_import_batches batch ON batch.id=entry.batch_id AND batch.owner=category.owner AND batch.shop_id=category.shop_id
                LEFT JOIN kiz_codes k ON k.id=entry.code_id AND k.shop_id=category.shop_id AND batch.id IS NOT NULL
                WHERE NOT EXISTS(SELECT 1 FROM kiz_stock_deleted_categories deleted WHERE deleted.category_id=category.id) AND category.shop_id=? AND category.owner=? GROUP BY category.id ORDER BY category.name,category.id
                """)){
                p.setInt(1,shop.getId());p.setString(2,owner);try(var r=p.executeQuery()){while(r.next())rows.add(new CategoryStock(r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getInt(6)));}
            }return List.copyOf(rows);
        }catch(SQLException failure){throw storage(failure);}
    }
    private static List<CategoryStock> refreshOzon(Connection c,Shop shop,String owner,String now)throws SQLException {
        record NativeCategory(String category,String type,String name){}
        var groups=new ArrayList<NativeCategory>();
        try(var p=c.prepareStatement("SELECT description_category_id,type_id,MIN(category) FROM ozon_products WHERE shop_id=? AND archived=0 AND description_category_id<>'' AND type_id<>'' GROUP BY description_category_id,type_id")){
            p.setInt(1,shop.getId());try(var r=p.executeQuery()){while(r.next())groups.add(new NativeCategory(r.getString(1),r.getString(2),r.getString(3)));}
        }
        for(var group:groups){
            String id=UUID.nameUUIDFromBytes((shop.getId()+"|"+owner+"|OZON|"+group.category()+"|"+group.type()).getBytes(StandardCharsets.UTF_8)).toString();
            if(deleted(c,id))continue;
            String label=(group.name()==null||group.name().isBlank()?"Ozon":group.name())+" · "+group.category()+" / "+group.type();
            try(var p=c.prepareStatement("INSERT INTO kiz_stock_categories VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name")){
                p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,owner);p.setString(4,label);p.setString(5,now);p.executeUpdate();
            }
            try(var p=c.prepareStatement("INSERT INTO kiz_ozon_category_sources VALUES(?,?,?,?,?) ON CONFLICT(shop_id,owner,description_category_id,type_id) DO NOTHING")){
                p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,group.category());p.setString(4,group.type());p.setString(5,id);p.executeUpdate();
            }
            try(var p=c.prepareStatement("""
                INSERT INTO kiz_stock_product_categories(shop_id,marketplace,product_id,owner,category_id,updated_at)
                SELECT shop_id,'OZON',product_id,?,?,? FROM ozon_products WHERE shop_id=? AND archived=0 AND description_category_id=? AND type_id=?
                ON CONFLICT(shop_id,marketplace,product_id) DO UPDATE SET owner=excluded.owner,category_id=excluded.category_id,updated_at=excluded.updated_at
                WHERE kiz_stock_product_categories.owner<>excluded.owner OR EXISTS(SELECT 1 FROM kiz_ozon_category_sources auto WHERE auto.category_id=kiz_stock_product_categories.category_id)
                """)){
                p.setString(1,owner);p.setString(2,id);p.setString(3,now);p.setInt(4,shop.getId());p.setString(5,group.category());p.setString(6,group.type());p.executeUpdate();
            }
        }
        var rows=new ArrayList<CategoryStock>();
        try(var p=c.prepareStatement("""
            SELECT category.id,category.name,
              (SELECT count(*) FROM kiz_stock_product_categories product WHERE product.category_id=category.id AND product.shop_id=category.shop_id AND product.owner=category.owner),
              COALESCE(sum(k.status='AVAILABLE'),0),COALESCE(sum(k.status='RESERVED'),0),COALESCE(sum(k.status='CONSUMED'),0)
            FROM kiz_stock_categories category
            LEFT JOIN kiz_stock_classifications classified ON classified.category_id=category.id
            LEFT JOIN kiz_import_entries entry ON entry.code_id=classified.code_id
            LEFT JOIN kiz_import_batches batch ON batch.id=entry.batch_id AND batch.owner=category.owner AND batch.shop_id=category.shop_id
            LEFT JOIN kiz_codes k ON k.id=entry.code_id AND k.shop_id=category.shop_id AND batch.id IS NOT NULL
            WHERE NOT EXISTS(SELECT 1 FROM kiz_stock_deleted_categories deleted WHERE deleted.category_id=category.id) AND category.shop_id=? AND category.owner=? GROUP BY category.id ORDER BY category.name,category.id
            """)){
            p.setInt(1,shop.getId());p.setString(2,owner);try(var r=p.executeQuery()){while(r.next())rows.add(new CategoryStock(r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getInt(6)));}
        }return List.copyOf(rows);
    }
    private static boolean deleted(Connection c,String id)throws SQLException {
        try(var p=c.prepareStatement("SELECT 1 FROM kiz_stock_deleted_categories WHERE category_id=?")){p.setString(1,id);try(var r=p.executeQuery()){return r.next();}}
    }
    private static String require(Connection c,Shop shop,String shipment)throws SQLException {
        if(shop.getMarketplace()!=com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES && shop.getMarketplace()!=com.vncode.app.integration.marketplace.Marketplace.OZON)throw new IllegalArgumentException("KIZ_MARKETPLACE_UNSUPPORTED");ListingIdentity.require(shop);
        if(shipment==null||shipment.isBlank()||shipment.length()>200)throw new IllegalArgumentException("KIZ_SHIPMENT_REQUIRED");
        String owner=ListingIdentity.fingerprint(shop);
        if(!owner.equals(KizWarehouseRepository.currentOwner(c,shop.getId())))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
        return owner;
    }
    private static IllegalStateException storage(SQLException failure){return new IllegalStateException("KIZ_SHIPMENT_STORAGE",failure);}
}
