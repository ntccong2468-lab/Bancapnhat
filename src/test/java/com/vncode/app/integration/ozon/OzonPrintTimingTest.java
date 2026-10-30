package com.vncode.app.integration.ozon;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OzonPrintTimingTest {
    @Test void recordsOnlyBoundedAggregateDurationsAndRestoresNestedScopes() {
        var now=new AtomicLong(1_000_000L);
        try(var outer=OzonPrintTiming.begin(now::get)) {
            long stock=OzonPrintTiming.tick();now.addAndGet(125_000_000L);
            OzonPrintTiming.add(OzonPrintTiming.Stage.STOCK,stock);
            OzonPrintTiming.labelGroup();OzonPrintTiming.isolatedLabel();
            try(var inner=OzonPrintTiming.begin(now::get)) {
                long label=OzonPrintTiming.tick();now.addAndGet(25_000_000L);
                OzonPrintTiming.add(OzonPrintTiming.Stage.LABEL_NETWORK,label);
                assertEquals(25,inner.snapshot().millis(OzonPrintTiming.Stage.LABEL_NETWORK));
                assertEquals(0,inner.snapshot().labelGroups());
            }
            long compose=OzonPrintTiming.tick();now.addAndGet(50_000_000L);
            OzonPrintTiming.add(OzonPrintTiming.Stage.COMPOSITION,compose);
            var summary=outer.snapshot();
            assertEquals(200,summary.totalMillis());assertEquals(125,summary.millis(OzonPrintTiming.Stage.STOCK));
            assertEquals(50,summary.millis(OzonPrintTiming.Stage.COMPOSITION));
            assertEquals(0,summary.millis(OzonPrintTiming.Stage.LABEL_NETWORK));
            assertEquals(1,summary.labelGroups());assertEquals(1,summary.isolatedLabels());
            assertThrows(UnsupportedOperationException.class,()->summary.stageMillis().clear());
        }
        OzonPrintTiming.add(OzonPrintTiming.Stage.STOCK,OzonPrintTiming.tick());
        try(var next=OzonPrintTiming.begin(now::get)){assertEquals(0,next.snapshot().millis(OzonPrintTiming.Stage.STOCK));}
    }
}
