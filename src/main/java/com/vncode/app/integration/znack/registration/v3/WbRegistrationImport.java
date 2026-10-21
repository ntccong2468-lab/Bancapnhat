package com.vncode.app.integration.znack.registration.v3;

import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.WbCharacteristic;
import com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import java.util.*;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;

/** Complete, cursor-checked WB read. Import never allocates a GTIN or submits a card. */
public final class WbRegistrationImport {
    private WbRegistrationImport() { }
    @FunctionalInterface public interface Pages { WbProductCardsResponse read(String updatedAt,Long nmId) throws Exception; }
    public static List<Product> read(Pages pages) throws Exception {
        String updated=null;Long nm=null;var cards=new HashSet<Long>();var variants=new HashSet<Long>();var cursors=new HashSet<String>();
        var result=new ArrayList<Product>();
        for(int page=0;page<20_000;page++) {
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("V3_IMPORT_INTERRUPTED");
            var response=pages.read(updated,nm);
            if(response==null||response.getCards()==null||response.getCursor()==null||response.getCursor().getTotal()==null
                    ||response.getCursor().getTotal()!=response.getCards().size()||response.getCards().size()>100)
                throw new IllegalStateException("V3_WB_PAGE_INVALID");
            for(var card:response.getCards()) {
                if(card==null||card.getNmID()==null||card.getNmID()<=0||!cards.add(card.getNmID())||card.getSizes()==null||card.getSizes().isEmpty())
                    throw new IllegalStateException("V3_WB_CARD_INVALID");
                var characteristics=new ArrayList<WbCharacteristic>();
                if(card.getCharacteristics()!=null)for(var a:card.getCharacteristics()) {
                    if(a==null||a.getId()==null)throw new IllegalStateException("V3_WB_CHARACTERISTIC_INVALID");
                    var values=a.getValue() instanceof List<?> list?list:List.of(Objects.toString(a.getValue(),""));
                    characteristics.add(new WbCharacteristic(a.getId(),a.getName(),values.stream().map(v->Objects.toString(v,"")).toList()));
                }
                String image=card.getPhotos()==null||card.getPhotos().isEmpty()?"":Objects.toString(card.getPhotos().getFirst().getBig(),"");
                for(var size:card.getSizes()) {
                    if(size==null||size.getChrtID()==null||size.getChrtID()<=0||!variants.add(size.getChrtID()))throw new IllegalStateException("V3_WB_VARIANT_INVALID");
                    var snapshot=WbGtinProductReader.fromCard(new ProductKey(1,Marketplace.WILDBERRIES,card.getNmID().toString(),size.getChrtID().toString()),card);
                    result.add(new Product(card.getNmID(),size.getChrtID(),card.getSubjectID()==null?0:card.getSubjectID(),
                            card.getVendorCode(),card.getTitle(),card.getBrand(),snapshot.color(),size.getTechSize(),size.getWbSize(),
                            snapshot.barcodes(),image,characteristics,card.getSubjectName()));
                }
            }
            if(response.getCards().size()<100)return List.copyOf(result);
            var cursor=response.getCursor();updated=cursor.getUpdatedAt();nm=cursor.getNmID();
            if(blank(updated)||nm==null||nm<=0||!cursors.add(updated+":"+nm))throw new IllegalStateException("V3_WB_CURSOR_STALLED");
        }
        throw new IllegalStateException("V3_WB_PAGE_LIMIT");
    }
}
