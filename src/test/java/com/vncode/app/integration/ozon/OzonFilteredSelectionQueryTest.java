package com.vncode.app.integration.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.features.ozon.OzonFbsQuery;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonFilteredSelectionQueryTest {
    @TempDir Path appData;
    private OzonPostingRepository repository;

    @BeforeEach void setUp() throws Exception {
        System.setProperty("vncode.appdata.dir", appData.toString());
        Database.initDatabase();
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'one','OZON','client1','key1'),(2,'two','OZON','client2','key2')");
            statement.execute("""
                    WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n<12050)
                    INSERT INTO ozon_postings(shop_id,posting_number,status,synced_at)
                    SELECT 1,printf('bulk-%05d',n),CASE WHEN n<=11000 THEN 'awaiting_packaging' ELSE 'awaiting_deliver' END,'now' FROM numbers
                    """);
            statement.execute("INSERT INTO ozon_postings(shop_id,posting_number,status,synced_at) VALUES(2,'foreign','awaiting_packaging','now')");
        }
        repository = new OzonPostingRepository();
    }

    @AfterEach void tearDown() { System.clearProperty("vncode.appdata.dir"); }

    @Test void selectedCountCoversAllMatchesBeyondTenThousandAndRespectsShopAndFilter() {
        var selected = new ArrayList<String>();
        for (int i=1; i<=12050; i++) selected.add("bulk-%05d".formatted(i));
        selected.add("bulk-00001"); selected.add("foreign"); selected.add("missing");
        var packaging = new OzonFbsQuery(1,"awaiting_packaging","","",100,100);
        assertEquals(11000, repository.countSelectedFiltered(packaging, selected));
        assertEquals(1050, repository.countSelectedFiltered(new OzonFbsQuery(1,"awaiting_deliver","","",0,100), selected));
        assertEquals(1, repository.countSelectedFiltered(new OzonFbsQuery(2,"awaiting_packaging","","",0,100), selected));
        assertEquals(0, repository.countSelectedFiltered(packaging, List.of()));
    }

    @Test void rejectedCheckUsesCurrentOwnerFilterAndPendingReplacementOverlay() throws Exception {
        String owner;
        try (var connection=Database.getConnection()) { owner=ListingIdentity.fingerprint(connection,1,Marketplace.OZON); }
        try (var connection=Database.getConnection(); var insert=connection.prepareStatement("INSERT INTO kn3_marks(shop_id,marketplace,owner,order_id,product_id,exemplar_id,code,state,reason,checked_at) VALUES(1,'OZON',?,'bulk-11000','sku','piece','test','REJECTED','','now')")) {
            insert.setString(1,owner); insert.executeUpdate();
        }
        var all = new OzonFbsQuery(1,"awaiting_packaging","","",0,100);
        assertTrue(repository.hasRejectedFiltered(all));
        assertFalse(repository.hasRejectedFiltered(new OzonFbsQuery(1,"awaiting_packaging","bulk-00001","",0,100)));
        assertFalse(repository.hasRejectedFiltered(new OzonFbsQuery(2,"awaiting_packaging","","",0,100)));
        try (var connection=Database.getConnection(); var insert=connection.prepareStatement("INSERT INTO kn3_replacements(id,shop_id,marketplace,owner,order_id,product_id,exemplar_id,old_hash,new_code,state,payload,updated_at) VALUES('r',1,'OZON',?,'bulk-11000','sku','piece','hash','new','AWAITING','{}','now')")) {
            insert.setString(1,owner); insert.executeUpdate();
        }
        assertFalse(repository.hasRejectedFiltered(all));
        try (var connection=Database.getConnection(); var statement=connection.createStatement()) {
            statement.execute("UPDATE kn3_replacements SET state='FAILED' WHERE id='r'");
        }
        assertTrue(repository.hasRejectedFiltered(all));
        try (var connection=Database.getConnection(); var statement=connection.createStatement()) {
            statement.execute("UPDATE shops SET api_key='rotated' WHERE id=1");
        }
        assertFalse(repository.hasRejectedFiltered(all));
    }
}
