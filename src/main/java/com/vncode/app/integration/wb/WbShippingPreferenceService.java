package com.vncode.app.integration.wb;
import com.google.gson.Gson;
import com.vncode.app.shared.ConfigService;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.util.*;
public final class WbShippingPreferenceService {
    public record Preferences(String country,String shippingType,String city,Long pointId) { }
    /** Only locations actually returned by WB or used successfully by this account. */
    public record Location(long pointId,String shippingType,String city,String label) {
        public boolean valid(){return pointId>0&&Set.of("selfShipping","transportCompany").contains(Objects.toString(shippingType,""));}
        @Override public String toString(){return label==null||label.isBlank()?"Điểm giao #"+pointId:label;}
    }
    public List<Location> history(Shop shop) {
        ListingIdentity.require(shop);
        try {
            var rows=new Gson().fromJson(ConfigService.getConfigValue(historyKey(shop)),Location[].class);
            if(rows==null)return List.of();
            return Arrays.stream(rows).filter(Objects::nonNull).filter(Location::valid).limit(100).toList();
        }catch(RuntimeException invalid){return List.of();}
    }
    public void remember(Shop shop,Location location) {
        if(location==null||!location.valid())throw new IllegalArgumentException("Invalid historical shipping point.");
        var rows=new ArrayList<Location>();rows.add(location);
        for(var previous:history(shop))if(previous.pointId()!=location.pointId()&&rows.size()<100)rows.add(previous);
        ListingIdentity.require(shop);
        ConfigService.setConfigValue(historyKey(shop),new Gson().toJson(rows));
    }
    private static String historyKey(Shop shop){return "wb.shipping.history."+shop.getId()+"."+ListingIdentity.fingerprint(shop);}
    public Preferences load(int shopId) {
        if(shopId<1)throw new IllegalArgumentException("Invalid shop.");
        try {var p=new Gson().fromJson(ConfigService.getConfigValue("wb.shipping.preferences."+shopId),Preferences.class);
            return p==null?new Preferences(null,null,null,null):p;
        } catch(RuntimeException error){return new Preferences(null,null,null,null);}
    }
    public void save(int shopId,Preferences p) {
        if(shopId<1||p==null||!("RU".equals(p.country())||"OTHER".equals(p.country())))throw new IllegalArgumentException("Invalid shop shipping preferences.");
        ConfigService.setConfigValue("wb.shipping.preferences."+shopId,new Gson().toJson(p));
    }
}
