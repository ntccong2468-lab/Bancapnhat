package com.vncode.app.integration.wb;
import com.google.gson.Gson;
import com.vncode.app.shared.ConfigService;
public final class WbShippingPreferenceService {
    public record Preferences(String country,String shippingType,String city,Long pointId) { }
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
