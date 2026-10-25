package com.vncode.app.features.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonFbsPrintStateTest {
    @TempDir Path directory;
    Shop shop;OzonPostingRepository postings;OzonFbsSnapshotService reader;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','client','fixture'),(2,'Other','OZON','other','fixture')");}
        shop=new Shop(1,"Fixture",Marketplace.OZON,"client","fixture");postings=new OzonPostingRepository();reader=new OzonFbsSnapshotService();
    }
    @AfterEach void reset(){System.clearProperty("vncode.appdata.dir");}
    @Test void printedAndNewOrdersStaySeparateAfterSyncAndRepositoryRestart(){
        postings.upsertPage(1,List.of(posting("OLD"),posting("NEW")));
        new OzonPrintReceiptRepository().record(shop,List.of("OLD"));
        postings.upsertPage(1,List.of(posting("OLD"),posting("NEW"),posting("ARRIVED")));
        reader=new OzonFbsSnapshotService();
        assertEquals(Set.of("NEW","ARRIVED"),new HashSet<>(reader.load(query(OzonFbsQuery.PrintState.UNPRINTED,0)).postings().stream().map(OzonPostingDto::postingNumber).toList()));
        assertEquals(List.of("OLD"),reader.load(query(OzonFbsQuery.PrintState.PRINTED,0)).postings().stream().map(OzonPostingDto::postingNumber).toList());
        assertEquals("awaiting_deliver",postings.find(1,"OLD").status(),"Local print grouping must not ship an order remotely");
    }
    @Test void printAllFreezesOnlyUnprintedAcrossEveryPageAndSearch(){
        var all=new ArrayList<OzonPostingDto>();for(int i=1;i<=1001;i++)all.add(posting("POST-"+i));postings.upsertPage(1,all);
        new OzonPrintReceiptRepository().record(shop,all.subList(0,650).stream().map(OzonPostingDto::postingNumber).toList());
        var first=reader.load(query(OzonFbsQuery.PrintState.UNPRINTED,0));var last=reader.load(query(OzonFbsQuery.PrintState.UNPRINTED,300));
        assertEquals(351,first.total());assertEquals(100,first.postings().size());assertEquals(51,last.postings().size());
        var frozen=postings.freezeFilteredNumbers(query(OzonFbsQuery.PrintState.UNPRINTED,0),10000);assertEquals(351,frozen.size());assertFalse(frozen.contains("POST-1"));assertTrue(frozen.contains("POST-1001"));
        var search=new OzonFbsQuery(1,"awaiting_deliver","POST-1001","",0,100,OzonFbsQuery.PrintState.UNPRINTED);assertEquals(List.of("POST-1001"),postings.freezeFilteredNumbers(search,10000));assertEquals(1,reader.load(search).total());
    }
    @Test void oldCompletedReportsMigrateAdditivelyButWaitingOrFailedReportsDoNot()throws Exception{
        postings.upsertPage(1,List.of(posting("SUCCESS"),posting("LOWERCASE"),posting("FAILED"),posting("WAITING")));
        var reports=new com.vncode.app.features.sync.SyncRunRepository();String run=reports.begin(shop,"OZON_FBS_PRINT","FBS_PRINT");
        for(String number:List.of("SUCCESS","FAILED","WAITING"))reports.addBatch(shop,run,List.of(new com.vncode.app.features.sync.SyncRunRepository.Item(UUID.randomUUID().toString(),run,number,"","","","","","",number.equals("SUCCESS")?"SUCCEEDED":"NEEDS_ACTION","FBS_PDF_CREATED","","",number.equals("WAITING")?"{\"pdfBatchComplete\":false}":"{\"pdfBatchComplete\":true}")));
        String lower=reports.begin(shop,"ozon_fbs_print","FBS_PRINT");reports.addBatch(shop,lower,List.of(new com.vncode.app.features.sync.SyncRunRepository.Item(UUID.randomUUID().toString(),lower,"LOWERCASE","","","","","","","SUCCEEDED","FBS_PDF_CREATED","","","{\"pdfBatchComplete\":true}")));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DROP TABLE ozon_fbs_print_receipts");}
        Database.initDatabase();Database.initDatabase();
        assertEquals(Set.of("SUCCESS","LOWERCASE"),new HashSet<>(reader.load(query(OzonFbsQuery.PrintState.PRINTED,0)).postings().stream().map(OzonPostingDto::postingNumber).toList()));
        assertEquals(2,reader.load(query(OzonFbsQuery.PrintState.UNPRINTED,0)).total());
        try(var c=Database.getConnection();var s=c.createStatement()){
            try(var r=s.executeQuery("PRAGMA integrity_check")){assertTrue(r.next());assertEquals("ok",r.getString(1));}
            try(var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
        }
    }
    @Test void receiptsAreScopedIdempotentAndCannotMoveMissingOrForeignOrders()throws Exception{
        postings.upsertPage(1,List.of(posting("SAME")));postings.upsertPage(2,List.of(posting("SAME")));
        var receipts=new OzonPrintReceiptRepository();receipts.record(shop,List.of("SAME","SAME"));receipts.record(shop,List.of("SAME"));assertEquals(1,reader.load(query(OzonFbsQuery.PrintState.PRINTED,0)).total());
        assertEquals(1,reader.load(new OzonFbsQuery(2,"awaiting_deliver","","",0,100,OzonFbsQuery.PrintState.UNPRINTED)).total());
        assertThrows(IllegalStateException.class,()->receipts.record(shop,List.of("SAME","MISSING")));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='changed' WHERE id=1");}
        assertThrows(SecurityException.class,()->receipts.record(shop,List.of("SAME")));assertEquals(0,reader.load(query(OzonFbsQuery.PrintState.PRINTED,0)).total());
    }
    private OzonFbsQuery query(OzonFbsQuery.PrintState state,int offset){return new OzonFbsQuery(1,"awaiting_deliver","","",offset,100,state);}
    private static OzonPostingDto posting(String number){return new OzonPostingDto(number,number,number,"awaiting_deliver","","","","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,"1","sku","article","Fixture",1,"RUB","1")));}
}
