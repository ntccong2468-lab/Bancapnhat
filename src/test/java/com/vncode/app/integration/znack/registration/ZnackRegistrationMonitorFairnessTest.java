package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ZnackRegistrationMonitorFairnessTest {
    @TempDir Path directory;
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void wbReadsABoundedOldestWindowAndDoesNotStarveAnApprovedCardBehindTenThousandImports()throws Exception {
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);seed(shop);
        var rows=new ZnackCardRegistrationRepository().pendingStatus(shop.getId(),30);
        assertEquals(30,rows.size());assertEquals(10001,rows.getFirst().chrtId());
        assertTrue(rows.stream().noneMatch(row->row.chrtId()==10002||row.chrtId()==10003));
        rotateReads("znack_card_registrations");
        assertEquals(10001,new ZnackCardRegistrationRepository().pendingStatus(shop.getId(),30).getFirst().chrtId());
    }
    @Test void ozonUsesTheSameBoundedOwnerConsentAndOldestReadOrder()throws Exception {
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);seed(shop);
        var rows=ZnackRegistrationMonitor.pendingOzonKeys(shop,30);
        assertEquals(30,rows.size());assertEquals("10001",rows.getFirst().cardId());
        assertTrue(rows.stream().noneMatch(row->row.cardId().equals("10002")||row.cardId().equals("10003")));
        rotateReads("ozon_card_registrations");
        assertEquals("10001",ZnackRegistrationMonitor.pendingOzonKeys(shop,30).getFirst().cardId());
    }
    private void rotateReads(String table)throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.executeUpdate("UPDATE "+table+" SET updated_at='2030-01-01T00:00:00Z' WHERE status='READY_TO_SIGN'");
            s.executeUpdate("UPDATE "+table+" SET updated_at='2031-01-01T00:00:00Z' WHERE status='PROCESSING'");
        }
    }
    private void seed(Shop shop)throws Exception {
        String owner=ListingIdentity.fingerprint(shop);boolean wb=shop.getMarketplace()==Marketplace.WILDBERRIES;
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);
            String sql=wb?"INSERT INTO znack_card_registrations(shop_id,nm_id,chrt_id,gtin,feed_id,status,created_at,updated_at) VALUES(1,?,?,?,? ,?,'2020-01-01T00:00:00Z',?)"
                    :"INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,gtin,feed_id,status,scope_json,owner,created_at,updated_at) VALUES(2,?,?,?, ?,?,?,'{}',?,'2020-01-01T00:00:00Z',?)";
            try(var row=c.prepareStatement(sql);var cards=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,?,'Fixture','Fixture','fixture')");var sizes=c.prepareStatement("INSERT INTO wb_product_sizes(shop_id,nm_id,chrt_id,tech_size) VALUES(1,?,?,'XL')")){
                for(int n=1;n<=10003;n++){
                    String gtin=gtin(n),state=n>10000?"READY_TO_SIGN":"PROCESSING",date=n>10000?"2020-01-01T00:00:00Z":"2026-01-01T00:00:00Z";
                    if(wb){cards.setInt(1,n);cards.addBatch();sizes.setInt(1,n);sizes.setInt(2,n);sizes.addBatch();row.setInt(1,n);row.setInt(2,n);row.setString(3,gtin);row.setString(4,"feed-"+n);row.setString(5,state);row.setString(6,date);}
                    else{row.setString(1,Integer.toString(n));row.setString(2,"offer-"+n);row.setString(3,Integer.toString(n));row.setString(4,gtin);row.setString(5,"feed-"+n);row.setString(6,state);row.setString(7,owner);row.setString(8,date);}
                    row.addBatch();
                    if(n%500==0){if(wb){cards.executeBatch();sizes.executeBatch();}row.executeBatch();}
                }
                if(wb){cards.executeBatch();sizes.executeBatch();}row.executeBatch();
            }
            try(var p=c.prepareStatement("INSERT INTO znack_registration_signing_consents VALUES(?,?,?,?,?,?,1,'fixture')")){
                for(int n:new int[]{10001,10003}){p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());p.setString(3,Integer.toString(n));p.setString(4,wb?Integer.toString(n):"offer-"+n);p.setString(5,gtin(n));p.setString(6,n==10001?owner:"foreign-owner");p.addBatch();}p.executeBatch();
            }
            c.commit();
        }
    }
    private static String gtin(int number){
        String body=String.format("046319%07d",number);int sum=0;for(int n=0;n<body.length();n++)sum+=(body.charAt(n)-'0')*(n%2==0?3:1);return body+((10-sum%10)%10);
    }
}
