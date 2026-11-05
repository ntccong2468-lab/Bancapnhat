package com.vncode.app.features.kiz.status;

import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;

/** Keep original stock/history immutable; regenerate labels with a replacement only after
 * exact marketplace readback. A local slot must map unambiguously to the replaced exemplar. */
public final class Kn3PrintProjection {
    private Kn3PrintProjection(){}
    public record Slot(String product,String exemplar,String code){@Override public String toString(){return "Kn3PrintSlot[redacted]";}}
    private record Change(String product,String exemplar,String oldHash,String code,String state){}
    public static List<String> codes(Shop shop,String order,List<Slot> slots)throws IOException {
        return codes(shop,order,slots,false);
    }
    /** The seller's Ozon print workflow retains the exact bound code after remote rejection.
     * Pending replacements still require reconciliation; this never assigns or submits a mark. */
    public static List<String> ozonReservedCodes(Shop shop,String order,List<Slot> slots)throws IOException {
        com.vncode.app.integration.marketplace.MarketplaceGuard.requireOzon(shop);
        return codes(shop,order,slots,true);
    }
    private static List<String> codes(Shop shop,String order,List<Slot> slots,boolean printRejectedReservation)throws IOException {
        ListingIdentity.require(shop);var changes=new ArrayList<Change>();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT product_id,exemplar_id,old_hash,new_code,state FROM kn3_replacements WHERE shop_id=? AND marketplace=? AND owner=? AND order_id=? ORDER BY rowid DESC")){
            p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());p.setString(3,ListingIdentity.fingerprint(shop));p.setString(4,order);try(var r=p.executeQuery()){while(r.next())changes.add(new Change(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)));}
        }catch(java.sql.SQLException e){throw new IOException("FBS_KN3_STORAGE",e);}
        var observed=new Kn3Repository().read(shop,order);var result=new ArrayList<String>();
        for(var slot:slots){String code=slot.code();var seen=new HashSet<String>();
            for(int depth=0;depth<=changes.size();depth++){
                String fingerprint=Kn3Mark.fingerprint(code);if(!seen.add(fingerprint))throw new IOException("FBS_KN3_PRINT_MAPPING_UNCLEAR");
                var change=changes.stream().filter(v->v.product().equals(slot.product())&&v.oldHash().equals(fingerprint)&&(slot.exemplar()==null||slot.exemplar().isBlank()||slot.exemplar().equals(v.exemplar()))).findFirst();if(change.isEmpty())break;
                if(slot.exemplar()==null||slot.exemplar().isBlank()){
                    long count=slots.stream().filter(v->v.product().equals(slot.product())&&Kn3Mark.fingerprint(v.code()).equals(fingerprint)).count();if(count>1)throw new IOException("FBS_KN3_PRINT_MAPPING_UNCLEAR");
                }
                if(change.get().state().equals("FAILED"))break;
                if(!change.get().state().equals("DONE"))throw new IOException("FBS_KN3_REPLACEMENT_PENDING");code=change.get().code();
            }
            String finalCode=code;
            boolean retainedRejected=printRejectedReservation&&Kn3Mark.fingerprint(finalCode).equals(Kn3Mark.fingerprint(slot.code()))
                    &&observed.stream().anyMatch(m->m.productId().equals(slot.product())&&Objects.equals(slot.exemplar(),m.exemplarId())
                        &&m.state()==Kn3Mark.State.REJECTED&&com.vncode.app.features.kiz.KizRejectedStock.identity(m.code())
                            .equals(com.vncode.app.features.kiz.KizRejectedStock.identity(finalCode)));
            if(!retainedRejected&&observed.stream().anyMatch(m->m.productId().equals(slot.product())
                    &&(!printRejectedReservation||sameExemplar(slot,m.exemplarId()))&&m.state()==Kn3Mark.State.REJECTED))throw new IOException("FBS_KN3_REJECTED_REPLACE_REQUIRED");
            if(!retainedRejected&&changes.stream().anyMatch(v->v.product().equals(slot.product())&&(!printRejectedReservation||sameExemplar(slot,v.exemplar())))
                    &&!observed.stream().anyMatch(m->m.productId().equals(slot.product())&&sameExemplar(slot,m.exemplarId())
                        &&m.fingerprint().equals(Kn3Mark.fingerprint(finalCode))&&m.state()==Kn3Mark.State.ACCEPTED))throw new IOException("FBS_KN3_PRINT_MAPPING_UNCLEAR");
            result.add(code);
        }return List.copyOf(result);
    }
    private static boolean sameExemplar(Slot slot,String exemplar){return slot.exemplar()==null||slot.exemplar().isBlank()||slot.exemplar().equals(exemplar);}
}
