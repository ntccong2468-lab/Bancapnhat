package com.vncode.app.features.print;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.print.history.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class KizPrintHistoryRecoveryTest {
    @TempDir Path root;
    private KizAttachmentCheckpointRepository checkpoints;
    private KizAttachmentCoordinator coordinator;
    private long ownJob,foreignJob;
    private final AtomicInteger reads=new AtomicInteger();
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture A','synthetic-a'),(2,'Fixture B','synthetic-b')");}
        checkpoints=new KizAttachmentCheckpointRepository();
        checkpoints.write(new KizAttachmentCoordinator.OrderKey(1,101),"supply-a",KizAttachmentCoordinator.hash("fixture-mark-a"),"ATTACH_PENDING");
        checkpoints.write(new KizAttachmentCoordinator.OrderKey(1,102),"supply-a",KizAttachmentCoordinator.hash("fixture-mark-b"),"RECONCILE");
        checkpoints.write(new KizAttachmentCoordinator.OrderKey(2,101),"supply-b",KizAttachmentCoordinator.hash("foreign-mark"),"ATTACH_PENDING");
        var history=new PrintHistoryRepository();
        ownJob=history.insertSuccessfulJob(1,"Fixture A","supply-a","Fixture","2026-10-14T00:00:00Z",2,null,"Fixture","{}",List.of(item(101,"fixture-mark-a"),item(102,"fixture-mark-b")));
        foreignJob=history.insertSuccessfulJob(2,"Fixture B","supply-b","Fixture","2026-10-14T00:00:00Z",1,null,"Fixture","{}",List.of(item(101,"foreign-mark")));
        coordinator=new KizAttachmentCoordinator(Runnable::run,new KizAttachmentCoordinator.AttachmentClient(){
            public KizService.RemoveMetaResult remove(String token,long order){throw new AssertionError("Recovery must never remove WB metadata");}
            public KizService.AttachCodeResult attach(String token,long order,String code){throw new AssertionError("Recovery must never resend a mark");}
            public Map<Long,KizService.SgtinMetadata> metadata(String token,List<Long> ids){assertEquals("synthetic-a",token);reads.incrementAndGet();long order=ids.getFirst();return Map.of(order,new KizService.SgtinMetadata(true,order==101?"fixture-mark-a":null));}
        },checkpoints,System::nanoTime);
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private PrintHistoryItem item(long order,String mark){return new PrintHistoryItem(0,(int)order,order,
            "","Fixture","","","","","","","","",mark,null);}
    @Test void recoveryUsesOriginalPrintedMarksAndClearsOnlyConfirmedOrEmptyOwnedOrders()throws Exception {
        var result=coordinator.reconcilePrintedJob(new Shop(1,"Fixture A","synthetic-a"),ownJob);
        assertEquals(Map.of(101L,KizAttachmentCoordinator.Reconciliation.CONFIRMED,102L,KizAttachmentCoordinator.Reconciliation.EMPTY),result);
        assertEquals(2,reads.get());assertEquals("CONFIRMED",checkpoints.read(new KizAttachmentCoordinator.OrderKey(1,101)).phase());
        assertEquals("READ_EMPTY",checkpoints.read(new KizAttachmentCoordinator.OrderKey(1,102)).phase());
        assertEquals("ATTACH_PENDING",checkpoints.read(new KizAttachmentCoordinator.OrderKey(2,101)).phase());
        assertTrue(coordinator.reconcilePrintedJob(new Shop(1,"Fixture A","synthetic-a"),ownJob).isEmpty());assertEquals(2,reads.get());
    }
    @Test void foreignPrintJobCannotAuthorizeAReadOrChangeAnyCheckpoint() {
        assertThrows(IOException.class,()->coordinator.reconcilePrintedJob(new Shop(1,"Fixture A","synthetic-a"),foreignJob));
        assertEquals(0,reads.get());
    }
}
