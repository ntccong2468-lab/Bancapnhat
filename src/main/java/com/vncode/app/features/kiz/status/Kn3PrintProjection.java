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
            String finalCode=code;if(observed.stream().anyMatch(m->m.productId().equals(slot.product())&&m.state()==Kn3Mark.State.REJECTED))throw new IOException("FBS_KN3_REJECTED_REPLACE_REQUIRED");
            if(changes.stream().anyMatch(v->v.product().equals(slot.product()))&&!observed.stream().anyMatch(m->m.productId().equals(slot.product())&&(slot.exemplar()==null||slot.exemplar().isBlank()||slot.exemplar().equals(m.exemplarId()))&&m.fingerprint().equals(Kn3Mark.fingerprint(finalCode))&&m.state()==Kn3Mark.State.ACCEPTED))throw new IOException("FBS_KN3_PRINT_MAPPING_UNCLEAR");
            result.add(code);
        }return List.copyOf(result);
    }
}
