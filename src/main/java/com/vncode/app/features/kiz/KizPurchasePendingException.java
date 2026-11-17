package com.vncode.app.features.kiz;

import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.I18nService;
import java.text.MessageFormat;
import java.util.*;

/** A stock wait, not a failed print or proof that a paid request was accepted remotely. */
public final class KizPurchasePendingException extends IllegalStateException {
    public record Deficit(String gtin,int required,int available,int missing){}
    private final List<Deficit> deficits;
    private final String reason;
    private final KizPurchaseAvailability availability;
    public KizPurchasePendingException(Map<String,Integer> required,KizStockReplenisher.Result result){
        this(required,result,null);
    }
    public KizPurchasePendingException(Map<String,Integer> required,KizStockReplenisher.Result result,KizPurchaseAvailability availability){
        super("KIZ_PURCHASE_PENDING");
        this.availability=availability;
        reason=Set.of("queued","existing","review").contains(result.reason())?result.reason():"review";
        var normalized=new TreeMap<String,Integer>();required.forEach((g,n)->normalized.merge(GtinNormalizer.normalize(g),n,Math::addExact));
        var rows=new ArrayList<Deficit>();
        result.missing().forEach((gtin,n)->{int count=normalized.getOrDefault(gtin,0);rows.add(new Deficit(gtin,count,Math.max(0,count-n),n));});
        rows.sort(Comparator.comparing(Deficit::gtin));deficits=List.copyOf(rows);
    }
    public List<Deficit> deficits(){return deficits;}
    public String reason(){return reason;}
    public Map<String,Integer> availableCounts(Shop shop,Collection<String> gtins){
        return availability==null?new ZnackGtinInventoryService().purchasedAvailableCounts(shop.getId(),gtins):availability.counts(shop,gtins);
    }
    public String display(){
        var i18n=I18nService.getInstance();
        String rows=deficits.stream().map(d->MessageFormat.format(i18n.tr("kiz.pending.deficit"),d.gtin(),d.required(),d.available(),d.missing()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return i18n.tr("kiz.pending.hint")+"\n"+rows+"\n"+i18n.tr("kiz.pending.reason."+reason);
    }
    public static boolean isPending(Throwable failure){
        for(int depth=0;failure!=null&&depth<8;depth++,failure=failure.getCause())
            if("KIZ_PURCHASE_PENDING".equals(failure.getMessage()))return true;
        return false;
    }
}
