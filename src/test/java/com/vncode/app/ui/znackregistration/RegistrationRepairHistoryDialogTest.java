package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.CatalogAutomaticRepairLedger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationRepairHistoryDialogTest {
    @Test void showsSavedBeforeAfterAndReasonWithoutClaimingSubmission(){
        var rounds=List.of(new CatalogAutomaticRepairLedger.Round("feed-1","Catalog rejected kit flag",
                "[{\"field\":\"is_kit\",\"before\":\"true\",\"after\":\"false\",\"source\":\"Catalog rejection\"}]","2026-10-30T10:00:00Z","PENDING"));
        var rows=RegistrationRepairHistoryDialog.rows(rounds);assertEquals(1,rows.size());
        assertEquals("is_kit",rows.getFirst().field());assertEquals("true",rows.getFirst().before());assertEquals("false",rows.getFirst().after());
        assertEquals("Catalog rejection",rows.getFirst().source());assertEquals("Catalog rejected kit flag",rows.getFirst().rejection());
    }
    @Test void malformedHistoryDoesNotInventARepair(){
        var rounds=List.of(new CatalogAutomaticRepairLedger.Round("feed-1","rejected","not json","","PENDING"));
        assertThrows(IllegalStateException.class,()->RegistrationRepairHistoryDialog.rows(rounds));
    }
}
