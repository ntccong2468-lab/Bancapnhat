package com.vncode.app.integration.wb;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WbFbsSnapshotRaceTest {
    @TempDir Path root;
    @AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }
    private void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString()); Database.initDatabase();
        try (var c=Database.getConnection(); var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shop','fixture')");
            s.execute("INSERT INTO wb_supplies(shop_id,supply_id,synced_at) VALUES(1,'WB-GI-1','now')");
            s.execute("INSERT INTO wb_supplies(shop_id,supply_id,synced_at) VALUES(1,'WB-GI-OLD','now')");
            s.execute("INSERT INTO wb_orders(shop_id,order_id,supply_id,supplier_status,wb_status,status_synced_at,synced_at) VALUES(1,101,'WB-GI-1','complete','waiting','2026-10-31T10:00:02Z','now'),(1,102,NULL,'new',NULL,'2026-10-31T10:00:02Z','now')");
            s.execute("INSERT INTO wb_supply_orders(shop_id,supply_id,order_id) VALUES(1,'WB-GI-1',101)");
            s.execute("INSERT INTO wb_order_skus(shop_id,order_id,sku) VALUES(1,101,'sku-101')");
            s.execute("INSERT INTO wb_order_meta_requirements(shop_id,order_id,meta_key,requirement_type) VALUES(1,101,'sgtin','required')");
        }
    }
    @Test void staleOrderPayloadCannotMoveAConfirmedSupplyToAnOldSupply() throws Exception {
        setup(); var order=new Gson().fromJson("{\"id\":101,\"supplyId\":\"WB-GI-OLD\"}",WbOrderDto.class);
        new WbOrderRepository().saveCurrentNewOrdersSnapshot(1,List.of(order),Instant.parse("2026-10-31T10:00:00Z"));
        try (var c=Database.getConnection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT supply_id,supplier_status FROM wb_orders WHERE shop_id=1 AND order_id=101")) {assertTrue(r.next());assertEquals("WB-GI-1",r.getString(1));assertEquals("complete",r.getString(2));}
            try(var r=s.executeQuery("SELECT supply_id FROM wb_supply_orders WHERE shop_id=1 AND order_id=101")) {assertTrue(r.next());assertEquals("WB-GI-1",r.getString(1));assertFalse(r.next());}
        }
    }
    @Test void lateNewOrdersResponseCannotEraseNewerStatusSupplyOrOrderFields() throws Exception {
        setup(); var order=new Gson().fromJson("{\"id\":101}",WbOrderDto.class);
        new WbOrderRepository().saveCurrentNewOrdersSnapshot(1,List.of(order),Instant.parse("2026-10-31T10:00:00Z"));
        try (var c=Database.getConnection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT supplier_status,supply_id FROM wb_orders WHERE shop_id=1 AND order_id=101")) {
                assertTrue(r.next()); assertEquals("complete",r.getString(1)); assertEquals("WB-GI-1",r.getString(2));
            }
            try(var r=s.executeQuery("SELECT supplier_status FROM wb_orders WHERE shop_id=1 AND order_id=102")) {
                assertTrue(r.next()); assertEquals("new",r.getString(1),"An old empty snapshot must not clear a later new status");
            }
            for(String table:List.of("wb_supply_orders","wb_order_skus","wb_order_meta_requirements"))
                try(var r=s.executeQuery("SELECT COUNT(*) FROM "+table+" WHERE shop_id=1 AND order_id=101")) {assertTrue(r.next());assertEquals(1,r.getInt(1));}
        }
    }
    @Test void lateStatusResponseDoesNotOverwriteMoreRecentStatus() throws Exception {
        setup(); var status=new Gson().fromJson("{\"id\":101,\"supplierStatus\":\"confirm\",\"wbStatus\":\"waiting\"}",WbOrderStatusDto.class);
        new WbOrderRepository().updateOrderStatuses(1,List.of(status),Instant.parse("2026-10-31T10:00:00Z"));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT supplier_status FROM wb_orders WHERE shop_id=1 AND order_id=101")) {assertTrue(r.next());assertEquals("complete",r.getString(1));}
    }
    @Test void concurrentResponsesWithinOneMillisecondStillPreserveTheNewerStatus() throws Exception {
        setup();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("UPDATE wb_orders SET status_synced_at='2026-10-31T10:00:00.000900Z' WHERE shop_id=1 AND order_id=101");
        }
        var status=new Gson().fromJson("{\"id\":101,\"supplierStatus\":\"confirm\",\"wbStatus\":\"waiting\"}",WbOrderStatusDto.class);
        new WbOrderRepository().updateOrderStatuses(1,List.of(status),Instant.parse("2026-10-31T10:00:00.000100Z"));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT supplier_status FROM wb_orders WHERE shop_id=1 AND order_id=101")) {assertTrue(r.next());assertEquals("complete",r.getString(1));}
    }
    @Test void delayedOldSupplyReadCannotMoveAnOrderBackAfterConfirmedTransfer() throws Exception {
        setup();var repository=new WbOrderRepository();Instant oldRead=Instant.now().minusSeconds(2);
        repository.appendConfirmedSupplyOrders(1,"WB-GI-OLD",List.of(101L));
        assertFalse(repository.replaceSupplyOrders(1,"WB-GI-1",List.of(101L),oldRead));
        assertEquals(List.of(),repository.getOrderIdsForSupply(1,"WB-GI-1"));
        assertEquals(List.of(101L),repository.getOrderIdsForSupply(1,"WB-GI-OLD"));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT order_count FROM wb_supplies WHERE shop_id=1 AND supply_id='WB-GI-1'")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void wbReadbackMustContainConfirmedOrdersBeforeTheyCanBeRemoved() throws Exception {
        setup();var repository=new WbOrderRepository();
        repository.appendConfirmedSupplyOrders(1,"WB-GI-OLD",List.of(101L));
        Instant after=Instant.now().plusSeconds(1);
        assertFalse(repository.replaceSupplyOrders(1,"WB-GI-OLD",List.of(),after),"An eventually consistent empty response cannot discard a 204-confirmed order");
        assertEquals(List.of(101L),repository.getOrderIdsForSupply(1,"WB-GI-OLD"));
        assertTrue(repository.replaceSupplyOrders(1,"WB-GI-OLD",List.of(101L),after),"WB has now confirmed the order through readback");
        assertTrue(repository.replaceSupplyOrders(1,"WB-GI-OLD",List.of(),Instant.now().plusSeconds(1)),"A subsequent authoritative removal is allowed");
        assertEquals(List.of(),repository.getOrderIdsForSupply(1,"WB-GI-OLD"));
    }
    @Test void snapshotContainingMoreThanOneThousandOrdersRetainsAllReturnedOrders() throws Exception {
        setup(); var gson=new Gson(); var orders=new java.util.ArrayList<WbOrderDto>();
        for(int id=1000;id<2501;id++) orders.add(gson.fromJson("{\"id\":"+id+"}",WbOrderDto.class));
        new WbOrderRepository().saveCurrentNewOrdersSnapshot(1,orders,Instant.now().plusSeconds(1));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM wb_orders WHERE shop_id=1 AND order_id>=1000 AND supplier_status='new'")) {assertTrue(r.next());assertEquals(1501,r.getInt(1));}
    }
    @Test void staleSupplyReadCannotReopenAnAlreadyDeliveredShipment() throws Exception {
        setup();var supply=new WbSupplyRepository();supply.markSupplyDelivered(1,"WB-GI-1");
        var old=new Gson().fromJson("{\"id\":\"WB-GI-1\",\"done\":false}",WbSupplyDto.class);
        supply.saveSupplies(1,List.of(old));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT done FROM wb_supplies WHERE shop_id=1 AND supply_id='WB-GI-1'")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
    }
}
