package com.vncode.app.integration.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.sql.*;
import java.util.*;

/** Ozon posting product_id can be a SKU. Only an unambiguous cached SKU resolves the native product. */
final class OzonCategoryStock {
    static String key(String posting,int index){return posting+":"+index;}
    static Map<String,String> nativeProducts(Connection c,int shop,Collection<String> skus)throws SQLException{
        var ids=skus.stream().filter(Objects::nonNull).filter(s->!s.isBlank()).distinct().toList();
        var candidates=new HashMap<String,Set<String>>();
        for(int from=0;from<ids.size();from+=500){var batch=ids.subList(from,Math.min(from+500,ids.size()));
            try(var p=c.prepareStatement("SELECT sku,product_id FROM ozon_products WHERE shop_id=? AND archived=0 AND sku IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")")){
                p.setInt(1,shop);for(int n=0;n<batch.size();n++)p.setString(n+2,batch.get(n));
                try(var r=p.executeQuery()){while(r.next())candidates.computeIfAbsent(r.getString(1),k->new HashSet<>()).add(r.getString(2));}
            }
        }
        var result=new HashMap<String,String>();candidates.forEach((sku,products)->{if(products.size()==1)result.put(sku,products.iterator().next());});return Map.copyOf(result);
    }
    static Map<String,String> verifiedProducts(Connection c,int shop,List<OzonPostingItemDto> items)throws SQLException{
        var catalog=new OzonCatalogRepository().findBySkus(c,shop,items.stream().map(OzonPostingItemDto::sku).toList());
        var result=new HashMap<String,String>();
        for(var item:items){var product=catalog.get(item.sku());if(product!=null&&(item.offerId().isBlank()||item.offerId().equals(product.offerId())))result.put(item.sku(),product.productId());}
        for(var item:items){var product=catalog.get(item.sku());if(product==null||!item.offerId().isBlank()&&!item.offerId().equals(product.offerId()))result.remove(item.sku());}
        return Map.copyOf(result);
    }
    static Set<String> categorySkus(int shop,Collection<String> skus){
        try(var c=Database.getConnection()){
            var nativeIds=nativeProducts(c,shop,skus);String owner=KizWarehouseRepository.currentOwner(c,shop);
            var categories=KizStockCategoryRepository.bindings(c,shop,Marketplace.OZON,nativeIds.values(),owner);
            var result=new HashSet<String>();nativeIds.forEach((sku,id)->{if(categories.containsKey(id))result.add(sku);});return Set.copyOf(result);
        }catch(SQLException e){throw new IllegalStateException("KIZ_CATEGORY_STORAGE",e);}
    }
    /** One read transaction and one FIFO simulation across the complete batch; this never reserves marks. */
    static Map<String,Integer> coverage(int shop,List<OzonPostingDto> postings,Set<String> exempt){
        if(postings.isEmpty())return Map.of();
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);var products=verifiedProducts(c,shop,postings.stream().flatMap(p->p.items().stream()).toList());
            var demands=new ArrayList<KizCategoryAllocator.Demand>();
            for(var posting:postings){
                if(posting.requirements().blocksPreparation()||!posting.isSinglePackageSupported())continue;
                var mandatory=Set.copyOf(posting.requirements().mandatoryMarkProductIds());
                for(var item:posting.items())if(products.containsKey(item.sku())&&(mandatory.contains(item.productId())||!exempt.contains(item.sku())))
                    demands.add(new KizCategoryAllocator.Demand(key(posting.postingNumber(),item.itemIndex()),products.get(item.sku()),item.quantity()));
            }
            var result=new HashMap<String,Integer>();
            for(var selection:KizCategoryAllocator.select(c,shop,Marketplace.OZON,KizWarehouseRepository.currentOwner(c,shop),demands))result.merge(selection.key(),1,Integer::sum);
            c.commit();return Map.copyOf(result);
        }catch(SQLException e){throw new IllegalStateException("KIZ_CATEGORY_STORAGE",e);}
    }
}
