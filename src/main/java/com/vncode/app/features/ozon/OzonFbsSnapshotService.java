package com.vncode.app.features.ozon;
import com.vncode.app.config.Database;
import com.vncode.app.integration.ozon.*;
import java.sql.*;
import java.util.*;
/** Count, rows, item batches and catalog enrichment share a consistent read snapshot. */
public final class OzonFbsSnapshotService {
    public OzonFbsPage load(OzonFbsQuery q){try(var c=Database.getConnection()){
        c.setAutoCommit(false);try(var p=c.prepareStatement("SELECT 1 FROM shops WHERE id=? AND marketplace='OZON'")){p.setInt(1,q.shopId());try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("Foreign Ozon shop");}}
        var repo=new OzonPostingRepository();long total=repo.countFiltered(c,q);var rows=repo.findSortedPage(c,q);var skus=rows.stream().flatMap(p->p.items().stream()).map(OzonPostingItemDto::sku).filter(s->!s.isBlank()).distinct().toList();var products=new OzonCatalogRepository().findBySkus(c,q.shopId(),skus);var gtins=verifiedGtins(c,q.shopId(),products);var marks=new com.vncode.app.features.kiz.status.Kn3Repository().read(c,q.shopId(),com.vncode.app.integration.marketplace.Marketplace.OZON,rows.stream().map(OzonPostingDto::postingNumber).toList());c.commit();return new OzonFbsPage(rows,total,products,gtins,marks);
    }catch(SQLException e){throw new IllegalStateException("Cannot load Ozon posting snapshot");}}
    private static Map<String,String> verifiedGtins(Connection c,int shop,Map<String,OzonProductDto> products)throws SQLException{
        var keys=new ArrayList<>(products.keySet());var out=new HashMap<String,String>();for(int start=0;start<keys.size();start+=500){var batch=keys.subList(start,Math.min(start+500,keys.size()));String marks=String.join(",",Collections.nCopies(batch.size(),"?"));
            try(var p=c.prepareStatement("""
              SELECT p.sku,m.gtin FROM ozon_products p JOIN gtin_sync_mappings m ON m.shop_id=p.shop_id AND m.marketplace='OZON' AND m.product_id=p.product_id AND m.variant_id=p.offer_id JOIN gtin_sync_catalog g ON g.shop_id=m.shop_id AND g.marketplace=m.marketplace AND g.gtin=m.gtin
              WHERE p.shop_id=? AND json_valid(g.data_json) AND json_extract(g.data_json,'$.published')=1 AND json_extract(g.data_json,'$.tradeUnit')=1 AND p.sku IN (
              """+marks+")")){p.setInt(1,shop);int n=2;for(String key:batch)p.setString(n++,key);try(var r=p.executeQuery()){while(r.next()){String sku=r.getString(1),gtin=r.getString(2);if(com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(gtin)&&products.get(sku).barcodes().stream().anyMatch(b->pad(b).equals(pad(gtin))))out.put(sku,gtin);}}}
        }return Map.copyOf(out);
    }
    private static String pad(String v){return v!=null&&v.matches("[0-9]{8,14}")?"0".repeat(14-v.length())+v:Objects.toString(v,"");}
}
