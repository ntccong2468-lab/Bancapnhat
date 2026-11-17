package com.vncode.app.integration.ozon;

import com.vncode.app.features.kiz.KizPurchaseAvailability;
import com.vncode.app.features.kiz.KizWarehouseRepository;
import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.util.*;

/** Availability and reservation use the same Ozon paid-pool evidence. */
final class OzonPurchasedKizStock implements KizPurchaseAvailability {
    private final Map<String,Integer> required;
    private final Map<String,Integer> strictRequired;
    private final String owner;
    static boolean purchaseIntent(Shop shop,com.vncode.app.features.kiz.KizSourceSelection sources){
        return sources!=null&&sources.purchased();
    }
    private OzonPurchasedKizStock(Shop shop,Map<String,Integer> required,Map<String,Integer> strictRequired){
        this.required=Map.copyOf(required);this.strictRequired=Map.copyOf(strictRequired);this.owner=ListingIdentity.fingerprint(shop);
    }
    static KizPurchaseAvailability forDemand(Shop shop,Map<String,Integer> required,OzonPurchasedKizAssignment.RejectedDemand rejected){
        var strict=new TreeMap<String,Integer>();
        required.forEach((gtin,quantity)->{
            int legacy=rejected.quantities().getOrDefault(gtin,0)-rejected.downloadedQuantities().getOrDefault(gtin,0);
            if(legacy<0||legacy>quantity)throw new IllegalArgumentException("OZON_PURCHASED_KIZ_DEMAND_CHANGED");
            strict.put(gtin,quantity-legacy);
        });
        return new OzonPurchasedKizStock(shop,required,strict);
    }
    @Override public Map<String,Integer> counts(Shop shop,Collection<String> gtins){
        ListingIdentity.require(shop);
        if(!owner.equals(ListingIdentity.fingerprint(shop)))throw new SecurityException("KIZ_OWNER_CHANGED");
        var result=new LinkedHashMap<String,Integer>();
        try(var c=Database.getConnection()){
            for(String source:gtins){
                String gtin=GtinNormalizer.normalize(source);Integer target=required.get(gtin);
                if(target==null)throw new IllegalArgumentException("OZON_PURCHASED_KIZ_DEMAND_CHANGED");
                int[] available=new int[2];
                visit(c,shop.getId(),gtin,owner,(id,downloaded)->{available[0]++;if(downloaded)available[1]++;return true;});
                // Maximum usable rows for this frozen plan: actual downloaded
                // slots cannot borrow unknown legacy stock. Legacy held slots
                // retain their explicit paid-source intent on replacement.
                int strictMissing=Math.max(0,strictRequired.getOrDefault(gtin,0)-available[1]);
                result.put(gtin,Math.min(available[0],Math.max(0,target-strictMissing)));
            }
            return Map.copyOf(result);
        }catch(SQLException failure){throw new IllegalStateException("OZON_PURCHASED_KIZ_STORAGE",failure);}
    }
    static List<Long> select(Connection c,int shopId,String gtin,int quantity,String owner,boolean requireDownloaded)throws SQLException{
        if(quantity<=0)return List.of();
        var result=new ArrayList<Long>();
        visit(c,shopId,gtin,owner,(id,downloaded)->{
            if(!requireDownloaded||downloaded)result.add(id);
            return result.size()<quantity;
        });
        return List.copyOf(result);
    }
    static String downloadedSql(String alias){
        return "EXISTS(SELECT 1 FROM kiz_orders paid WHERE paid.id="+alias+".order_id AND paid.shop_id="+alias+".shop_id"
                +" AND paid.local_status<>'IMPORTED' AND (COALESCE("+alias+".block_id,'')<>'' OR COALESCE(paid.external_order_id,'')<>''"
                +" OR EXISTS(SELECT 1 FROM znack_purchase_pipelines pipe WHERE pipe.shop_id="+alias+".shop_id AND pipe.order_id="+alias+".order_id)))";
    }
    @FunctionalInterface private interface Visitor{boolean row(long id,boolean downloaded);}
    private static void visit(Connection c,int shopId,String gtin,String owner,Visitor visitor)throws SQLException{
        try(var p=c.prepareStatement("""
            SELECT k.id,k.raw_code,%s FROM kiz_codes k
            WHERE k.shop_id=? AND k.gtin=? AND k.status='AVAILABLE' AND k.legal_status='IN_CIRCULATION'
              AND NOT EXISTS(SELECT 1 FROM kiz_import_entries imported WHERE imported.code_id=k.id)
              AND EXISTS(SELECT 1 FROM kiz_orders owned WHERE owned.id=k.order_id AND owned.shop_id=k.shop_id AND owned.local_status<>'IMPORTED')
              AND %s ORDER BY k.source_priority,k.id
            """.formatted(downloadedSql("k"),KizWarehouseRepository.eligibleExact("k")))){
            p.setInt(1,shopId);p.setString(2,gtin);p.setString(3,owner);
            try(var r=p.executeQuery()){while(r.next()){
                if(!gtin.equals(OzonPurchasedKizAssignment.rawGtin(r.getString(2))))continue;
                if(!visitor.row(r.getLong(1),r.getBoolean(3)))break;
            }}
        }
    }
}
