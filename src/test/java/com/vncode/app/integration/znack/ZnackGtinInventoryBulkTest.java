package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.models.Kiz;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ZnackGtinInventoryBulkTest {
    @TempDir Path directory;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(Connection c=Database.getConnection();PreparedStatement ps=c.prepareStatement("INSERT INTO shops(id,name,api_key) VALUES(?,?,?)")){
            for(int shop=1;shop<=10;shop++){ps.setInt(1,shop);ps.setString(2,"Shop "+shop);ps.setString(3,"fixture-token-"+shop);ps.addBatch();}ps.executeBatch();
        }
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void tenThousandUnitsAcrossTenShopsReserveAndFinalizeInExactScope()throws Exception{
        for(int shop=1;shop<=10;shop++)seed(shop,100,10);
        var inventory=new ZnackGtinInventoryService();Map<Integer,List<Kiz>> reserved=new LinkedHashMap<>();Set<Integer> ids=new HashSet<>();
        for(int shop=1;shop<=10;shop++){
            Map<String,List<Kiz>> byGtin=inventory.reserveAvailableBulk(shop,quantities(100,10),"owner-"+shop);
            List<Kiz> codes=byGtin.values().stream().flatMap(List::stream).toList();assertEquals(1000,codes.size());
            for(Kiz code:codes){assertTrue(ids.add(code.getId()));assertEquals("owner-"+shop,code.getReservationToken());}reserved.put(shop,codes);
        }
        assertEquals(10000,ids.size());assertThrows(IllegalStateException.class,()->inventory.consume(2,reserved.get(1)));
        for(int shop=1;shop<=10;shop++)assertEquals(1000,shop%2==0?inventory.release(shop,reserved.get(shop)):inventory.consume(shop,reserved.get(shop)));
        for(int shop=1;shop<=10;shop++)assertEquals(shop%2==0?10:0,inventory.availableCount(shop,gtin(1)));
        try(Connection c=Database.getConnection();Statement st=c.createStatement()){
            try(ResultSet rs=st.executeQuery("PRAGMA foreign_key_check")){assertFalse(rs.next());}
            try(ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM kiz_codes WHERE reservation_token IS NOT NULL OR status='RESERVED'")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
        }
    }
    @Test void thousandDifferentGtinsCrossSqlParameterChunksAndShortageRollsBackEntirePlan()throws Exception{
        seed(1,1000,1);var inventory=new ZnackGtinInventoryService();var plan=quantities(1000,1);plan.put(gtin(1001),1);
        assertThrows(IllegalStateException.class,()->inventory.reserveAvailableBulk(1,plan,"shortage-owner"));assertEquals(1,inventory.availableCount(1,gtin(1)));
        plan.remove(gtin(1001));var result=inventory.reserveAvailableBulk(1,plan,"bulk-owner");assertEquals(1000,result.size());
        var all=result.values().stream().flatMap(List::stream).toList();assertEquals(1000,inventory.consume(1,all));
    }
    @Test void staleOrDuplicateOwnershipCannotPartiallyReleaseBulkReservations()throws Exception{
        seed(1,1,1000);var inventory=new ZnackGtinInventoryService();var reserved=inventory.reserveAvailableBulk(1,Map.of(gtin(1),1000),"owner").get(gtin(1));
        List<Kiz> corrupted=new ArrayList<>(reserved);Kiz last=corrupted.getLast();corrupted.set(corrupted.size()-1,new Kiz(last.getId(),last.getCode(),"stale-owner"));
        assertThrows(IllegalStateException.class,()->inventory.release(1,corrupted));assertEquals(0,inventory.availableCount(1,gtin(1)));
        List<Kiz> duplicates=new ArrayList<>(reserved);duplicates.set(duplicates.size()-1,duplicates.getFirst());assertThrows(IllegalStateException.class,()->inventory.consume(1,duplicates));
        assertEquals(1000,inventory.release(1,reserved));assertEquals(1000,inventory.availableCount(1,gtin(1)));
    }
    @Test void availableCirculationPartialIndexIsUsedAfterAdditiveMigration()throws Exception{
        try(Connection c=Database.getConnection();Statement st=c.createStatement();ResultSet rs=st.executeQuery("EXPLAIN QUERY PLAN SELECT id,raw_code FROM kiz_codes INDEXED BY idx_kiz_codes_available_circulation WHERE shop_id=1 AND gtin='47000000000001' AND status='AVAILABLE' AND legal_status='IN_CIRCULATION' ORDER BY id LIMIT 1000")){
            StringBuilder plan=new StringBuilder();while(rs.next())plan.append(rs.getString("detail"));assertTrue(plan.toString().contains("idx_kiz_codes_available_circulation"));
        }
    }
    private static Map<String,Integer> quantities(int groups,int amount){Map<String,Integer> result=new LinkedHashMap<>();for(int group=1;group<=groups;group++)result.put(gtin(group),amount);return result;}
    private static String gtin(int group){return String.format(Locale.ROOT,"%014d",47_000_000_000_000L+group);}
    private static void seed(int shop,int groups,int amount)throws Exception{
        try(Connection c=Database.getConnection();
            PreparedStatement product=c.prepareStatement("INSERT INTO znack_products(shop_id,gtin,synced_at) VALUES(?,?,'fixture-time')");
            PreparedStatement order=c.prepareStatement("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(?,?,?,?,'READY','fixture-time','fixture-time')");
            PreparedStatement code=c.prepareStatement("INSERT INTO kiz_codes(shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES(?,?,?,?,?,'AVAILABLE','IN_CIRCULATION','fixture-time','fixture-time')")){
            c.setAutoCommit(false);
            for(int group=1;group<=groups;group++){
                String gtin=gtin(group);int orderId=shop*10000+group;
                product.setInt(1,shop);product.setString(2,gtin);product.addBatch();
                order.setInt(1,orderId);order.setInt(2,shop);order.setString(3,gtin);order.setInt(4,amount);order.addBatch();
                for(int unit=1;unit<=amount;unit++){String raw="01"+gtin+"21fixture-"+shop+"-"+unit;code.setInt(1,shop);code.setInt(2,orderId);code.setString(3,raw);code.setString(4,"fixture-display");code.setString(5,gtin);code.addBatch();}
            }
            product.executeBatch();order.executeBatch();code.executeBatch();c.commit();
        }
    }
}
