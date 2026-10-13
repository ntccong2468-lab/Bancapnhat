package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class KizServiceTest {
    private static final char GS = 0x1D;

    @TempDir
    Path tempDir;

    @AfterEach
    void clearAppDataOverride() {
        System.clearProperty("vncode.appdata.dir");
    }

    @Test
    void scannerSafeCodeRemovesOnlyLeadingGs() {
        String raw = GS + "010465039888513821ABC" + GS + "91XYZ" + GS + "92SIGNATURE";

        assertEquals("010465039888513821ABC" + GS + "91XYZ" + GS + "92SIGNATURE",
                KizService.scannerSafeCode(raw));
    }

    @Test
    void scannerSafeCodeKeepsInternalGsAndPlainCodes() {
        String raw = "010465039888513821ABC" + GS + "91XYZ" + GS + "92SIGNATURE";

        assertEquals(raw, KizService.scannerSafeCode(raw));
        assertEquals("", KizService.scannerSafeCode(""));
        assertNull(KizService.scannerSafeCode(null));
    }

    @Test void thousandMetadataOrdersUseTenBoundedBatchesAndRejectCrossBatchScope()throws Exception{
        List<Long> ids=new ArrayList<>();for(long id=1;id<=1000;id++)ids.add(id);ids.add(1L);ids.add(null);ids.add(-1L);
        var calls=new AtomicInteger();Set<Long> queried=new HashSet<>();
        var result=KizService.getSgtinMetadata(ids,batch->{calls.incrementAndGet();assertTrue(batch.size()<=100);Map<Long,KizService.SgtinMetadata> response=new HashMap<>();for(Long id:batch){assertTrue(queried.add(id));response.put(id,new KizService.SgtinMetadata(true,"fixture-value"));}response.put(9999L,new KizService.SgtinMetadata(true,"other-shop-value"));return response;});
        assertEquals(10,calls.get());assertEquals(1000,result.size());assertNull(result.get(9999L));
    }

}
