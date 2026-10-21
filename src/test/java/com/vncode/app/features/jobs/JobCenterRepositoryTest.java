package com.vncode.app.features.jobs;

import com.vncode.app.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class JobCenterRepositoryTest {
    @TempDir Path data;
    @AfterEach void clearOverride(){System.clearProperty("vncode.appdata.dir");}

    @Test void readsActualSchemaAndKeepsRecoverableExportsWithoutCompletedJobs() throws Exception {
        System.setProperty("vncode.appdata.dir",data.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'First','synthetic'),(2,'Second','synthetic')");
            for(var state:java.util.List.of("success","pdf_ready","failed"))
                s.execute("INSERT INTO print_jobs(shop_id,supply_id,printed_at,item_count,template_layout_json,status,error_message) VALUES(1,'FBO/test','now',3,'{}','"+state+"','SENSITIVE-PAYLOAD')");
            s.execute("INSERT INTO print_jobs(shop_id,supply_id,printed_at,item_count,template_layout_json,status) VALUES(2,'FBO/test','now',2,'{}','prepared')");
        }
        var rows=new JobCenterRepository().load();
        assertEquals(3,rows.size());
        assertTrue(rows.stream().anyMatch(r->r.shopId()==1&&r.state().equals("review")&&r.count()==1));
        assertTrue(rows.stream().anyMatch(r->r.shopId()==1&&r.state().equals("blocked")&&r.count()==1));
        assertTrue(rows.stream().anyMatch(r->r.shopId()==2&&r.state().equals("running")&&r.count()==1));
        assertFalse(rows.toString().contains("SENSITIVE-PAYLOAD"));
    }
}
