package com.vncode.app.features.kiz.status;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Kn3OzonPrintProjectionTest {
    @TempDir Path root;
    Shop shop;
    final Kn3Repository repository=new Kn3Repository();
    static final String CODE="0104645588781154210000000000001\u001d91ABCD\u001d92FIXTURE";
    final List<Kn3PrintProjection.Slot> slots=List.of(new Kn3PrintProjection.Slot("101","7",CODE));

    @BeforeEach void setUp()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        shop=new Shop(1,"Synthetic",Marketplace.OZON,"client","fixture");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic','OZON','client','fixture')");
        }
    }
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    Kn3Mark rejected(){return new Kn3Mark("ORDER","101","7",CODE,Kn3Mark.State.REJECTED,"INVALID_STATUS");}

    @Test void retainedRejectedCodePrintsOnlyThroughExplicitOzonPath()throws Exception {
        repository.observe(shop,"ORDER",List.of(rejected()));
        assertEquals(List.of(CODE),Kn3PrintProjection.ozonReservedCodes(shop,"ORDER",slots));
        assertThrows(IOException.class,()->Kn3PrintProjection.codes(shop,"ORDER",slots));
        assertEquals(Kn3Mark.State.REJECTED,repository.read(shop,"ORDER").getFirst().state());
    }
    @Test void pendingReplacementCannotPrintOldOrUnconfirmedNewCode()throws Exception {
        var old=rejected();repository.observe(shop,"ORDER",List.of(old));repository.begin(shop,old,"new-code","{}");
        var failure=assertThrows(IOException.class,()->Kn3PrintProjection.ozonReservedCodes(shop,"ORDER",slots));
        assertEquals("FBS_KN3_REPLACEMENT_PENDING",failure.getMessage());
    }
    @Test void confirmedReplacementPrintsTheExactReadbackCode()throws Exception {
        var old=rejected();repository.observe(shop,"ORDER",List.of(old));repository.begin(shop,old,"new-code","{}");
        repository.observe(shop,"ORDER",List.of(new Kn3Mark("ORDER","101","7","new-code",Kn3Mark.State.ACCEPTED,"passed")));
        assertEquals(List.of("new-code"),Kn3PrintProjection.ozonReservedCodes(shop,"ORDER",slots));
        assertEquals(CODE,slots.getFirst().code());
    }
    @Test void rejectionOfSiblingDoesNotRemoveThisExemplarLabel()throws Exception {
        repository.observe(shop,"ORDER",List.of(new Kn3Mark("ORDER","101","8",CODE,Kn3Mark.State.REJECTED,"INVALID_STATUS")));
        assertEquals(List.of(CODE),Kn3PrintProjection.ozonReservedCodes(shop,"ORDER",slots));
    }
    @Test void aDifferentCodeOnTheSameExemplarStillRequiresReconciliation()throws Exception {
        repository.observe(shop,"ORDER",List.of(new Kn3Mark("ORDER","101","7","another-code",Kn3Mark.State.REJECTED,"INVALID_STATUS")));
        assertThrows(IOException.class,()->Kn3PrintProjection.ozonReservedCodes(shop,"ORDER",slots));
    }
}
