package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Seller-imported stock can be allocated locally after category assignment; legal status is never fabricated. */
public final class KizWarehouseRepository {
    public Set<Integer> sellerOccurrences(Shop shop,Collection<Integer> ids){
        if(ids.isEmpty())return Set.of();
        ListingIdentity.require(shop);var result=new HashSet<Integer>();var keys=ids.stream().distinct().toList();
        try(var c=Database.getConnection()){
            requireOwner(c,shop,ListingIdentity.fingerprint(shop));
            for(int offset=0;offset<keys.size();offset+=500){var batch=keys.subList(offset,Math.min(offset+500,keys.size()));
                try(var p=c.prepareStatement("SELECT k.id FROM kiz_codes k JOIN kiz_import_entries i ON i.code_id=k.id JOIN kiz_import_batches b ON b.id=i.batch_id WHERE k.shop_id=? AND b.owner=? AND k.stock_instance<>'' AND k.id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")")){
                    p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));for(int i=0;i<batch.size();i++)p.setInt(i+3,batch.get(i));try(var r=p.executeQuery()){while(r.next())result.add(r.getInt(1));}
                }
            }return Set.copyOf(result);
        }catch(SQLException e){throw new IllegalStateException("KIZ_IMPORT_STORAGE_FAILED",e);}
    }
    public record Summary(String gtin,int total,int ready,int held,int consumed,int unverified,String category){
        public Summary(String gtin,int total,int ready,int held,int consumed,int unverified){this(gtin,total,ready,held,consumed,unverified,"");}
    }
    public record Pending(long id,String raw,String cis,String gtin){@Override public String toString(){return "PendingKiz[redacted]";}}
    public record Verdict(boolean ready,String reason){public Verdict{if(!reason.matches("KIZ_[A-Z_]{1,70}"))throw new IllegalArgumentException("Invalid verification reason");}}
    public record ImportReview(int fresh,int unassigned,int sameCategory,int otherCategory,int reserved,int consumed,int archived,int otherStock,int conflicts,int fileDuplicates){}
    public record Imported(String batch,int inserted,int duplicates,int classified,int moved,ImportReview review){}
    public ImportReview review(Shop shop,KizImportReader.Preview preview,String category)throws SQLException {
        validateImport(shop,preview);
        try(var c=Database.getConnection()) {
            String owner=ListingIdentity.fingerprint(shop);requireOwner(c,shop,owner);KizStockCategoryRepository.require(c,shop,category);
            return new ImportReview(preview.marks().size(),0,0,0,0,0,0,0,0,0);
        }
    }
    public Imported commit(Shop shop,KizImportReader.Preview preview)throws Exception {
        return commit(shop,new RegisteredGtinProof.Scope("Seller import@"+ListingIdentity.fingerprint(shop),"","",""),preview);
    }
    public Imported commit(Shop shop,RegisteredGtinProof.Scope scope,KizImportReader.Preview preview)throws Exception {
        return commit(shop,scope,preview,null);
    }
    public Imported commitToCategory(Shop shop,KizImportReader.Preview preview,String category)throws Exception {
        Objects.requireNonNull(category);
        return commitToCategory(shop,preview,category,false);
    }
    /** Each category upload creates new stock units; the legacy move flag is retained for callers. */
    public Imported commitToCategory(Shop shop,KizImportReader.Preview preview,String category,boolean moveAvailable)throws Exception {
        Objects.requireNonNull(category);
        return commit(shop,new RegisteredGtinProof.Scope("Seller import@"+ListingIdentity.fingerprint(shop),"","",""),preview,category,moveAvailable);
    }
    private Imported commit(Shop shop,RegisteredGtinProof.Scope scope,KizImportReader.Preview preview,String category)throws Exception {
        return commit(shop,scope,preview,category,false);
    }
    private static void validateImport(Shop shop,KizImportReader.Preview preview) {
        ListingIdentity.require(shop);if(!preview.valid()||!preview.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("KIZ_IMPORT_PREVIEW_INVALID");
        var marks=preview.marks();if(marks.size()>100000)throw new IllegalArgumentException("KIZ_CODE_COUNT_LIMIT");
        for(var mark:marks)if(!mark.equals(KizMark.parse(mark.raw())))throw new IllegalArgumentException("KIZ_IMPORT_PREVIEW_INVALID");
    }
    private Imported commit(Shop shop,RegisteredGtinProof.Scope scope,KizImportReader.Preview preview,String category,boolean moveAvailable)throws Exception {
        validateImport(shop,preview);var marks=preview.marks();String owner=ListingIdentity.fingerprint(shop);
        try {
            return SqliteWriteTransaction.run(c->{
                String now=Instant.now().toString();String batch=null;
                requireOwner(c,shop,owner);
                if(category!=null)KizStockCategoryRepository.require(c,shop,category);
                var inventory=category==null?new KizImportInventory(c):null;
                var review=category==null?inventory.review(shop,owner,null,preview):new ImportReview(marks.size(),0,0,0,0,0,0,0,0,0);
                // Only the older unclassified inventory API is idempotent. Seller
                // category uploads do not inspect existing files, codes or their states.
                if(category==null)try(var p=c.prepareStatement("SELECT id FROM kiz_import_batches WHERE shop_id=? AND owner=? AND file_hash=?")){
                    p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,preview.sha256());try(var r=p.executeQuery()){if(r.next())batch=r.getString(1);}
                }
                if(batch==null&&review.fresh()>0) {
                    batch=UUID.randomUUID().toString();
                    try(var p=c.prepareStatement("INSERT INTO kiz_import_batches VALUES(?,?,?,?,?,?,?)")){p.setString(1,batch);p.setInt(2,shop.getId());p.setString(3,owner);p.setString(4,scope.source());p.setString(5,scope.inn());p.setString(6,category==null?preview.sha256():preview.sha256()+"|"+batch);p.setString(7,now);p.executeUpdate();}
                }
                var orders=new HashMap<String,Long>();int inserted=0,duplicates=0,classified=0,moved=0;
                var legacyIdentities=new HashSet<String>();
                for(var mark:marks){
                    if(category==null&&!legacyIdentities.add(mark.cis())){duplicates++;continue;}
                    var disposition=category==null?inventory.disposition(shop,owner,null,mark):KizImportInventory.Disposition.NEW;
                    if(disposition!=KizImportInventory.Disposition.NEW) {
                        duplicates++;
                        if(category!=null&&(disposition==KizImportInventory.Disposition.UNASSIGNED||moveAvailable&&disposition==KizImportInventory.Disposition.OTHER_CATEGORY)) {
                            try(var p=c.prepareStatement("INSERT INTO kiz_stock_classifications(code_id,category_id,assigned_at) VALUES(?,?,?) ON CONFLICT(code_id) DO UPDATE SET category_id=excluded.category_id,assigned_at=excluded.assigned_at")) {
                                p.setLong(1,inventory.existing(mark).id());p.setString(2,category);p.setString(3,now);p.executeUpdate();
                            }
                            if(disposition==KizImportInventory.Disposition.UNASSIGNED)classified++;else moved++;
                        }
                        continue;
                    }
                    Long order=orders.get(mark.gtin());if(order==null){
                        // A file proves neither Catalog publication nor product metadata. Create only
                        // a quarantined placeholder. Category assignment authorizes local seller
                        // stock only; it never proves Catalog publication or authorizes buying codes.
                        try(var p=c.prepareStatement("INSERT INTO znack_products(shop_id,gtin,card_status,synced_at) VALUES(?,?,'imported_unverified',?) ON CONFLICT(shop_id,gtin) DO NOTHING")){
                            p.setInt(1,shop.getId());p.setString(2,mark.gtin());p.setString(3,now);p.executeUpdate();
                        }
                        try(var p=c.prepareStatement("SELECT 1 FROM znack_products WHERE shop_id=? AND gtin=?")){
                            p.setInt(1,shop.getId());p.setString(2,mark.gtin());try(var r=p.executeQuery()){if(!r.next())throw new IllegalStateException("KIZ_GTIN_PRODUCT_MISSING");}
                        }
                        try(var p=c.prepareStatement("INSERT INTO kiz_orders(shop_id,external_order_id,gtin,quantity,local_status,created_at,updated_at) VALUES(?,NULL,?,0,'IMPORTED',?,?)",Statement.RETURN_GENERATED_KEYS)){
                            p.setInt(1,shop.getId());p.setString(2,mark.gtin());p.setString(3,now);p.setString(4,now);p.executeUpdate();try(var r=p.getGeneratedKeys()){if(!r.next())throw new SQLException();order=r.getLong(1);}
                        }orders.put(mark.gtin(),order);
                    }
                    long id;try(var p=c.prepareStatement("INSERT INTO kiz_codes(shop_id,order_id,raw_code,display_code,gtin,status,legal_status,source_priority,created_at,updated_at,stock_instance) VALUES(?,?,?,?,?,'AVAILABLE','RECEIVED',0,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                        p.setInt(1,shop.getId());p.setLong(2,order);p.setString(3,mark.raw());p.setString(4,mark.cis());p.setString(5,mark.gtin());p.setString(6,now);p.setString(7,now);p.setString(8,category==null?"":UUID.randomUUID().toString());p.executeUpdate();try(var r=p.getGeneratedKeys()){if(!r.next())throw new SQLException();id=r.getLong(1);}
                    }
                    try(var p=c.prepareStatement("INSERT INTO kiz_import_entries(code_id,batch_id,cis) VALUES(?,?,?)")){p.setLong(1,id);p.setString(2,batch);p.setString(3,mark.cis());p.executeUpdate();}
                    if(category!=null)try(var p=c.prepareStatement("INSERT INTO kiz_stock_classifications VALUES(?,?,?)")){p.setLong(1,id);p.setString(2,category);p.setString(3,now);p.executeUpdate();}
                    inserted++;
                }
                for(var order:orders.values())try(var p=c.prepareStatement("UPDATE kiz_orders SET quantity=(SELECT count(*) FROM kiz_codes WHERE order_id=?) WHERE id=? AND shop_id=?")){p.setLong(1,order);p.setLong(2,order);p.setInt(3,shop.getId());p.executeUpdate();}
                return new Imported(Objects.toString(batch,""),inserted,duplicates+preview.duplicates(),classified,moved,review);
            });
        }catch(SQLException storage){throw new IllegalStateException("KIZ_IMPORT_STORAGE_FAILED",storage);}
    }
    public List<Pending> pending(Shop shop,String source,int limit,long afterId){
        if(limit<1||limit>1000)throw new IllegalArgumentException("KIZ_VERIFY_LIMIT");
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            SELECT k.id,k.raw_code,i.cis,k.gtin FROM kiz_import_entries i
            JOIN kiz_codes k ON k.id=i.code_id JOIN kiz_import_batches b ON b.id=i.batch_id
            WHERE k.shop_id=? AND b.owner=? AND b.source=? AND k.status='AVAILABLE' AND i.verified_at IS NULL AND k.id>?
            ORDER BY k.id LIMIT ?
            """)){
            requireOwner(c,shop,ListingIdentity.fingerprint(shop));p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,source);p.setLong(4,afterId);p.setInt(5,limit);
            var out=new ArrayList<Pending>();try(var r=p.executeQuery()){while(r.next())out.add(new Pending(r.getLong(1),r.getString(2),r.getString(3),r.getString(4)));}return List.copyOf(out);
        }catch(SQLException failed){throw new IllegalStateException("KIZ_IMPORT_STORAGE_FAILED");}
    }
    public void verified(Shop shop,String source,List<Pending> requested,Map<String,Verdict> verdicts){
        if(requested.size()>1000)throw new IllegalArgumentException("KIZ_VERIFY_LIMIT");
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);try{
                requireOwner(c,shop,ListingIdentity.fingerprint(shop));String now=Instant.now().toString();
                for(var item:requested){var verdict=verdicts.get(item.cis());if(verdict==null)continue;
                    try(var p=c.prepareStatement("""
                        UPDATE kiz_codes SET legal_status=?,updated_at=? WHERE id=? AND shop_id=? AND status='AVAILABLE'
                          AND EXISTS(SELECT 1 FROM kiz_import_entries i JOIN kiz_import_batches b ON b.id=i.batch_id
                            WHERE i.code_id=kiz_codes.id AND i.cis=? AND b.owner=? AND b.source=?)
                        """)){
                        p.setString(1,verdict.ready()?"IN_CIRCULATION":"RECEIVED");p.setString(2,now);p.setLong(3,item.id());p.setInt(4,shop.getId());p.setString(5,item.cis());p.setString(6,ListingIdentity.fingerprint(shop));p.setString(7,source);
                        if(p.executeUpdate()==1)try(var q=c.prepareStatement("UPDATE kiz_import_entries SET reason=?,verified_at=? WHERE code_id=?")){q.setString(1,verdict.reason());q.setString(2,verdict.ready()?now:null);q.setLong(3,item.id());q.executeUpdate();}
                    }
                }c.commit();
            }catch(Exception failed){c.rollback();throw failed;}
        }catch(SQLException storage){throw new IllegalStateException("KIZ_IMPORT_STORAGE_FAILED");}
    }
    public List<Summary> summaries(Shop shop){
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            SELECT k.gtin,count(*),sum(k.status='AVAILABLE' AND (k.legal_status='IN_CIRCULATION' OR category.id IS NOT NULL)),
              sum(k.status='RESERVED'),sum(k.status='CONSUMED'),sum(k.status='AVAILABLE' AND COALESCE(k.legal_status,'')<>'IN_CIRCULATION' AND category.id IS NULL),
              COALESCE(group_concat(DISTINCT category.name),'')
            FROM kiz_codes k JOIN kiz_import_entries i ON i.code_id=k.id JOIN kiz_import_batches b ON b.id=i.batch_id
            LEFT JOIN kiz_stock_classifications classification ON classification.code_id=k.id
            LEFT JOIN kiz_stock_categories category ON category.id=classification.category_id AND category.owner=b.owner
            WHERE k.shop_id=? AND b.owner=? GROUP BY k.gtin ORDER BY k.gtin LIMIT 10001
            """)){
            requireOwner(c,shop,ListingIdentity.fingerprint(shop));p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));var out=new ArrayList<Summary>();try(var r=p.executeQuery()){while(r.next())out.add(new Summary(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getInt(6),r.getString(7)));}return List.copyOf(out);
        }catch(SQLException failed){throw new IllegalStateException("KIZ_IMPORT_STORAGE_FAILED");}
    }
    public Map<String,Integer> issues(Shop shop){
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            SELECT 'KIZ_CATEGORY_REQUIRED',count(*) FROM kiz_import_entries i JOIN kiz_codes k ON k.id=i.code_id JOIN kiz_import_batches b ON b.id=i.batch_id
            WHERE k.shop_id=? AND b.owner=? AND k.status='AVAILABLE' AND COALESCE(k.legal_status,'')<>'IN_CIRCULATION' AND NOT EXISTS(SELECT 1 FROM kiz_stock_classifications category WHERE category.code_id=k.id)
            GROUP BY 1 ORDER BY count(*) DESC LIMIT 20
            """)){
            requireOwner(c,shop,ListingIdentity.fingerprint(shop));p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));var out=new LinkedHashMap<String,Integer>();try(var r=p.executeQuery()){while(r.next())out.put(r.getString(1),r.getInt(2));}return Collections.unmodifiableMap(out);
        }catch(SQLException failed){throw new IllegalStateException("KIZ_IMPORT_STORAGE_FAILED");}
    }
    /** Archive unused seller imports; restore into the same local category without remote verification. */
    public int changeArchive(Shop shop,String gtin,boolean restore){
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);try{
                String owner=ListingIdentity.fingerprint(shop);requireOwner(c,shop,owner);int changed;
                try(var p=c.prepareStatement("UPDATE kiz_codes SET status=?,legal_status='RECEIVED',updated_at=? WHERE shop_id=? AND gtin=? AND status=? "+"AND EXISTS(SELECT 1 FROM kiz_import_entries i JOIN kiz_import_batches b ON b.id=i.batch_id WHERE i.code_id=kiz_codes.id AND b.owner=?)")){
                    p.setString(1,restore?"AVAILABLE":"ARCHIVED");p.setString(2,Instant.now().toString());p.setInt(3,shop.getId());p.setString(4,com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin));p.setString(5,restore?"ARCHIVED":"AVAILABLE");p.setString(6,owner);changed=p.executeUpdate();
                }
                if(restore)try(var p=c.prepareStatement("UPDATE kiz_import_entries SET verified_at=NULL,reason='KIZ_NOT_VERIFIED' WHERE code_id IN (SELECT id FROM kiz_codes WHERE shop_id=? AND gtin=? AND status='AVAILABLE' AND legal_status='RECEIVED')")){p.setInt(1,shop.getId());p.setString(2,com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin));p.executeUpdate();}
                c.commit();return changed;
            }catch(Exception failed){c.rollback();throw failed;}
        }catch(SQLException failed){throw new IllegalStateException("KIZ_IMPORT_STORAGE_FAILED");}
    }
    public boolean autoPurchase(Shop shop){try(var c=Database.getConnection();var p=c.prepareStatement("SELECT auto_purchase FROM kiz_warehouse_settings WHERE shop_id=? AND owner=?")){
        requireOwner(c,shop,ListingIdentity.fingerprint(shop));p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));try(var r=p.executeQuery()){return r.next()&&r.getInt(1)==1;}
    }catch(SQLException failed){throw new IllegalStateException("KIZ_IMPORT_STORAGE_FAILED");}}
    public void autoPurchase(Shop shop,boolean enabled){try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO kiz_warehouse_settings VALUES(?,?,?) ON CONFLICT(shop_id) DO UPDATE SET owner=excluded.owner,auto_purchase=excluded.auto_purchase")){
        requireOwner(c,shop,ListingIdentity.fingerprint(shop));p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setInt(3,enabled?1:0);p.executeUpdate();
    }catch(SQLException failed){throw new IllegalStateException("KIZ_IMPORT_STORAGE_FAILED");}}
    public static String currentOwner(Connection c,int shopId)throws SQLException{
        try(var p=c.prepareStatement("SELECT name,marketplace,client_id,api_key FROM shops WHERE id=?")){p.setInt(1,shopId);try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("SHOP_CREDENTIALS_UNAVAILABLE");return ListingIdentity.fingerprint(new Shop(shopId,r.getString(1),com.vncode.app.integration.marketplace.Marketplace.valueOf(r.getString(2)),r.getString(3),r.getString(4)));}}
    }
    /** Local category provenance: keep the shop/account boundary, independent of certificate/INN/API availability. */
    public static String eligibleCategory(String alias){return "NOT EXISTS(SELECT 1 FROM kiz_import_entries ownership JOIN kiz_import_batches batch ON batch.id=ownership.batch_id WHERE ownership.code_id="+alias+".id AND (batch.owner<>? OR batch.shop_id<>"+alias+".shop_id))";}
    /** Used only when an imported category row has already been selected under the owner guard. */
    public static String categoryStock(String alias){return "EXISTS(SELECT 1 FROM kiz_stock_classifications category_stock JOIN kiz_import_entries imported ON imported.code_id=category_stock.code_id JOIN kiz_import_batches batch ON batch.id=imported.batch_id JOIN kiz_stock_categories category ON category.id=category_stock.category_id WHERE category_stock.code_id="+alias+".id AND batch.shop_id="+alias+".shop_id AND category.shop_id=batch.shop_id AND category.owner=batch.owner)";}
    public static String eligible(String alias){return "NOT EXISTS(SELECT 1 FROM kiz_import_entries ownership JOIN kiz_import_batches batch ON batch.id=ownership.batch_id LEFT JOIN znack_settings config ON config.shop_id=batch.shop_id WHERE ownership.code_id="+alias+".id AND (batch.owner<>? OR batch.inn<>COALESCE(NULLIF(config.owner_inn,''),NULLIF(config.participant_inn,''),CASE WHEN json_valid(config.certificate_metadata_json) THEN json_extract(config.certificate_metadata_json,'$.inn') END,'')))";}
    /** Category stock belongs to its explicit product pool, never to another product's exact-GTIN fallback. */
    public static String eligibleExact(String alias){return eligible(alias)+" AND NOT EXISTS(SELECT 1 FROM kiz_stock_classifications classification JOIN kiz_stock_categories category ON category.id=classification.category_id JOIN kiz_import_entries imported ON imported.code_id=classification.code_id JOIN kiz_import_batches batch ON batch.id=imported.batch_id WHERE classification.code_id="+alias+".id AND category.shop_id="+alias+".shop_id AND category.owner=batch.owner)";}
    /** Explicit re-verification may rebind a rotated credential, but first quarantines every available imported code. */
    public void prepareVerification(Shop shop,String source,String inn){
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);try{
                String owner=ListingIdentity.fingerprint(shop);requireOwner(c,shop,owner);
                String affected="SELECT i.code_id FROM kiz_import_entries i JOIN kiz_import_batches b ON b.id=i.batch_id WHERE b.shop_id=? AND (b.owner<>? OR b.source<>?)";
                try(var p=c.prepareStatement("UPDATE kiz_codes SET legal_status='RECEIVED' WHERE status='AVAILABLE' AND id IN ("+affected+")")){p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,source);p.executeUpdate();}
                try(var p=c.prepareStatement("UPDATE kiz_import_entries SET verified_at=NULL,reason='KIZ_NOT_VERIFIED' WHERE code_id IN (SELECT id FROM kiz_codes WHERE status='AVAILABLE') AND code_id IN ("+affected+")")){p.setInt(1,shop.getId());p.setString(2,owner);p.setString(3,source);p.executeUpdate();}
                try(var p=c.prepareStatement("UPDATE kiz_import_batches SET owner=?,source=?,inn=? WHERE shop_id=?")){p.setString(1,owner);p.setString(2,source);p.setString(3,inn);p.setInt(4,shop.getId());p.executeUpdate();}c.commit();
            }catch(Exception failed){c.rollback();throw failed;}
        }catch(SQLException failed){throw new IllegalStateException("KIZ_IMPORT_STORAGE_FAILED");}
    }
    private static void requireOwner(Connection c,Shop shop,String owner)throws SQLException{if(!owner.equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");}
}
