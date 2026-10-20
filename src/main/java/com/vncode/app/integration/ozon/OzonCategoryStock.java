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
    /** Frozen allocations remain printable after the seller hides their category.
     * Proof is limited to this posting, item, native product and current shop owner. */
    static Set<String> reservedCategorySkus(int shop,OzonPostingDto posting,OzonExemplarJob job){
        if(job==null||job.shopId()!=shop||!job.postingNumber().equals(posting.postingNumber())
                ||!Set.of(OzonExemplarJobStage.RESERVED,OzonExemplarJobStage.VALIDATED,OzonExemplarJobStage.ACCEPTED).contains(job.stage()))return Set.of();
        try(var c=Database.getConnection()){
            String owner=KizWarehouseRepository.currentOwner(c,shop),token="ozon:"+job.id();
            var nativeIds=verifiedProducts(c,shop,posting.items());
            var items=new HashMap<Integer,OzonPostingItemDto>();posting.items().forEach(i->items.put(i.itemIndex(),i));
            var covered=new HashMap<Integer,Set<Integer>>();
            try(var p=c.prepareStatement("""
                SELECT e.item_index,e.product_id,e.exemplar_index,k.status,k.reservation_token,proof.product_id
                FROM ozon_exemplars e JOIN ozon_exemplar_jobs job ON job.id=e.job_id
                JOIN kiz_codes k ON k.id=e.kiz_id AND k.shop_id=job.shop_id
                JOIN kiz_stock_category_reservations proof ON proof.code_id=k.id AND proof.token=?
                  AND proof.shop_id=job.shop_id AND proof.allocation_key=job.posting_number||':'||e.item_index
                JOIN kiz_stock_categories category ON category.id=proof.category_id
                  AND category.shop_id=proof.shop_id AND category.owner=proof.owner
                WHERE job.id=? AND job.shop_id=? AND job.posting_number=? AND e.shop_id=job.shop_id
                  AND e.posting_number=job.posting_number AND proof.owner=? AND %s
                """.formatted(KizWarehouseRepository.eligibleCategory("k")))){
                p.setString(1,token);p.setLong(2,job.id());p.setInt(3,shop);p.setString(4,posting.postingNumber());p.setString(5,owner);p.setString(6,owner);
                try(var r=p.executeQuery()){while(r.next()){
                    var item=items.get(r.getInt(1));int index=r.getInt(3);
                    boolean held="RESERVED".equals(r.getString(4))&&token.equals(r.getString(5));
                    boolean printed=job.stage()==OzonExemplarJobStage.ACCEPTED&&"CONSUMED".equals(r.getString(4));
                    if(item!=null&&item.productId().equals(r.getString(2))&&index>=0&&index<item.quantity()
                            &&r.getString(6).equals(nativeIds.get(item.sku()))&&(held||printed))
                        covered.computeIfAbsent(item.itemIndex(),i->new HashSet<>()).add(index);
                }}
            }
            var result=new HashSet<String>();for(var item:posting.items())
                if(covered.getOrDefault(item.itemIndex(),Set.of()).size()==item.quantity())result.add(item.sku());
            return Set.copyOf(result);
        }catch(SQLException error){throw new IllegalStateException("KIZ_CATEGORY_STORAGE",error);}
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
