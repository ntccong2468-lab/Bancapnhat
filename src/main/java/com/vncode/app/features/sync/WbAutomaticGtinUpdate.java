package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.SQLException;

/** Owner/source-bound automatic append policy; exact variant/publication/readback gates still apply. */
public final class WbAutomaticGtinUpdate {
    private static final String PREFIX="wb_automatic_gtin_sources.v1:";
    private WbAutomaticGtinUpdate() {}

    public static void enableForCurrentSources(Shop shop) {
        if(shop.getMarketplace()!=Marketplace.WILDBERRIES)return;
        var ready=new RegistrationSources().check(shop);if(!ready.ready())return;
        String value=proof(shop,ready.run());
        try{SqliteWriteTransaction.run(c->{
            ListingIdentity.require(shop);
            if(!ready.equals(new RegistrationSources().require(shop)))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
            try(var p=c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value WHERE value<>excluded.value")){
                p.setString(1,PREFIX+shop.getId());p.setString(2,value);
                if(p.executeUpdate()>0)SyncStreamStore.request(c,shop.getId(),"ZNACK");
            }
            return null;
        });}catch(SQLException error){throw new IllegalStateException("SYNC_STORAGE",error);}
    }

    public static boolean enabled(Shop shop) {
        if(shop.getMarketplace()!=Marketplace.WILDBERRIES)return false;
        var ready=new RegistrationSources().check(shop);if(!ready.ready())return false;
        String expected=proof(shop,ready.run());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")){
            p.setString(1,PREFIX+shop.getId());try(var r=p.executeQuery()){return r.next()&&expected.equals(r.getString(1));}
        }catch(SQLException error){throw new IllegalStateException("SYNC_STORAGE",error);}
    }

    private static String proof(Shop shop,String run) {
        ListingIdentity.require(shop);
        return ListingIdentity.hash(ListingIdentity.fingerprint(shop)+"|"+RegisteredGtinProof.source(shop)+"|"+run);
    }
}
