package com.vncode.app.integration.wb;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class WbStatusBatchIntegrityTest {
    @TempDir Path directory;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(1,'Synthetic','synthetic-key','WILDBERRIES')");
            s.execute("INSERT INTO wb_orders(shop_id,order_id,supplier_status,wb_status,synced_at) VALUES(1,1,'new','waiting','old'),(1,2,'new','waiting','old'),(1,3,'new','waiting','old'),(1,4,'new','waiting','old')");
        }
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private int count(String where)throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM wb_orders WHERE "+where)){return r.getInt(1);}
    }
    @Test void failureInTheMiddleCannotCommitHalfTheStatusPageAndTheNextAttemptCanSucceed()throws Exception{
        var rows=new Gson().fromJson("""
                {"orders":[{"id":1,"supplierStatus":"confirm","wbStatus":"waiting"},
                {"id":2,"supplierStatus":"confirm","wbStatus":"waiting"},
                {"id":3,"supplierStatus":"confirm","wbStatus":"waiting"}]}
                """,WbOrderStatusesResponse.class).getOrders();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("CREATE TRIGGER reject_status_page BEFORE UPDATE ON wb_orders WHEN NEW.order_id=2 BEGIN SELECT RAISE(ABORT,'synthetic status failure'); END");
        }
        var repository=new WbOrderRepository();
        assertThrows(RuntimeException.class,()->repository.updateOrderStatuses(1,rows));
        assertEquals(3,count("order_id<=3 AND supplier_status='new'"),"A failed page must retain every old status");
        assertEquals(0,count("status_synced_at IS NOT NULL"));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DROP TRIGGER reject_status_page");}
        repository.updateOrderStatuses(1,rows);
        assertEquals(3,count("supplier_status='confirm' AND status_synced_at IS NOT NULL"));
        assertEquals(1,count("order_id=4 AND supplier_status='new' AND status_synced_at IS NULL"));
    }
}
