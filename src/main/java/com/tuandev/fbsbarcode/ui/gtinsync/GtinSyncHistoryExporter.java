package com.tuandev.fbsbarcode.ui.gtinsync;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;

public final class GtinSyncHistoryExporter {
    private static final List<String> HEADERS=List.of("Shop","Marketplace","Product","Variant","Article","Operation","Old GTIN","New GTIN","Status");
    private List<String> values(JobItem item) {
        var p=item.preview();var k=p.before().key();
        return List.of(String.valueOf(k.shopId()),k.marketplace().name(),k.productId(),k.variantId(),p.before().article(),p.operation().name(),p.oldGtin(),p.newGtin(),item.status().name());
    }
    public void writeCsv(List<JobItem> items,Path path)throws IOException {
        var lines=new ArrayList<String>();lines.add(csv(HEADERS));
        for(var item:items)lines.add(csv(values(item).stream().map(v->v.matches("[0-9]+")?"'"+v:escapeFormula(v)).toList()));
        Files.writeString(path,"\uFEFF"+String.join("\r\n",lines)+"\r\n",StandardCharsets.UTF_8);
    }
    public void writeXlsx(List<JobItem> items,Path path)throws IOException {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("GTIN");var header=sheet.createRow(0);
            for(int i=0;i<HEADERS.size();i++)header.createCell(i).setCellValue(HEADERS.get(i));
            for(int n=0;n<items.size();n++) {
                var row=sheet.createRow(n+1);var values=values(items.get(n));
                for(int i=0;i<values.size();i++)row.createCell(i).setCellValue(escapeFormula(values.get(i)));
            }
            for(int i=0;i<HEADERS.size();i++)sheet.setColumnWidth(i,24*256);
            try(var out=Files.newOutputStream(path)){book.write(out);}
        }
    }
    private static String csv(List<String> values){return values.stream().map(v->"\""+v.replace("\"","\"\"")+"\"").collect(java.util.stream.Collectors.joining(","));}
    private static String escapeFormula(String value){return !value.isEmpty()&&"=+-@\t\r\n".indexOf(value.charAt(0))>=0?"'"+value:value;}
}
