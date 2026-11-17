package com.vncode.app.integration.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.kiz.KizSourceSelection;
import com.vncode.app.features.kiz.KizPurchaseMappingProof;
import com.vncode.app.features.kiz.status.Kn3Mark;
import com.vncode.app.features.kiz.status.Kn3Repository;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.sql.*;
import java.util.*;

/** Paid allocation and authoritative acceptance are distinct from a local PDF receipt. */
final class OzonPurchasedKizAssignment {
    private static final String PROOF_ACTION="accept_kiz_paid_print";
    private OzonPurchasedKizAssignment(){}

    private record Code(long id,int itemIndex,String product,String gtin,String raw,String status,
                        String legal,String token,boolean imported,boolean downloaded,boolean ownedOrder){
        @Override public String toString(){return "OzonPaidCode["+id+",redacted]";}
    }
    private static List<Code> codes(Shop shop,OzonExemplarJob job)throws SQLException,IOException{
        if(job.shopId()!=shop.getId())throw new IOException("OZON_PURCHASED_KIZ_OWNER_CHANGED");
        var rows=new ArrayList<Code>();
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            SELECT k.id,e.item_index,e.product_id,k.gtin,k.raw_code,k.status,k.legal_status,k.reservation_token,
              (EXISTS(SELECT 1 FROM kiz_import_entries i WHERE i.code_id=k.id)
                 OR EXISTS(SELECT 1 FROM kiz_orders imported_order WHERE imported_order.id=k.order_id
                    AND imported_order.shop_id=k.shop_id AND imported_order.local_status='IMPORTED')),
              (COALESCE(k.block_id,'')<>'' OR EXISTS(SELECT 1 FROM znack_purchase_pipelines pipe
                 WHERE pipe.shop_id=k.shop_id AND pipe.order_id=k.order_id)
                 OR EXISTS(SELECT 1 FROM kiz_orders paid WHERE paid.id=k.order_id AND paid.shop_id=k.shop_id
                    AND paid.local_status<>'IMPORTED' AND COALESCE(paid.external_order_id,'')<>'')),
              EXISTS(SELECT 1 FROM kiz_orders ord WHERE ord.id=k.order_id AND ord.shop_id=k.shop_id),
              k.shop_id,e.shop_id,e.posting_number
            FROM ozon_exemplars e JOIN kiz_codes k ON k.id=e.kiz_id WHERE e.job_id=?
            ORDER BY e.item_index,e.exemplar_index
            """)){
            p.setLong(1,job.id());try(var r=p.executeQuery()){while(r.next()){
                if(r.getInt(12)!=shop.getId()||r.getInt(13)!=shop.getId()||!job.postingNumber().equals(r.getString(14)))
                    throw new IOException("OZON_PURCHASED_KIZ_OWNER_CHANGED");
                rows.add(new Code(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),
                        r.getString(7),r.getString(8),r.getBoolean(9),r.getBoolean(10),r.getBoolean(11)));
            }}
        }
        return List.copyOf(rows);
    }
    private static boolean paid(Code code,KizSourceSelection sources){
        return !code.imported()&&(code.downloaded()||sources!=null&&sources.purchased()&&!sources.sellerImported());
    }
    static boolean required(Shop shop,OzonExemplarJob job,KizSourceSelection sources)throws IOException{
        if(job==null)return false;ListingIdentity.require(shop);
        try{return codes(shop,job).stream().anyMatch(code->paid(code,sources));}
        catch(SQLException failure){throw new IOException("OZON_PURCHASED_KIZ_STORAGE",failure);}
    }

    record RejectedDemand(Map<String,Integer> quantities,List<KizPurchaseMappingProof.Product> candidates,String fingerprint,Map<String,Integer> downloadedQuantities){
        RejectedDemand{quantities=Map.copyOf(quantities);candidates=List.copyOf(candidates);downloadedQuantities=Map.copyOf(downloadedQuantities);}
        RejectedDemand(Map<String,Integer> quantities,List<KizPurchaseMappingProof.Product> candidates,String fingerprint){this(quantities,candidates,fingerprint,Map.of());}
    }
    /** Only definitive rejected, still-bound paid slots can request replacement stock.
     * Accepted siblings, uncertain requests and historical printed jobs do not add a charge. */
    static RejectedDemand rejectedDemand(Shop shop,Collection<OzonPostingDto> postings,KizSourceSelection sources)throws IOException{
        ListingIdentity.require(shop);
        var quantities=new TreeMap<String,Integer>();var downloaded=new TreeMap<String,Integer>();var candidates=new ArrayList<KizPurchaseMappingProof.Product>();
        var signature=new StringBuilder(ListingIdentity.fingerprint(shop));
        if(sources!=null&&!sources.purchased())return new RejectedDemand(quantities,candidates,ListingIdentity.hash(signature.toString()));
        var jobs=new OzonExemplarJobRepository();var replacements=new OzonAcceptanceKizReplacement();
        try(var c=Database.getConnection()){
            for(var posting:postings.stream().sorted(Comparator.comparing(OzonPostingDto::postingNumber)).toList()){
                if(!Set.of("awaiting_packaging","awaiting_deliver").contains(posting.status()))continue;
                var job=jobs.find(shop.getId(),posting.postingNumber());
                if(job==null||job.stage()==OzonExemplarJobStage.ACCEPTED||replacements.active(shop,job)!=null
                        ||!"rejected".equals(jobs.latestActionStatus(shop.getId(),"accept_kiz",posting.postingNumber())))continue;
                var bindings=jobs.bindings(job.id());var observed=new Kn3Repository().read(shop,posting.postingNumber());
                var rejected=codes(shop,job).stream().filter(code->paid(code,sources)&&observed.stream().anyMatch(mark->
                        mark.state()==Kn3Mark.State.REJECTED&&posting.postingNumber().equals(mark.orderId())
                                &&bindings.stream().anyMatch(binding->binding.kizId()==code.id()&&OzonAcceptanceKizReplacement.matches(binding,mark)))).toList();
                if(rejected.isEmpty())continue;
                requireCurrentPaidBindings(shop,posting,job,sources);
                var products=OzonCategoryStock.verifiedProducts(c,shop.getId(),posting.items());
                for(var code:rejected){
                    if(!"CONSUMED".equals(code.status())||!"REJECTED".equals(code.legal()))throw new IOException("OZON_ACCEPT_KIZ_PENDING");
                    var item=posting.items().stream().filter(i->i.itemIndex()==code.itemIndex()).findFirst().orElseThrow();
                    quantities.merge(code.gtin(),1,Math::addExact);
                    if(code.downloaded())downloaded.merge(code.gtin(),1,Math::addExact);
                    candidates.add(new KizPurchaseMappingProof.Product(products.get(item.sku()),item.sku(),code.gtin(),posting.postingNumber()));
                    signature.append('\n').append(job.id()).append(':').append(job.stage()).append(':').append(code.id())
                            .append(':').append(item.productId()).append(':').append(item.sku()).append(':').append(item.offerId())
                            .append(':').append(code.gtin()).append(':').append(code.downloaded()).append(':').append(Kn3Mark.fingerprint(code.raw()));
                }
            }
            return new RejectedDemand(quantities,candidates,ListingIdentity.hash(signature.toString()),downloaded);
        }catch(SQLException failure){throw new IOException("OZON_PURCHASED_KIZ_STORAGE",failure);}
    }
    static void requireUnchangedRejectedDemand(Shop shop,Collection<OzonPostingDto> postings,KizSourceSelection sources,RejectedDemand expected){
        try{
            if(!expected.equals(rejectedDemand(shop,postings,sources)))throw new IllegalStateException("OZON_PURCHASED_KIZ_DEMAND_CHANGED");
        }catch(IOException failure){throw new IllegalStateException("OZON_PURCHASED_KIZ_DEMAND_CHANGED",failure);}
    }

    /** New paid assignments must retain the exact native SKU/offer and Catalog GTIN. */
    static void requireCurrentPaidBindings(Shop shop,OzonPostingDto posting,OzonExemplarJob job,KizSourceSelection sources)throws IOException{
        ListingIdentity.require(shop);
        if(!posting.postingNumber().equals(job.postingNumber()))throw new IOException("OZON_PURCHASED_KIZ_IDENTITY_CHANGED");
        try(var c=Database.getConnection()){
            var paidCodes=codes(shop,job).stream().filter(code->paid(code,sources)).toList();
            if(paidCodes.isEmpty())return;
            var nativeIds=OzonCategoryStock.verifiedProducts(c,shop.getId(),posting.items());
            var mappings=new OzonProductGtinMappingRepository().findResolvedBySku(shop.getId());
            var observed=new Kn3Repository().read(shop,posting.postingNumber());
            var bindings=new OzonExemplarJobRepository().bindings(job.id());
            for(var code:paidCodes){
                var item=posting.items().stream().filter(i->i.itemIndex()==code.itemIndex()).findFirst().orElse(null);
                boolean current=item!=null&&item.productId().equals(code.product())&&nativeIds.containsKey(item.sku())
                        &&Objects.equals(code.gtin(),mappings.get(item.sku()))&&Objects.equals(code.gtin(),rawGtin(code.raw()));
                boolean held="RESERVED".equals(code.status())&&("ozon:"+job.id()).equals(code.token())&&"IN_CIRCULATION".equals(code.legal());
                boolean rejected="CONSUMED".equals(code.status())&&"REJECTED".equals(code.legal())&&observed.stream().anyMatch(mark->
                        mark.state()==Kn3Mark.State.REJECTED&&bindings.stream().anyMatch(binding->binding.kizId()==code.id()&&OzonAcceptanceKizReplacement.matches(binding,mark)));
                if(!current||!code.ownedOrder()||!(held||rejected))throw new IOException("OZON_PURCHASED_KIZ_IDENTITY_CHANGED");
            }
        }catch(SQLException failure){throw new IOException("OZON_PURCHASED_KIZ_STORAGE",failure);}
    }

    /** Extract AI01 without changing or truncating the payload sent to Ozon. */
    static String rawGtin(String raw){
        String value=Objects.toString(KizService.scannerSafeCode(raw),"");
        int from=value.startsWith("(01)")?4:value.startsWith("01")?2:-1;
        if(from<0||value.length()<from+14)return "";
        String gtin=value.substring(from,from+14);return gtin.matches("[0-9]{14}")?gtin:"";
    }

    private static boolean exactAccepted(Shop shop,OzonExemplarJob job,List<OzonExemplarJobRepository.KizBinding> bindings){
        if(bindings.isEmpty()||bindings.stream().anyMatch(b->b.exemplarId()==null||b.exemplarId().isBlank()))return false;
        var marks=new Kn3Repository().read(shop,job.postingNumber());
        return marks.size()==bindings.size()&&bindings.stream().allMatch(binding->marks.stream().anyMatch(mark->
                job.postingNumber().equals(mark.orderId())&&mark.state()==Kn3Mark.State.ACCEPTED&&OzonAcceptanceKizReplacement.matches(binding,mark)));
    }
    private static String fingerprint(Shop shop,OzonExemplarJob job,List<OzonExemplarJobRepository.KizBinding> bindings){
        return ListingIdentity.hash(ListingIdentity.fingerprint(shop)+"\n"+job.id()+"\n"
                +OzonExemplarService.exemplarPayload(job.postingNumber(),bindings,true));
    }
    /** A terminal local-print state or a successful POST is not this receipt. */
    static boolean acceptedProof(Shop shop,OzonExemplarJob job)throws IOException{
        ListingIdentity.require(shop);
        if(job==null)return false;
        var bindings=new OzonExemplarJobRepository().bindings(job.id());
        if(!exactAccepted(shop,job,bindings))return false;
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            SELECT action_type,status,request_fingerprint FROM ozon_action_log
            WHERE shop_id=? AND posting_number=? AND action_type IN ('accept_kiz','accept_kiz_paid_print')
            ORDER BY id DESC LIMIT 1
            """)){
            p.setInt(1,shop.getId());p.setString(2,job.postingNumber());try(var r=p.executeQuery()){
                if(!r.next()||!"reconciled".equals(r.getString(2)))return false;
                String expected=PROOF_ACTION.equals(r.getString(1))?fingerprint(shop,job,bindings):job.requestFingerprint();
                return expected!=null&&!expected.isBlank()&&expected.equals(r.getString(3));
            }
        }catch(SQLException failure){throw new IOException("OZON_PURCHASED_KIZ_STORAGE",failure);}
    }
    /** Called only after the strict exemplar/status protocol has completed successfully. */
    static void recordAcceptedReadback(Shop shop,OzonExemplarJob job)throws IOException{
        ListingIdentity.require(shop);var jobs=new OzonExemplarJobRepository();var bindings=jobs.bindings(job.id());
        if(!exactAccepted(shop,job,bindings))throw new IOException("OZON_ACCEPT_KIZ_PENDING");
        jobs.logAction(shop.getId(),PROOF_ACTION,job.postingNumber(),"reconciled",null,fingerprint(shop,job,bindings));
    }
}
