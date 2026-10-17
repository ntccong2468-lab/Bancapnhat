package com.vncode.app.testsupport;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.models.Shop;
import java.util.*;

/** Synthetic completed reads; the test controls exactly which native keys the source observed. */
public final class RegistrationSourceFixture {
    public static String ready(Shop shop)throws Exception {
        GtinWriteTestFixture.saveSettings(shop,GtinWriteTestFixture.CERTIFICATE,ZnackModels.PRODUCTION_TRUE_API);
        var report=new SyncRunRepository();String run=report.begin(shop,RegistrationSources.KIND,"READING_MARKETPLACE");
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_runs SET source_revision=? WHERE id=?")){
            p.setString(1,RegisteredGtinProof.source(shop));p.setString(2,run);p.executeUpdate();
        }
        var members=new ArrayList<SyncRunRepository.Item>();
        String sql=shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON
            ?"SELECT product_id,offer_id,sku FROM ozon_products WHERE shop_id=?":"SELECT CAST(nm_id AS TEXT),CAST(chrt_id AS TEXT),'' FROM wb_product_sizes WHERE shop_id=?";
        try(var c=Database.getConnection();var p=c.prepareStatement(sql)) {
            p.setInt(1,shop.getId());try(var rows=p.executeQuery()){while(rows.next())members.add(new SyncRunRepository.Item(UUID.randomUUID().toString(),run,rows.getString(1),rows.getString(2),rows.getString(3),"","","","","SUCCEEDED","READING_MARKETPLACE","","","{}"));}
        }
        report.addBatch(shop,run,members);report.stage(shop,run,"SUCCEEDED","SOURCES_COMPLETE","");return run;
    }
}
