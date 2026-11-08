package com.vncode.app.shared;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermitDocumentNumberTest {
    @Test void onlyRegistryLetterBlocksChangeAndNormalizationIsIdempotent(){
        for(String country:List.of("RU","CN","TR")){
            String original="ЕАЭС N RU Д-"+country+".PA08.B.54321/25";
            String expected="ЕАЭС N RU Д-"+country+".РА08.В.54321/25";
            assertEquals(expected,PermitDocumentNumber.normalize(original));
            assertEquals(expected,PermitDocumentNumber.normalize(expected));
        }
        assertEquals("ЕАЭС RU С-CN.НК12.А.12345/24",PermitDocumentNumber.normalize("ЕАЭС RU С-CN.HK12.A.12345/24"));
        assertEquals("RU Д-RU.РА08.В.54321/25",PermitDocumentNumber.normalize("RU Д-RU.PА08.B.54321/25"));
    }
    @Test void arbitraryIdentifiersUnknownFormatsAndNonVisualSubstitutionsStayUnchanged(){
        for(String unsupported:List.of("SYNTHETIC-PB","ЕАЭС N RU D-RU.PA08.B.54321/25","ЕАЭС N RU Д-RU.PA08.B.543210/25",
                "ЕАЭС N RU Д-RU.PA08.B.54321/2025","ЕАЭС N RU Д-RU.pa08.b.54321/25","ЕАЭС N RU Д-RU.RA08.B.54321/25",
                "prefix ЕАЭС N RU Д-RU.PA08.B.54321/25","ЕАЭС N RU Д-RU.PA08.B.54321/25 suffix","2025-09-12"))
            assertEquals(unsupported,PermitDocumentNumber.normalize(unsupported));
        assertEquals("",PermitDocumentNumber.normalize(null));assertEquals("",PermitDocumentNumber.normalize("   "));
    }
}
