package com.vncode.app.features.gtinsync;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GtinTnvedIdentityTest {
    @Test void rejectsTheGroupChangeObservedOnTheLiveR21Card(){
        assertFalse(GtinTnvedIdentity.compatible("6112110000",List.of("6103109000")));
        assertFalse(GtinTnvedIdentity.compatible("6211323100",List.of("6103220000")));
    }
    @Test void acceptsTheVerifiedLiveCardAndOzonDictionaryLabels(){
        assertTrue(GtinTnvedIdentity.compatible("6103109000",List.of("6103109000")));
        assertTrue(GtinTnvedIdentity.compatible("6103109000 - Костюмы мужские",List.of("6103109000")));
        assertTrue(GtinTnvedIdentity.compatible("6110309100",List.of("6110113000")));
    }
    @Test void missingHistoricalMetadataDoesNotReplaceTheOtherExistingIdentityChecks(){
        assertTrue(GtinTnvedIdentity.compatible("",List.of("6103109000")));
        assertTrue(GtinTnvedIdentity.compatible("6103109000",List.of()));
    }
}
