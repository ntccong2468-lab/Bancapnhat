package com.vncode.app.features.jobs;

import com.vncode.app.features.sync.SyncRunRepository;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class JobReportExporter {
    private JobReportExporter(){}
    public static void export(JobReportRepository.Report report,Path target)throws Exception {
        var lines=new ArrayList<JobReportRepository.Line>();var repository=new JobReportRepository();report=repository.refresh(report);
        for(int offset=0;offset<100_000;offset+=300){var page=repository.lines(report,offset);lines.addAll(page);if(page.size()<300)break;if(offset+300>=100_000)throw new IllegalStateException("REPORT_EXPORT_LIMIT");}
        String content;
        if(target.toString().toLowerCase(Locale.ROOT).endsWith(".csv")){
            var out=new StringBuilder("\ufeffProduct,Variant,SKU,Article,Color,Size,GTIN,State,Step,Reason\r\n");
            for(var row:lines){var values=List.of(row.product(),row.variant(),row.sku(),row.article(),row.color(),row.size(),row.gtin(),row.state(),row.step(),row.error());out.append(values.stream().map(JobReportExporter::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");}content=out.toString();
        }else{
            var data=new LinkedHashMap<String,Object>();data.put("report",report);data.put("items",lines);data.put("events",report.detailed()?new SyncRunRepository().events(report.id(),null,1000):List.of());content=new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data)+"\n";
        }
        Path parent=target.toAbsolutePath().getParent();Path staged=Files.createTempFile(parent,".vncode-report-",".tmp");
        try{Files.writeString(staged,content,StandardCharsets.UTF_8);try{Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(staged,target,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(staged);}
    }
    static String cell(String input){String s=Objects.toString(input,"");if(s.matches("^[\\s]*[=+@-].*")||s.startsWith("\t")||s.startsWith("\r"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
}
