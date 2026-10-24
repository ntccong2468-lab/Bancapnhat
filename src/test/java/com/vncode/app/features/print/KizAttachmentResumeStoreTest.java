package com.vncode.app.features.print;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.models.*;
import com.vncode.app.shared.UpdateHandoff;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class KizAttachmentResumeStoreTest {
    @TempDir Path directory;Shop shop;
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=new Shop(1,"WB","fixture");
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'WB','fixture')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'04600000000001','Fixture','2026-08-18T00:00:00Z')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'04600000000001',2,'COMPLETED','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
            for(int id=1;id<=2;id++)s.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,created_at,updated_at) VALUES("+id+",1,1,'fixture-mark-"+id+"','fixture','04600000000001','CONSUMED','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
        }
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    List<OrderExportWorkflow.KizAttachmentAssignment> assignments(){return java.util.stream.IntStream.rangeClosed(1,2).mapToObj(id->new OrderExportWorkflow.KizAttachmentAssignment(100L+id,"fixture-mark-"+id,new Kiz(id,"fixture-mark-"+id),false)).toList();}
    @Test void savedQueueResumesExistingStockAndSkipsAnAlreadyConfirmedOrder()throws Exception {
        var store=new KizAttachmentResumeStore();assertTrue(store.save(shop,"SUP","Supply",assignments()));
        var journal=new KizAttachmentCheckpointRepository();journal.write(new KizAttachmentCoordinator.OrderKey(1,101),"SUP",KizAttachmentCoordinator.hash("fixture-mark-1"),"CONFIRMED");
        var calls=new AtomicInteger();var coordinator=new KizAttachmentCoordinator(Runnable::run,client(calls,null,null),journal,System::nanoTime);
        coordinator.resumePending(shop);assertEquals(1,calls.get());assertFalse(coordinator.hasActiveJobs());assertTrue(new KizAttachmentResumeStore().pending(shop).isEmpty());
        try(var c=Database.getConnection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT COUNT(*) FROM kiz_codes")){assertTrue(r.next());assertEquals(2,r.getInt(1));}
            try(var r=s.executeQuery("PRAGMA integrity_check")){assertTrue(r.next());assertEquals("ok",r.getString(1));}
            try(var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
        }
    }
    @Test void updateCanYieldAfterOneOrderInsteadOfWaitingForTheWholeQueue()throws Exception {
        var workers=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var coordinator=new KizAttachmentCoordinator(workers,client(calls,entered,release),new KizAttachmentCheckpointRepository(),System::nanoTime);
        try {
            coordinator.enqueue(shop,"SUP","Supply",assignments());assertTrue(entered.await(5,TimeUnit.SECONDS));
            try(var pause=UpdateHandoff.pause()) {
                coordinator.prepareForUpdate();release.countDown();
                boolean idle=false;for(int i=0;i<30&&!idle;i++)idle=UpdateHandoff.awaitIdle(100);
                assertTrue(idle,"Installer must not wait for the unsent second order");assertEquals(1,calls.get());
                assertEquals(2,new KizAttachmentResumeStore().pending(shop).getFirst().assignments().size());
                coordinator.cancel(1,"SUP");
            }
        }finally{release.countDown();workers.shutdown();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void changedOwnershipOrMissingStockNeverAllocatesAnotherCodeOnResume()throws Exception {
        var store=new KizAttachmentResumeStore();assertTrue(store.save(shop,"SUP","Supply",assignments()));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='changed' WHERE id=1");}
        assertTrue(store.pending(new Shop(1,"WB","changed")).isEmpty());
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='fixture' WHERE id=1");s.execute("DELETE FROM kiz_codes WHERE id=2");}
        assertTrue(store.pending(shop).isEmpty());
    }
    @Test void aCodeRejectedWhileTheAppWasClosedCannotBeAssignedAgain()throws Exception {
        var store=new KizAttachmentResumeStore();assertTrue(store.save(shop,"SUP","Supply",assignments()));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET legal_status='REJECTED' WHERE id=2");}
        assertTrue(store.pending(shop).isEmpty());
    }
    @Test void additiveResumeTablePreservesAnExistingShopAndContainsOnlyReferences()throws Exception {
        var store=new KizAttachmentResumeStore();assertTrue(store.save(shop,"SUP","Supply",assignments()));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT refs_json FROM wb_kiz_attachment_resume")) {
            assertTrue(r.next());assertFalse(r.getString(1).contains("fixture-mark"));assertFalse(r.getString(1).contains("api_key"));
        }
        assertEquals(2,new KizAttachmentResumeStore().pending(shop).getFirst().assignments().size());
    }
    private KizAttachmentCoordinator.AttachmentClient client(AtomicInteger calls,CountDownLatch entered,CountDownLatch release) {
        return new KizAttachmentCoordinator.AttachmentClient(){
            public KizService.RemoveMetaResult remove(String token,long id){throw new AssertionError("No removal");}
            public KizService.AttachCodeResult attach(String token,long id,String code)throws java.io.IOException {
                if(calls.incrementAndGet()==1&&entered!=null){entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}}
                return new KizService.AttachCodeResult(true,204,"");
            }
        };
    }
}
