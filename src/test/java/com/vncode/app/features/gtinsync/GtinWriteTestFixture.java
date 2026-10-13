package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.time.Instant;
import java.util.List;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

/** Synthetic identities only; nothing in this fixture is a seller credential or certificate. */
public final class GtinWriteTestFixture {
    public static final String GTIN="00000000000017";
    public static final String CERTIFICATE="A".repeat(40);
    private GtinWriteTestFixture() {}
    public static Shop shop(Marketplace market)throws Exception {
        int id=market==Marketplace.WILDBERRIES?1:2;
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,api_key,client_id,marketplace) VALUES(?, 'Fixture', 'fixture-token', ?,?)")){
            p.setInt(1,id);p.setString(2,market==Marketplace.OZON?"fixture-client":null);p.setString(3,market.name());p.executeUpdate();
        }
        var shop=new Shop(id,"Fixture",market,market==Marketplace.OZON?"fixture-client":null,"fixture-token");
        saveSettings(shop,CERTIFICATE,ZnackModels.PRODUCTION_TRUE_API);
        catalog(shop);
        return shop;
    }
    public static void saveSettings(Shop shop,String fingerprint,String base) {
        String metadata="{\"selector\":\""+fingerprint+"\",\"thumbprint\":\""+fingerprint+"\",\"inn\":\"7700000000\",\"hasPrivateKey\":true,\"validFrom\":\""+Instant.now().minusSeconds(86400)+"\",\"validTo\":\""+Instant.now().plusSeconds(86400)+"\"}";
        var settings=new ZnackModels.Settings(base,"","","","7700000000","","","",fingerprint,"[]","","","",false,"","[]",metadata,Instant.now(),"","","",60);
        new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).saveSettings(settings);
    }
    public static void catalog(Shop shop) {
        new GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),List.of(new RegisteredGtin(GTIN,"Fixture","","",true,true,RegisteredGtinProof.source(shop),Instant.now())));
    }
}
