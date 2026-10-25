package com.vncode.app.config;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.kiz.PendingKizFixture;
import com.vncode.app.features.products.*;
import com.vncode.app.features.sync.RegistrationSources;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.LocalDataMigrationGate;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SynchronizedDataUpgradeTest {
    @TempDir Path data;
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}

    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void everySavedBusinessRowSurvivesVersionChangeAndReopeningWithoutAnotherSync(Marketplace market)throws Exception{
        System.setProperty("vncode.appdata.dir",data.toString());Database.initDatabase();
        var shop=PendingKizFixture.create(market);var now=Instant.now();String gtin=PendingKizFixture.GTIN;
        var key=new ProductKey(shop.getId(),market,market==Marketplace.OZON?"9001":"101",market==Marketplace.OZON?"offer-M":"11");
        var gtinKey=new GtinSyncModels.ProductKey(key.shopId(),key.marketplace(),key.cardId(),key.variantId());
        var document=new ProductDocument("permit-1","declaration","Synthetic declaration",LocalDate.now().minusDays(1),LocalDate.now().plusDays(300),"Synthetic owner","6109",null,market,DocumentState.VALID,"https://fixture.invalid/document",now);
        var attributes=List.of(new ProductAttribute("tnved","TN VED",List.of("6109100000"),""),new ProductAttribute("material","Material",List.of("Cotton"),""),new ProductAttribute("gender","Gender",List.of("Male"),""));
        var snapshot=new ProductSnapshot(key,"live:synthetic","Saved product","Saved description","Saved brand","Fixture","fashion",List.of(),attributes,List.of(new ProductVariant(key.variantId(),"Fixture","Black","M",gtin,false)),null,List.of(document),now,Map.of("owner",ListingIdentity.fingerprint(shop)));
        var products=new ProductRepository();products.saveVerifiedSnapshot(shop,snapshot);GtinWriteTestFixture.catalog(shop);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE "+(market==Marketplace.OZON?"ozon_products":"wb_product_cards")+" SET synced_at=? WHERE shop_id=?")){p.setString(1,now.toString());p.setInt(2,shop.getId());p.executeUpdate();}
        var gtins=new GtinSyncRepository();gtins.saveMapping(gtinKey,gtin);gtins.recordVerifiedMapping(gtinKey,gtin);
        String run=RegistrationSourceFixture.ready(shop);
        var streams=new com.vncode.app.features.sync.SyncStreamStore();
        streams.finish(shop,"CATALOG",streams.ensure(shop,"CATALOG",now),"saved-page-2",false,now,now.plusSeconds(90),new com.vncode.app.features.sync.SyncFailure("API_COOLDOWN",Duration.ofSeconds(90),false));
        var streamBefore=streams.ensure(shop,"CATALOG",now);
        try(var c=Database.getConnection();var s=c.createStatement()){
            int id=shop.getId();
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES("+id+",'"+gtin+"','Synthetic','fixture')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,"+id+",'"+gtin+"',1,'READY','fixture','fixture')");
            s.execute("INSERT INTO kiz_codes(shop_id,order_id,gtin,raw_code,display_code,status,legal_status,created_at,updated_at) VALUES("+id+",1,'"+gtin+"','synthetic-code','synthetic','AVAILABLE','IN_CIRCULATION','fixture','fixture')");
            s.execute("INSERT INTO print_jobs(shop_id,shop_name,printed_at,item_count,template_layout_json,status) VALUES("+id+",'Synthetic','fixture',1,'{}','COMPLETED')");
        }
        try(var ignored=LocalDataMigrationGate.prepare(data,"1.5.16","javafx")){}
        var before=businessRows();var verifiedBefore=gtins.verifiedMappings(shop);assertEquals(gtin,verifiedBefore.get(gtinKey).gtin());
        for(String version:List.of("1.5.17","1.5.17"))try(var ignored=LocalDataMigrationGate.prepare(data,version,"javafx")){
            assertEquals(before,businessRows(),"An upgrade or restart must not clear or regenerate synchronized data");
            assertEquals(run,new RegistrationSources().require(shop).run());assertEquals(gtin,gtins.mapping(gtinKey));assertEquals(verifiedBefore,gtins.verifiedMappings(shop));
            assertEquals(streamBefore,new com.vncode.app.features.sync.SyncStreamStore().ensure(shop,"CATALOG",Instant.now()),"Keep the exact cursor, retry deadline and last result across versions");
            var saved=new ProductRepository().localSnapshot(key).orElseThrow();assertEquals("Saved brand",saved.brand());assertEquals(attributes,saved.attributes());assertEquals(List.of(document),saved.documents());
        }
    }

    private Map<String,String> businessRows()throws Exception{
        var result=new TreeMap<String,String>();
        try(var c=Database.getConnection();var p=c.createStatement();var names=p.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")){
            while(names.next()){
                String name=names.getString(1);var hash=MessageDigest.getInstance("SHA-256");
                try(var query=c.createStatement();var rows=query.executeQuery("SELECT * FROM \""+name.replace("\"","\"\"")+"\" ORDER BY rowid")){
                    int count=rows.getMetaData().getColumnCount();
                    while(rows.next())for(int n=1;n<=count;n++){
                        String value=rows.getString(n);byte[] bytes=value==null?new byte[0]:value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        hash.update(java.nio.ByteBuffer.allocate(4).putInt(value==null?-1:bytes.length).array());hash.update(bytes);
                    }
                }
                result.put(name,HexFormat.of().formatHex(hash.digest()));
            }
        }
        return Map.copyOf(result);
    }
}
