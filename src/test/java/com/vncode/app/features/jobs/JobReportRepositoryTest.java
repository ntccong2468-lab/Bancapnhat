package com.vncode.app.features.jobs;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JobReportRepositoryTest {
    @TempDir Path root;Shop shop;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void everyRunHasItsOwnIdentityAndPerItemErrorsSurviveReload()throws Exception{
        var store=new SyncRunRepository();String first=store.begin(shop,"znack_marketplace","MATCHING"),second=store.begin(shop,"gs1","AUTHENTICATING");
        var item=store.add(shop,first,"100","200","300","Article","Black","L",GtinWriteTestFixture.GTIN,"QUEUED","","{\"never_export\":\"SENSITIVE-PAYLOAD\"}");
        store.update(shop,item,"NEEDS_ACTION","VALIDATING","OZON_SKU_CHANGED",null);store.stage(shop,first,"PARTIAL","REPORT","ITEMS_NEED_ACTION");store.stage(shop,second,"SUCCEEDED","REPORT","");
        var reports=new JobReportRepository().list();assertTrue(reports.stream().anyMatch(r->r.id().equals(second)&&r.state().equals("SUCCEEDED")));
        var report=reports.stream().filter(r->r.id().equals(first)).findFirst().orElseThrow();var lines=new JobReportRepository().lines(report,0);assertEquals("300",lines.getFirst().sku());assertEquals("OZON_SKU_CHANGED",lines.getFirst().error());assertFalse(lines.toString().contains("SENSITIVE-PAYLOAD"));assertEquals(2,new SyncRunRepository().events(first,null,100).size());
        Path target=root.resolve("report.json");JobReportExporter.export(report,target);String json=Files.readString(target);assertTrue(json.contains("OZON_SKU_CHANGED"));assertFalse(json.contains("SENSITIVE-PAYLOAD"));
    }
    @Test void completedAndUnknownStatesCannotRemainRunningForever(){
        assertEquals("SUCCEEDED",JobReportRepository.normalized("SUCCEEDED"));assertEquals("SUCCEEDED",JobReportRepository.normalized("printed"));assertEquals("NEEDS_ACTION",JobReportRepository.normalized("unrecognized_future_status"));assertEquals("RECONCILING",JobReportRepository.normalized("RECONCILE_REQUIRED"));
    }
    @Test void legacyJobsRetainHistoryButNeverExportRawErrorOrKizPayload()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO print_jobs(shop_id,printed_at,item_count,template_layout_json,status,error_message) VALUES(1,'now',1,'{\"kiz\":\"PAID-CODE-MUST-NOT-EXPORT\"}','printed','SUPERSECRETTOKEN')");}
        var report=new JobReportRepository().list().stream().filter(r->r.kind().equals("print")).findFirst().orElseThrow();assertEquals("SUCCEEDED",report.state());assertFalse(report.toString().contains("SUPERSECRET"));Path file=root.resolve("legacy.json");JobReportExporter.export(report,file);String data=Files.readString(file);assertFalse(data.contains("PAID-CODE"));assertFalse(data.contains("SUPERSECRET"));
    }
    @Test void csvQuotesFieldsAndNeutralizesSpreadsheetCommands(){
        assertEquals("\"'=HYPERLINK(\"\"evil\"\")\"",JobReportExporter.cell("=HYPERLINK(\"evil\")"));assertEquals("\"a,b\"",JobReportExporter.cell("a,b"));assertEquals("\"00000000000017\"",JobReportExporter.cell("00000000000017"));
    }
    @Test void scopeChangeCannotModifyOldReports()throws Exception{
        var store=new SyncRunRepository();String id=store.begin(shop,"znack_marketplace","MATCHING");try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated' WHERE id=1");}
        assertThrows(SecurityException.class,()->store.stage(shop,id,"SUCCEEDED","REPORT",""));
    }
    @Test void largeReportsPageItemsWithoutLosingLaterRows(){
        var store=new SyncRunRepository();String id=store.begin(shop,"znack_marketplace","MATCHING");for(int n=0;n<1005;n++)store.add(shop,id,"p"+n,"v"+n,"","A","B","L",GtinWriteTestFixture.GTIN,"QUEUED","","{}");
        assertEquals(1000,store.items(id,0,1000).size());assertEquals(5,store.items(id,1000,1000).size());assertEquals(1005,store.list(1,0,10).getFirst().total());
    }
}
