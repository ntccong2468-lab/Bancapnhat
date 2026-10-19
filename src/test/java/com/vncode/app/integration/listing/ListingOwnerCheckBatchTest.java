package com.vncode.app.integration.listing;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ListingOwnerCheckBatchTest {
    @TempDir Path directory;Shop shop;
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture','WILDBERRIES','fixture')");}
        shop=new Shop(1,"Fixture",Marketplace.WILDBERRIES,null,"fixture");
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void nestedScopesRestoreTheirParentAndReadEachCredentialChange()throws Exception {
        try(var database=Database.openBatchSession();var outer=ListingIdentity.batchChecks(shop)){
            ListingIdentity.require(shop);try(var inner=ListingIdentity.batchChecks(shop)){ListingIdentity.require(shop);}
            try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key=? WHERE id=1")){p.setString(1,"rotated-fixture");p.executeUpdate();}
            assertThrows(SecurityException.class,()->ListingIdentity.require(shop));
            try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key=? WHERE id=1")){p.setString(1,"fixture");p.executeUpdate();}
            ListingIdentity.require(shop);
        }
        ListingIdentity.require(shop);
    }
    @Test void changingTheDatasetInvalidatesAReusedStatement(){
        try(var database=Database.openBatchSession();var checks=ListingIdentity.batchChecks(shop)){
            System.setProperty("vncode.appdata.dir",directory.resolve("other").toString());
            assertThrows(SecurityException.class,()->ListingIdentity.require(shop));
            System.setProperty("vncode.appdata.dir",directory.toString());ListingIdentity.require(shop);
        }
    }
}
