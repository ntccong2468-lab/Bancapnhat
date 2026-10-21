package com.vncode.app.ui.znackregistration;

import com.google.gson.*;
import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.znack.ZnackSanitizer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** An allowlisted read-only projection. Raw evidence/payloads never become an exported column. */
final class RegistrationReportData {
    record Filter(String text,String state){Filter{text=Objects.toString(text,"").strip().toLowerCase(Locale.ROOT);state=Objects.toString(state,"ALL");}}
    record Page(List<SyncRunRepository.Item> rows,long matched,Set<String> states){}
    private static final List<String> HEADERS=List.of("Marketplace","Shop","Scope","Run","Product","Variant","SKU","Article","Color","Size","Barcodes","GTIN","Catalog ID","Catalog raw status","Catalog checked at","Check result","Sync result","State","Step","Updated at","Error","Next action");
    private RegistrationReportData(){}
    static String field(SyncRunRepository.Item row,String name){
        try{var object=JsonParser.parseString(row.evidence()).getAsJsonObject();var value=object.get(name);if(value==null||value.isJsonNull())return "";
            if(value.isJsonArray()){var strings=new ArrayList<String>();for(var item:value.getAsJsonArray())if(item.isJsonPrimitive())strings.add(item.getAsString());return safe(String.join("; ",strings));}
            return value.isJsonPrimitive()?safe(value.getAsString()):"";
        }catch(RuntimeException invalid){return "";}
    }
    static String catalogState(SyncRunRepository.Item row){return String.join(" / ",java.util.stream.Stream.of(field(row,"catalogStatus"),field(row,"catalogDetailedStatus")).filter(value->!value.isBlank()).toList());}
    static String scopeKey(SyncRunRepository.Run run,List<SyncRunRepository.Item> rows){
        var scopes=rows.stream().map(row->field(row,"cohortScope")).filter(value->!value.isBlank()).distinct().toList();String scope=scopes.size()==1?scopes.getFirst():"";
        return "STORE".equals(scope)||com.vncode.app.features.sync.RegistrationSources.KIND.equals(run.kind())?"znack.registration.report_scope_store":"FILTER".equals(scope)?"znack.registration.report_scope_filter":"znack.registration.report_scope_batch";
    }
    static boolean matches(SyncRunRepository.Item row,Filter filter){
        if(!"ALL".equals(filter.state())&&!filter.state().equals(row.state()))return false;
        if(filter.text().isBlank())return true;
        return java.util.stream.Stream.of(row.productId(),row.variantId(),row.sku(),row.article(),row.color(),row.size(),row.gtin(),row.state(),row.error(),field(row,"barcodes"),field(row,"goodId"),catalogState(row),field(row,"checkResult"),field(row,"syncResult"),field(row,"nextAction")).anyMatch(value->safe(value).toLowerCase(Locale.ROOT).contains(filter.text()));
    }
    static Page page(Shop owner,String run,Filter filter,int offset,int limit){
        var repository=new SyncRunRepository();var result=new ArrayList<SyncRunRepository.Item>();var states=new LinkedHashSet<String>();long matched=0;
        for(int cursor=0;;cursor=Math.addExact(cursor,1000)){
            interrupted();repository.assertOwner(owner,run);var rows=repository.items(run,cursor,1000);
            for(var row:rows){states.add(row.state());if(matches(row,filter)){if(matched>=offset&&result.size()<limit)result.add(row);matched++;}}
            if(rows.size()<1000)break;
        }
        repository.assertOwner(owner,run);return new Page(List.copyOf(result),matched,Set.copyOf(states));
    }
    static void exportCsv(Shop owner,SyncRunRepository.Run run,Filter filter,Path target)throws IOException{
        var repository=new SyncRunRepository();repository.assertOwner(owner,run.id());var before=repository.find(run.id()).orElseThrow();
        Path staged=Files.createTempFile(target.toAbsolutePath().getParent(),".vncode-registration-",".tmp");
        try{
            try(var writer=Files.newBufferedWriter(staged,StandardCharsets.UTF_8)){
                writer.write('\ufeff');write(writer,HEADERS);
                for(int cursor=0;;cursor=Math.addExact(cursor,1000)){
                    interrupted();repository.assertOwner(owner,run.id());var rows=repository.items(run.id(),cursor,1000);
                    for(var row:rows)if(matches(row,filter))write(writer,values(owner,run,row),owner.getApiKey());
                    if(rows.size()<1000)break;
                }
            }
            interrupted();repository.assertOwner(owner,run.id());if(!before.equals(repository.find(run.id()).orElseThrow()))throw new IOException("SYNC_REPORT_CHANGED_RETRY");
            try{Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unavailable){Files.move(staged,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(staged);}
    }
    private static List<String> values(Shop owner,SyncRunRepository.Run run,SyncRunRepository.Item row){return List.of(run.marketplace(),owner.getName(),com.vncode.app.shared.I18nService.getInstance().tr(scopeKey(run,List.of(row))),run.id(),row.productId(),row.variantId(),row.sku(),row.article(),row.color(),row.size(),field(row,"barcodes"),row.gtin(),field(row,"goodId"),catalogState(row),field(row,"catalogCheckedAt"),field(row,"checkResult"),field(row,"syncResult"),row.state(),row.step(),row.updatedAt(),RegistrationSyncReportDialog.itemReason(row),RegistrationSyncReportDialog.nextAction(row));}
    private static void write(Writer writer,List<String> values)throws IOException{
        write(writer,values,"");
    }
    private static void write(Writer writer,List<String> values,String secret)throws IOException{
        for(int i=0;i<values.size();i++){if(i>0)writer.write(',');String original=Objects.toString(values.get(i),"");if(secret!=null&&!secret.isBlank())original=original.replace(secret,"[REDACTED]");String value=safe(original);if(value.matches("[0-9]+")||value.matches("(?s)^[\\s]*[=+@-].*")||value.startsWith("\t")||value.startsWith("\r"))value="'"+value;writer.write('"');writer.write(value.replace("\"","\"\""));writer.write('"');}writer.write("\r\n");
    }
    private static String safe(String value){return ZnackSanitizer.diagnostic(Objects.toString(value,"")).replaceAll("01[0-9]{14}21[^\\s]{13,}","[KIZ]");}
    private static void interrupted()throws java.util.concurrent.CancellationException{if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Report read cancelled");}
}
