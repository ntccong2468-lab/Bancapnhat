package com.tuandev.fbsbarcode.ui.gtinsync;
import java.nio.file.*;
import java.util.*;
import java.time.Instant;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;
class GtinSyncHistoryExporterTest {
    @TempDir Path temp;
    JobItem item() {
        var p=new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"101","11"),"=FORMULA","Đen","XL",List.of("old"),"fp",Instant.now());
        return new JobItem(UUID.randomUUID(),UUID.randomUUID(),new Preview(p,Operation.ADD,"","00000000000017",new Capability(true,false,"")),Status.SUCCEEDED,"");
    }
    @Test void csvEscapesFormulaTextAndKeepsGtinDigits()throws Exception {
        var path=temp.resolve("history.csv");new GtinSyncHistoryExporter().writeCsv(List.of(item()),path);
        var text=Files.readString(path);assertTrue(text.contains("'00000000000017"));assertTrue(text.contains("'=FORMULA"));
    }
    @Test void xlsxUsesStringCellsForLeadingZeros()throws Exception {
        var path=temp.resolve("history.xlsx");new GtinSyncHistoryExporter().writeXlsx(List.of(item()),path);
        try(var in=Files.newInputStream(path);var book=new XSSFWorkbook(in)) {
            var row=book.getSheetAt(0).getRow(1);assertEquals("00000000000017",row.getCell(7).getStringCellValue());
            assertEquals("'=FORMULA",row.getCell(4).getStringCellValue());
        }
    }
}
