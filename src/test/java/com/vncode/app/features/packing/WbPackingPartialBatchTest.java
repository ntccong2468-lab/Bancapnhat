package com.vncode.app.features.packing;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.WbApiClient;
import com.vncode.app.integration.wb.WbOrderRepository;
import com.vncode.app.integration.wb.WbReadbackPendingException;
import com.vncode.app.integration.wb.WbSyncWorkflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WbPackingPartialBatchTest {
    @TempDir Path root;
    @Test void acceptedBatchIsPreservedWhenNextBatchFailsAndOlderOrdersAreNotDeleted() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        var prior=LicenseRuntime.service();var license=LicenseDomainFixture.active(root.resolve("license"));LicenseRuntime.install(license);
        try {
            var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
            try(var c=Database.getConnection();var s=c.createStatement()) {
                s.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,synced_at) VALUES(1,'WB-GI-1',0,'now')");
                s.execute("INSERT INTO wb_orders(shop_id,order_id,supply_id,supplier_status,synced_at) VALUES(1,1,'WB-GI-1','confirm','now')");
                s.execute("INSERT INTO wb_supply_orders(shop_id,supply_id,order_id) VALUES(1,'WB-GI-1',1)");
                for(int id=2;id<=102;id++)s.execute("INSERT INTO wb_orders(shop_id,order_id,supplier_status,synced_at) VALUES(1,"+id+",'new','now')");
            }
            var batches=new ArrayList<List<Long>>();
            var api=new WbApiClient(){@Override public void addOrdersToSupply(String token,String id,List<Long> orders)throws IOException {
                batches.add(List.copyOf(orders));if(batches.size()==2)throw new IOException("simulated failure before acknowledgement");
            }};
            var selected=new ArrayList<Long>();for(long id=2;id<=102;id++)selected.add(id);
            assertThrows(IOException.class,()->new PackingWorkflow(api).addOrdersToSupply(shop,"WB-GI-1",selected));
            assertEquals(List.of(100,1),batches.stream().map(List::size).toList());
            var repository=new WbOrderRepository(); var stored=repository.getOrderIdsForSupply(1,"WB-GI-1");
            assertEquals(101,stored.size());assertTrue(stored.contains(1L));assertTrue(stored.contains(101L));assertFalse(stored.contains(102L));
            assertFalse(repository.replaceSupplyOrders(1,"WB-GI-1",List.of(1L),Instant.parse("2020-01-01T00:00:00Z")),"A remote read that started before WB accepted the batch cannot erase it");
            assertEquals(stored,repository.getOrderIdsForSupply(1,"WB-GI-1"));
        } finally {LicenseRuntime.install(prior);license.close();System.clearProperty("vncode.appdata.dir");}
    }
    @Test void acceptedBatchWithFailedReadbackRemainsPendingAndNeverResubmits() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        var prior=LicenseRuntime.service();var license=LicenseDomainFixture.active(root.resolve("license"));LicenseRuntime.install(license);
        try {
            var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
            try(var c=Database.getConnection();var s=c.createStatement()) {
                s.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,synced_at) VALUES(1,'WB-GI-1',0,'now')");
                s.execute("INSERT INTO wb_orders(shop_id,order_id,supplier_status,synced_at) VALUES(1,101,'new','now')");
            }
            var posts=new java.util.concurrent.atomic.AtomicInteger();
            var api=new WbApiClient(){@Override public void addOrdersToSupply(String token,String id,List<Long> orders){posts.incrementAndGet();}};
            var failedRead=new WbSyncWorkflow(){@Override public int syncSupplyOrdersAndStatuses(com.vncode.app.models.Shop s,String id)throws IOException{throw new IOException("readback unavailable");}};
            var warning=assertThrows(WbReadbackPendingException.class,()->new PackingWorkflow(api,failedRead).addOrdersToSupply(shop,"WB-GI-1",List.of(101L)));
            assertEquals("WB-GI-1",warning.supplyId());assertEquals(1,posts.get());
            try(var c=Database.getConnection();var s=c.createStatement()) {
                try(var r=s.executeQuery("SELECT count(*) FROM wb_supply_pending_confirmations WHERE shop_id=1 AND supply_id='WB-GI-1' AND order_id=101")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
                try(var r=s.executeQuery("SELECT count(*) FROM sync_streams WHERE shop_id=1 AND stream IN ('SUPPLY_CONTENT','FBS_STATUS') AND requested=1")){assertTrue(r.next());assertEquals(2,r.getInt(1));}
                try(var r=s.executeQuery("SELECT count(*) FROM wb_action_log WHERE shop_id=1 AND action_type='ADD_ORDERS_TO_SUPPLY' AND status='failed'")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
            }
            assertTrue(new WbOrderRepository().replaceSupplyOrders(1,"WB-GI-1",List.of(101L),Instant.now().plusSeconds(1)));
            assertEquals(1,posts.get(),"Readback must not submit the accepted batch again");
        } finally {LicenseRuntime.install(prior);license.close();System.clearProperty("vncode.appdata.dir");}
    }
}
