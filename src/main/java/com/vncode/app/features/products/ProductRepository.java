package com.vncode.app.features.products;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import org.sqlite.Function;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Shop-scoped projections; a page and its count share one SQLite read snapshot. */
public final class ProductRepository {
    private record Cursor(String scope,String title,String card,String variant) {}
    private static final String WB = """
        SELECT CAST(c.nm_id AS TEXT) card_id,COALESCE(CAST(v.chrt_id AS TEXT),'') variant_id,
          COALESCE(c.title,'') title,COALESCE(c.vendor_code,'') article,CAST(c.nm_id AS TEXT) market_article,
          COALESCE(CAST(c.subject_id AS TEXT),c.subject_name,'') category_id,COALESCE(c.subject_name,'') category,
          COALESCE(c.brand,'') brand,COALESCE(v.tech_size,'') size,
          COALESCE((SELECT group_concat(b.sku,char(31)) FROM wb_product_size_skus b
                    WHERE b.shop_id=c.shop_id AND b.chrt_id=v.chrt_id),'') barcodes,
          COALESCE((SELECT COALESCE(p.c246x328_url,p.square_url,p.big_url,p.hq_url) FROM wb_product_photos p
                    WHERE p.shop_id=c.shop_id AND p.nm_id=c.nm_id ORDER BY p.photo_index LIMIT 1),'') image,
          COALESCE((SELECT json_extract(ch.value_json,'$[0]') FROM wb_product_characteristics ch
                    WHERE ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id AND ch.characteristic_id IN (14177449,204557)
                    ORDER BY ch.characteristic_id DESC LIMIT 1),'') color,
          COALESCE((SELECT COALESCE(json_extract(ch.value_json,'$[0]'),json_extract(ch.value_json,'$'))
                    FROM wb_product_characteristics ch WHERE ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id
                    AND vn_casefold(ch.name) IN ('состав','материал','chất liệu','material') LIMIT 1),'') material,
          COALESCE((SELECT COALESCE(json_extract(ch.value_json,'$[0]'),json_extract(ch.value_json,'$'))
                    FROM wb_product_characteristics ch WHERE ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id
                    AND vn_casefold(ch.name) IN ('тнвэд','тн вэд','tn ved') LIMIT 1),'') tnved,
          COALESCE((SELECT COALESCE(json_extract(ch.value_json,'$[0]'),json_extract(ch.value_json,'$'))
                    FROM wb_product_characteristics ch WHERE ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id
                    AND vn_field_match('gender',ch.name)=1 LIMIT 1),'') gender,1 attributes_loaded
        FROM wb_product_cards c LEFT JOIN wb_product_sizes v ON v.shop_id=c.shop_id AND v.nm_id=c.nm_id
        WHERE c.shop_id=?
        """;
    private static final String OZON = """
        SELECT p.product_id card_id,COALESCE(p.offer_id,'') variant_id,COALESCE(p.name,'') title,
          COALESCE(p.article,p.offer_id,'') article,COALESCE(p.sku,p.product_id) market_article,
          COALESCE(p.category,'') category_id,COALESCE(p.category,'') category,
          COALESCE(json_extract(ps.payload,'$.brand'),'') brand,COALESCE(p.size,'') size,
          COALESCE((SELECT group_concat(b.barcode,char(31)) FROM ozon_product_barcodes b
                    WHERE b.shop_id=p.shop_id AND b.product_id=p.product_id),'') barcodes,
          COALESCE(p.primary_image_url,'') image,COALESCE(p.color,'') color,
          COALESCE((SELECT group_concat(v.value,', ') FROM json_each(ps.payload,'$.attributes') a
            JOIN json_each(a.value,'$.values') v WHERE vn_field_match('material',json_extract(a.value,'$.name'))=1
            AND COALESCE(json_extract(a.value,'$.complexId'),'0') IN ('','0')),'') material,
          COALESCE((SELECT group_concat(v.value,', ') FROM json_each(ps.payload,'$.attributes') a
            JOIN json_each(a.value,'$.values') v WHERE vn_field_match('tnved',json_extract(a.value,'$.name'))=1
            AND COALESCE(json_extract(a.value,'$.complexId'),'0') IN ('','0')),'') tnved,
          COALESCE((SELECT group_concat(v.value,', ') FROM json_each(ps.payload,'$.attributes') a
            JOIN json_each(a.value,'$.values') v WHERE vn_field_match('gender',json_extract(a.value,'$.name'))=1
            AND COALESCE(json_extract(a.value,'$.complexId'),'0') IN ('','0')),'') gender,
          CASE WHEN ps.payload IS NULL THEN 0 ELSE 1 END attributes_loaded
        FROM ozon_products p LEFT JOIN product_snapshots ps ON ps.shop_id=p.shop_id AND ps.marketplace='OZON'
          AND ps.card_id=p.product_id AND ps.variant_id=COALESCE(p.offer_id,'')
          AND json_extract(ps.payload,'$.metadata.owner')=? AND json_extract(ps.payload,'$.revision') LIKE 'live:%'
        WHERE p.shop_id=? AND p.archived=0
        """;

    public ProductPage loadPage(ProductQuery query) {
        try(var c=open()) {
            c.setAutoCommit(false);requireOwner(c,query.shopId(),query.marketplace());
            var parameters=new ArrayList<Object>();String projection=projection(query,parameters,c);
            String predicate=filter(query,parameters);String scope=scope(query);
            var counts=countIssues(c,query);
            long total=counts.get(query.issue());
            String cursorPredicate="";
            if(query.cursor()!=null&&!query.cursor().isBlank()) {
                Cursor cursor;
                try {cursor=ProductJson.GSON.fromJson(new String(Base64.getUrlDecoder().decode(query.cursor()),StandardCharsets.UTF_8),Cursor.class);}catch(RuntimeException bad){throw new IllegalArgumentException("Invalid product cursor");}
                if(cursor==null||!scope.equals(cursor.scope()))throw new IllegalArgumentException("Product cursor belongs to another filter");
                cursorPredicate=" AND (sort_title,card_id,variant_id)>(?,?,?)";
                parameters.add(cursor.title());parameters.add(cursor.card());parameters.add(cursor.variant());
            }
            parameters.add(query.limit()+1);
            var rows=new ArrayList<ProductRow>();
            String sql="SELECT * FROM ("+projection+") x WHERE "+predicate+cursorPredicate+" ORDER BY sort_title,card_id,variant_id LIMIT ?";
            try(var p=c.prepareStatement(sql)){bind(p,parameters);try(var r=p.executeQuery()){while(r.next())rows.add(row(query,r));}}
            boolean more=rows.size()>query.limit();if(more)rows.removeLast();String next=null;
            if(more) {var last=rows.getLast();next=Base64.getUrlEncoder().withoutPadding().encodeToString(ProductJson.GSON.toJson(new Cursor(scope,fold(last.title()),last.key().cardId(),last.key().variantId())).getBytes(StandardCharsets.UTF_8));}
            c.commit();return new ProductPage(rows,total,next,counts);
        }catch(SQLException error){throw storeError();}
    }
    private static Map<ProductIssueFilter,Long> countIssues(Connection c,ProductQuery q)throws SQLException {
        var base=new ProductQuery(q.shopId(),q.marketplace(),q.text(),q.categoryIds(),q.documentState(),q.missingGtin(),null,q.limit());
        var args=new ArrayList<Object>();var projection=projection(base,args,c);var predicate=filter(base,args);
        var result=new EnumMap<ProductIssueFilter,Long>(ProductIssueFilter.class);
        String sql="""
            SELECT count(*),COALESCE(sum(attributes_loaded=0),0),
              COALESCE(sum(attributes_loaded=1 AND trim(brand)=''),0),
              COALESCE(sum(attributes_loaded=1 AND trim(material)=''),0),
              COALESCE(sum(attributes_loaded=1 AND (length(tnved)<>10 OR tnved GLOB '*[^0-9]*')),0)
            FROM (
            """+projection+") x WHERE "+predicate;
        try(var p=c.prepareStatement(sql)){bind(p,args);try(var r=p.executeQuery()){r.next();int i=1;for(var issue:ProductIssueFilter.values())result.put(issue,r.getLong(i++));}}
        return Map.copyOf(result);
    }
    public List<ProductKey> freezeKeys(ProductQuery query) {
        try(var c=open()) {
            c.setAutoCommit(false);requireOwner(c,query.shopId(),query.marketplace());var args=new ArrayList<Object>();String projection=projection(query,args,c);String predicate=filter(query,args);
            var keys=new ArrayList<ProductKey>();
            try(var p=c.prepareStatement("SELECT card_id,variant_id FROM ("+projection+") x WHERE "+predicate+" ORDER BY sort_title,card_id,variant_id LIMIT 100001")) {
                bind(p,args);try(var r=p.executeQuery()){while(r.next())keys.add(new ProductKey(query.shopId(),query.marketplace(),r.getString(1),r.getString(2)));}
            }
            if(keys.size()>100000)throw new IllegalArgumentException("Selection too large");c.commit();return List.copyOf(keys);
        }catch(SQLException error){throw storeError();}
    }
    public List<String> categories(int shop,Marketplace market) {
        try(var c=open()) {
            requireOwner(c,shop,market);String sql=market==Marketplace.OZON?"SELECT DISTINCT category FROM ozon_products WHERE shop_id=? AND archived=0 ORDER BY category":"SELECT DISTINCT subject_name FROM wb_product_cards WHERE shop_id=? ORDER BY subject_name";
            try(var p=c.prepareStatement(sql)){p.setInt(1,shop);try(var r=p.executeQuery()){var values=new ArrayList<String>();while(r.next())if(r.getString(1)!=null&&!r.getString(1).isBlank())values.add(r.getString(1));return List.copyOf(values);}}
        }catch(SQLException error){throw storeError();}
    }
    private static String projection(ProductQuery q,List<Object> args,Connection c)throws SQLException {
        args.add(q.shopId());args.add(q.marketplace().name());args.add(q.shopId());args.add(q.marketplace().name());
        if(q.marketplace()==Marketplace.OZON)args.add(currentOwner(c,new ProductKey(q.shopId(),q.marketplace(),"scope","scope")));
        args.add(q.shopId());
        return """
            SELECT b.*,vn_casefold(b.title) sort_title,
              COALESCE((SELECT vn_verified_gtin(b.barcodes,m.gtin) FROM gtin_sync_mappings m JOIN gtin_sync_catalog g
                ON g.shop_id=m.shop_id AND g.marketplace=m.marketplace AND g.gtin=m.gtin
                WHERE m.shop_id=? AND m.marketplace=? AND m.product_id=b.card_id AND m.variant_id=b.variant_id
                  AND json_extract(g.data_json,'$.published')=1 AND json_extract(g.data_json,'$.tradeUnit')=1),'') verified_gtin,
              COALESCE((SELECT CASE
                  WHEN count(*)=0 THEN 'MISSING'
                  WHEN sum(CASE WHEN d.state='EXPIRED' OR (d.valid_until IS NOT NULL AND d.valid_until<date('now')) THEN 1 ELSE 0 END)>0 THEN 'EXPIRED'
                  WHEN sum(CASE WHEN d.state='REJECTED' THEN 1 ELSE 0 END)>0 THEN 'REJECTED'
                  WHEN sum(CASE WHEN d.state='PENDING' THEN 1 ELSE 0 END)>0 THEN 'PENDING'
                  WHEN min(CASE WHEN d.state='VALID' AND d.source LIKE 'https://%' AND d.verified_at IS NOT NULL THEN 1 ELSE 0 END)=1 THEN 'VALID'
                  ELSE 'UNVERIFIED' END FROM product_documents d
                WHERE d.shop_id=? AND d.marketplace=? AND d.card_id=b.card_id),'MISSING') document_state
            FROM (
            """+(q.marketplace()==Marketplace.OZON?OZON:WB)+") b";
    }
    private static String filter(ProductQuery q,List<Object> args) {
        var clauses=new ArrayList<String>();clauses.add("1=1");
        if(!q.text().isBlank()) {
            String match="%"+fold(q.text()).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            if(q.marketplace()==Marketplace.OZON) {
                clauses.add("(vn_casefold(title||' '||article||' '||market_article||' '||barcodes||' '||verified_gtin) LIKE ? ESCAPE '\\' OR EXISTS (SELECT 1 FROM ozon_card_registrations registration WHERE registration.shop_id=? AND registration.product_id=card_id AND registration.offer_id=variant_id AND (registration.gtin LIKE ? ESCAPE '\\' OR registration.active_gtin LIKE ? ESCAPE '\\'))) ");
                args.add(match);args.add(q.shopId());args.add(match);args.add(match);
            } else {
                clauses.add("vn_casefold(title||' '||article||' '||market_article||' '||barcodes||' '||verified_gtin) LIKE ? ESCAPE '\\'");args.add(match);
            }
        }
        if(!q.categoryIds().isEmpty()){clauses.add("(category_id IN ("+String.join(",",Collections.nCopies(q.categoryIds().size(),"?"))+") OR category IN ("+String.join(",",Collections.nCopies(q.categoryIds().size(),"?"))+"))");args.addAll(q.categoryIds());args.addAll(q.categoryIds());}
        if(q.documentState()!=null){clauses.add("document_state=?");args.add(q.documentState().name());}
        if(q.missingGtin())clauses.add("verified_gtin=''");
        switch(q.issue()) {
            case ALL -> {}
            case ATTRIBUTES_UNLOADED -> clauses.add("attributes_loaded=0");
            case MISSING_BRAND -> clauses.add("attributes_loaded=1 AND trim(brand)=''");
            case MISSING_MATERIAL -> clauses.add("attributes_loaded=1 AND trim(material)=''");
            case INVALID_TNVED -> clauses.add("attributes_loaded=1 AND (length(tnved)<>10 OR tnved GLOB '*[^0-9]*')");
        }
        return String.join(" AND ",clauses);
    }
    private static ProductRow row(ProductQuery q,ResultSet r)throws SQLException {
        List<String> codes=r.getString("barcodes").isBlank()?List.of():Arrays.stream(r.getString("barcodes").split("\u001f")).distinct().sorted().toList();
        String candidate=r.getString("verified_gtin");String verified=RegisteredGtinValidator.isValid(candidate)&&codes.stream().anyMatch(x->padGtin(x).equals(padGtin(candidate)))?candidate:"";
        return new ProductRow(new ProductKey(q.shopId(),q.marketplace(),r.getString("card_id"),r.getString("variant_id")),r.getString("title"),r.getString("article"),r.getString("market_article"),r.getString("category"),r.getString("brand"),r.getString("color"),r.getString("size"),codes,verified,r.getString("image"),r.getString("material"),r.getString("tnved"),DocumentState.valueOf(r.getString("document_state")),r.getString("gender"),r.getInt("attributes_loaded")==1);
    }
    private static String padGtin(String value){return value.matches("[0-9]{8,14}")?"0".repeat(14-value.length())+value:value;}
    public Optional<ProductSnapshot> localSnapshot(ProductKey key) {
        try(var c=open()) {
            requireOwner(c,key.shopId(),key.marketplace());
            try(var p=c.prepareStatement("SELECT payload FROM product_snapshots WHERE shop_id=? AND marketplace=? AND card_id=? AND variant_id=?")) {
                bindKey(p,key);try(var r=p.executeQuery()){if(r.next()){var snapshot=ProductJson.GSON.fromJson(r.getString(1),ProductSnapshot.class);if(!key.equals(snapshot.key()))throw new SecurityException("Product snapshot owner mismatch");String owner=snapshot.metadata().get("owner");if(owner==null||owner.equals(currentOwner(c,key)))return Optional.of(snapshot);}}
            }
            return key.marketplace()==Marketplace.WILDBERRIES?wbSnapshot(c,key):ozonSnapshot(c,key);
        }catch(SQLException error){throw storeError();}
    }
    private static Optional<ProductSnapshot> wbSnapshot(Connection c,ProductKey key)throws SQLException {
        try(var p=c.prepareStatement("SELECT * FROM wb_product_cards WHERE shop_id=? AND CAST(nm_id AS TEXT)=?")) {
            p.setInt(1,key.shopId());p.setString(2,key.cardId());try(var r=p.executeQuery()) {
                if(!r.next())return Optional.empty();var media=new ArrayList<ProductMedia>();var attrs=new ArrayList<ProductAttribute>();var variants=new ArrayList<ProductVariant>();
                try(var photos=c.prepareStatement("SELECT COALESCE(hq_url,big_url,c516x688_url,c246x328_url) FROM wb_product_photos WHERE shop_id=? AND CAST(nm_id AS TEXT)=? ORDER BY photo_index")) {
                    photos.setInt(1,key.shopId());photos.setString(2,key.cardId());try(var a=photos.executeQuery()){while(a.next())safeMedia(a.getString(1)).ifPresent(media::add);}
                }
                try(var fields=c.prepareStatement("SELECT characteristic_id,name,value_json FROM wb_product_characteristics WHERE shop_id=? AND CAST(nm_id AS TEXT)=? ORDER BY characteristic_id")) {
                    fields.setInt(1,key.shopId());fields.setString(2,key.cardId());try(var a=fields.executeQuery()){while(a.next()) {var values=new ArrayList<String>();try {var json=JsonParser.parseString(a.getString(3));if(json.isJsonArray())json.getAsJsonArray().forEach(v->values.add(v.isJsonPrimitive()?v.getAsString():v.toString()));else if(json.isJsonPrimitive())values.add(json.getAsString());}catch(RuntimeException ignored){}attrs.add(new ProductAttribute(a.getString(1),safe(a.getString(2)),values,""));}}
                }
                try(var sizes=c.prepareStatement("SELECT CAST(v.chrt_id AS TEXT),v.tech_size FROM wb_product_sizes v WHERE v.shop_id=? AND CAST(v.nm_id AS TEXT)=? ORDER BY v.chrt_id")) {
                    sizes.setInt(1,key.shopId());sizes.setString(2,key.cardId());try(var a=sizes.executeQuery()){while(a.next())variants.add(new ProductVariant(a.getString(1),safe(r.getString("vendor_code")),"",safe(a.getString(2)),"",false));}
                }
                if(!key.variantId().isBlank()&&variants.stream().noneMatch(v->v.id().equals(key.variantId())))throw new SecurityException("Unknown product variant");
                return Optional.of(new ProductSnapshot(key,"cache:"+hash(safe(r.getString("updated_at"))+safe(r.getString("synced_at"))),safe(r.getString("title")),safe(r.getString("description")),safe(r.getString("brand")),safe(r.getString("vendor_code")),safe(r.getString("subject_id")),media,attrs,variants,new PackageDimensions(decimal(r,"dimension_length"),decimal(r,"dimension_width"),decimal(r,"dimension_height"),decimal(r,"dimension_weight_brutto")),documents(c,key),Instant.now()));
            }
        }
    }
    private static Optional<ProductSnapshot> ozonSnapshot(Connection c,ProductKey key)throws SQLException {
        try(var p=c.prepareStatement("SELECT * FROM ozon_products WHERE shop_id=? AND product_id=?")) {
            p.setInt(1,key.shopId());p.setString(2,key.cardId());try(var r=p.executeQuery()){if(!r.next())return Optional.empty();if(!key.variantId().equals(safe(r.getString("offer_id"))))throw new SecurityException("Unknown product variant");return Optional.of(new ProductSnapshot(key,"summary:"+hash(safe(r.getString("updated_at"))),safe(r.getString("name")),"","",safe(r.getString("article")),safe(r.getString("category")),safeMedia(r.getString("primary_image_url")).stream().toList(),List.of(),List.of(new ProductVariant(key.variantId(),safe(r.getString("offer_id")),safe(r.getString("color")),safe(r.getString("size")),"",false)),null,documents(c,key),Instant.now()));}
        }
    }
    private static List<ProductDocument> documents(Connection c,ProductKey key)throws SQLException {
        try(var p=c.prepareStatement("SELECT payload FROM product_documents WHERE shop_id=? AND marketplace=? AND card_id=? ORDER BY document_id")) {
            p.setInt(1,key.shopId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());try(var r=p.executeQuery()){var out=new ArrayList<ProductDocument>();while(r.next())out.add(ProductJson.GSON.fromJson(r.getString(1),ProductDocument.class));return List.copyOf(out);}
        }
    }
    public void saveSnapshot(ProductSnapshot snapshot) {
        saveSnapshot(snapshot,false);
    }
    /** Only complete, current-credential marketplace reads can replace a card's document set. */
    public void saveVerifiedSnapshot(Shop owner,ProductSnapshot snapshot){
        com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(owner.getId(),()->{
            ListingIdentity.require(owner,snapshot.key());
            if(!snapshot.revision().startsWith("live:")||!ListingIdentity.fingerprint(owner).equals(snapshot.metadata().get("owner")))throw new SecurityException("Verified product snapshot required");
            saveSnapshot(snapshot,true);return null;
        });
    }
    private void saveSnapshot(ProductSnapshot snapshot,boolean complete) {
        try(var c=open()) {
            c.setAutoCommit(false);var key=snapshot.key();requireOwner(c,key.shopId(),key.marketplace());
            if(complete){
                if(!snapshot.metadata().get("owner").equals(currentOwner(c,key)))throw new SecurityException("Product credentials changed");
                for(String table:List.of("product_snapshots","product_documents"))try(var clear=c.prepareStatement("DELETE FROM "+table+" WHERE shop_id=? AND marketplace=? AND card_id=?")){clear.setInt(1,key.shopId());clear.setString(2,key.marketplace().name());clear.setString(3,key.cardId());clear.executeUpdate();}
            }
            try(var p=c.prepareStatement("INSERT INTO product_snapshots VALUES(?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,card_id,variant_id) DO UPDATE SET payload=excluded.payload,checked_at=excluded.checked_at")) {
                bindKey(p,key);p.setString(5,ProductJson.GSON.toJson(snapshot));p.setString(6,snapshot.checkedAt().toString());p.executeUpdate();
            }
            // Partial imported caches merge documents; a verified complete card replaces its shared set.
            try(var p=c.prepareStatement("INSERT INTO product_documents VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,card_id,document_id) DO UPDATE SET state=excluded.state,valid_until=excluded.valid_until,source=excluded.source,verified_at=excluded.verified_at,payload=excluded.payload")) {
                for(var doc:snapshot.documents()){p.setInt(1,key.shopId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());p.setString(4,doc.id());p.setString(5,doc.state().name());p.setString(6,doc.validUntil()==null?null:doc.validUntil().toString());p.setString(7,doc.source());p.setString(8,doc.checkedAt()==null?null:doc.checkedAt().toString());p.setString(9,ProductJson.GSON.toJson(doc));p.addBatch();}p.executeBatch();
            }
            c.commit();
        }catch(SQLException error){throw storeError();}
    }
    private static String currentOwner(Connection c,ProductKey key)throws SQLException{
        try(var q=c.prepareStatement("SELECT marketplace,client_id,api_key FROM shops WHERE id=?")){q.setInt(1,key.shopId());try(var r=q.executeQuery()){if(!r.next()||!key.marketplace().name().equals(r.getString(1)))throw new SecurityException("Product owner changed");return ListingIdentity.fingerprint(new Shop(key.shopId(),"cache",key.marketplace(),r.getString(2),r.getString(3)));}}
    }
    public static void requireOwner(Connection c,int shop,Marketplace market)throws SQLException {
        try(var p=c.prepareStatement("SELECT marketplace FROM shops WHERE id=?")){p.setInt(1,shop);try(var r=p.executeQuery()){if(!r.next()||!market.name().equals(r.getString(1)))throw new SecurityException("Product shop ownership mismatch");}}
    }
    private static Connection open()throws SQLException {
        Connection c=Database.getConnection();try {
            Function.create(c,"vn_field_match",new Function(){protected void xFunc()throws SQLException{result(ProductFieldNames.matches(value_text(0),value_text(1))?1:0);}},2,Function.FLAG_DETERMINISTIC);
            Function.create(c,"vn_casefold",new Function(){protected void xFunc()throws SQLException{result(fold(value_text(0)));}},1,Function.FLAG_DETERMINISTIC);
            Function.create(c,"vn_verified_gtin",new Function(){protected void xFunc()throws SQLException {
                String gtin=safe(value_text(1));String codes=safe(value_text(0));
                boolean verified=RegisteredGtinValidator.isValid(gtin)&&Arrays.stream(codes.split("\u001f")).anyMatch(x->padGtin(x).equals(padGtin(gtin)));
                result(verified?gtin:"");
            }},2,Function.FLAG_DETERMINISTIC);return c;}catch(SQLException error){c.close();throw error;}
    }
    private static String fold(String value){return Normalizer.normalize(safe(value),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private static String safe(String value){return value==null?"":value;}
    private static BigDecimal decimal(ResultSet r,String column)throws SQLException {String value=r.getString(column);return value==null?null:new BigDecimal(value);}
    private static Optional<ProductMedia> safeMedia(String value){try {if(value==null||value.isBlank())return Optional.empty();URI uri=URI.create(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)return Optional.empty();return Optional.of(new ProductMedia(uri,null,null,null,null));}catch(RuntimeException bad){return Optional.empty();}}
    private static void bindKey(PreparedStatement p,ProductKey key)throws SQLException {p.setInt(1,key.shopId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());p.setString(4,key.variantId());}
    private static void bind(PreparedStatement p,List<Object> args)throws SQLException {for(int i=0;i<args.size();i++)p.setObject(i+1,args.get(i));}
    private static String scope(ProductQuery q){return hash(q.shopId()+"|"+q.marketplace()+"|"+q.text()+"|"+q.categoryIds()+"|"+q.documentState()+"|"+q.missingGtin()+"|"+q.issue());}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static IllegalStateException storeError(){return new IllegalStateException("Unable to read or save product data");}
}
