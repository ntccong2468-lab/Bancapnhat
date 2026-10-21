package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationReportDataTest {
    @TempDir Path directory;Shop shop;String run;SyncRunRepository reports;
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=new Shop(1,"=HYPERLINK(\"synthetic\")",Marketplace.OZON,"fixture","synthetic");
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,?,'OZON','fixture','synthetic')")){p.setString(1,shop.getName());p.executeUpdate();}
        reports=new SyncRunRepository();run=reports.begin(shop,"znack_marketplace","READBACK");
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private void rows(int count)throws Exception{
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);try(var p=c.prepareStatement("INSERT INTO sync_run_items(id,run_id,product_id,variant_id,sku,article,color,size,gtin,state,step,error_code,evidence_json,updated_at) VALUES(?,?,?,?,?,'@SUM(1)','Blue','M','00000000000017',?,'READBACK','NETWORK_TIMEOUT',?,'2026-10-21T01:02:03Z')")){
                for(int n=1;n<=count;n++){p.setString(1,"row-"+n);p.setString(2,run);p.setString(3,"product-"+n);p.setString(4,"variant-"+n);p.setString(5,n==count?"needle-last":"sku-"+n);p.setString(6,n==count?"FAILED":"SUCCEEDED");p.setString(7,"{\"barcodes\":[\"000123\"],\"goodId\":\"91\",\"catalogStatus\":\"draft\",\"catalogDetailedStatus\":[\"moderation\"],\"catalogCheckedAt\":\"2026-10-21T00:00:00Z\",\"checkResult\":\"VERIFIED\",\"syncResult\":\"UNKNOWN\",\"nextAction\":\"READBACK_REQUIRED\",\"reason\":\"token=private-secret\",\"api_key\":\"never-export\",\"signatureXml\":\"private-cms\"}");p.addBatch();}p.executeBatch();
            }try(var p=c.prepareStatement("UPDATE sync_runs SET total=? WHERE id=?")){p.setInt(1,count);p.setString(2,run);p.executeUpdate();}c.commit();
        }
    }
    @Test void filtersScanTheEntireRunAndDoNotStopAtTenThousandRows()throws Exception{
        rows(10_001);var page=RegistrationReportData.page(shop,run,new RegistrationReportData.Filter("needle-last","FAILED"),0,300);
        assertEquals(1,page.matched());assertEquals("variant-10001",page.rows().getFirst().variantId());assertEquals(Set.of("FAILED","SUCCEEDED"),page.states());
        assertEquals(0,RegistrationReportData.page(shop,run,new RegistrationReportData.Filter("needle-last","SUCCEEDED"),0,300).matched());
    }
    @Test void summaryFindsTheActualFailureOutsideTheVisiblePageAndFilter()throws Exception{
        rows(1001);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_run_items SET error_code='ZNACK_INVALID_ATTRIBUTE_VALUE',evidence_json=? WHERE id='row-1001' AND run_id=?")){
            p.setString(1,"{\"reason\":\"ZNACK_INVALID_ATTRIBUTE_VALUE: Размер одежды / изделия [35]\"}");p.setString(2,run);p.executeUpdate();
        }
        var page=RegistrationReportData.page(shop,run,new RegistrationReportData.Filter("","SUCCEEDED"),0,300);
        assertEquals(300,page.rows().size());assertEquals(1,page.failures().size());
        String summary=RegistrationSyncReportDialog.failureSummary(page.failures());
        assertTrue(summary.contains("Размер одежды / изделия [35]"),summary);
        assertTrue(summary.contains("@SUM(1) / M"),summary);
    }
    @Test void csvExportsEveryRowWithRawCodesRedactedEvidenceAndFormulaProtection()throws Exception{
        rows(10_001);String multilineSku="Public first line\nPublic \"quoted\", second line";
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_run_items SET sku=? WHERE id='row-1' AND run_id=?")){p.setString(1,multilineSku);p.setString(2,run);assertEquals(1,p.executeUpdate());}
        Path target=directory.resolve("full.csv");RegistrationReportData.exportCsv(shop,reports.find(run).orElseThrow(),new RegistrationReportData.Filter("","ALL"),target);String csv=Files.readString(target);
        var records=parseCsv(csv);assertEquals(10_002,records.size());
        int columns=records.getFirst().size();assertEquals(22,columns);assertTrue(records.stream().allMatch(row->row.size()==columns),"Quoted error text must not split CSV records or columns");
        assertTrue(records.stream().skip(1).anyMatch(row->row.get(5).equals("variant-10001")));assertEquals(multilineSku,records.stream().filter(row->row.get(5).equals("variant-1")).findFirst().orElseThrow().get(6),"Public SKU line breaks, quotes and comma must survive exact CSV decoding");assertTrue(csv.contains("variant-10001"));assertTrue(csv.contains("'000123"));assertTrue(csv.contains("'00000000000017"));assertTrue(csv.contains("'@SUM(1)"));assertTrue(csv.contains("'=HYPERLINK"));assertTrue(csv.contains("draft / moderation"));assertTrue(csv.contains("2026-10-21T00:00:00Z"));assertTrue(csv.contains("2026-10-21T01:02:03Z"));assertTrue(csv.contains("READBACK_REQUIRED"));assertFalse(csv.contains("private-secret"));assertFalse(csv.contains("never-export"));assertFalse(csv.contains("private-cms"));
        Path filtered=directory.resolve("filtered.csv");RegistrationReportData.exportCsv(shop,reports.find(run).orElseThrow(),new RegistrationReportData.Filter("needle-last","FAILED"),filtered);assertEquals(2,parseCsv(Files.readString(filtered)).size());
    }
    private static List<List<String>> parseCsv(String csv){
        var records=new ArrayList<List<String>>();var row=new ArrayList<String>();var value=new StringBuilder();boolean quoted=false;
        for(int i=csv.startsWith("\uFEFF")?1:0;i<csv.length();i++){
            char c=csv.charAt(i);
            if(quoted){
                if(c=='"'){if(i+1<csv.length()&&csv.charAt(i+1)=='"'){value.append('"');i++;}else quoted=false;}else value.append(c);
            }else if(c=='"'){assertTrue(value.isEmpty(),"Opening CSV quote must begin a field");quoted=true;}
            else if(c==','){row.add(value.toString());value.setLength(0);}
            else if(c=='\r'||c=='\n'){row.add(value.toString());value.setLength(0);records.add(List.copyOf(row));row.clear();if(c=='\r'&&i+1<csv.length()&&csv.charAt(i+1)=='\n')i++;}
            else value.append(c);
        }
        assertFalse(quoted,"CSV must close every quoted field");if(!row.isEmpty()||!value.isEmpty()){row.add(value.toString());records.add(List.copyOf(row));}return records;
    }
    @Test void aChangedOwnerCannotOverwriteAnExistingExport()throws Exception{
        rows(1);Path target=directory.resolve("retained.csv");Files.writeString(target,"keep previous export");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=1");}
        assertThrows(SecurityException.class,()->RegistrationReportData.exportCsv(shop,reports.find(run).orElseThrow(),new RegistrationReportData.Filter("","ALL"),target));assertEquals("keep previous export",Files.readString(target));
    }
    @Test void literalOwnerCredentialIsRemovedEvenWhenItHasNoTokenLabel()throws Exception{
        rows(1);try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE sync_run_items SET article=?,evidence_json=? WHERE run_id=?")){
            p.setString(1,"Bare value: "+shop.getApiKey());p.setString(2,"{\"barcodes\":[\""+shop.getApiKey()+"\"],\"reason\":\"Unlabelled "+shop.getApiKey()+"\"}");p.setString(3,run);p.executeUpdate();
        }
        Path target=directory.resolve("context-redacted.csv");RegistrationReportData.exportCsv(shop,reports.find(run).orElseThrow(),new RegistrationReportData.Filter("","ALL"),target);String csv=Files.readString(target);assertFalse(csv.contains(shop.getApiKey()));assertTrue(csv.contains("[REDACTED]"));assertTrue(csv.contains("variant-1"));
    }
}
