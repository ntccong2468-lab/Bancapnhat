package com.vncode.app.integration.ozon;

import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.models.Shop;
import java.util.*;

/** Durable print outcome per posting; local KIZ/PDF state never implies remote KIZ acceptance. */
public final class OzonFbsPrintReport {
 private final SyncRunRepository reports=new SyncRunRepository();
 private final String run;
 private final List<SyncRunRepository.Item> items;
 public OzonFbsPrintReport(Shop shop,List<String> numbers){
  run=reports.begin(shop,"ozon_fbs_print","FBS_PRINT");
  items=numbers.stream().distinct().map(n->new SyncRunRepository.Item(UUID.randomUUID().toString(),run,n,"","","","","","","RUNNING","FBS_PRINT","","","{}")).toList();
  reports.addBatch(shop,run,items);
 }
 public String id(){return run;}
 public void finish(Shop shop,boolean completed){
  var jobs=new OzonExemplarJobRepository().findMany(shop.getId(),items.stream().map(SyncRunRepository.Item::productId).toList());
  var out=new ArrayList<SyncRunRepository.Item>();
  try(var c=Database.getConnection()){
   reports.assertOwner(shop,run);c.setAutoCommit(false);
   var counts=new HashMap<Long,int[]>();var ids=jobs.values().stream().map(OzonExemplarJob::id).distinct().toList();
   for(int start=0;start<ids.size();start+=500){var batch=ids.subList(start,Math.min(start+500,ids.size()));
    try(var p=c.prepareStatement("SELECT e.job_id,count(*),coalesce(sum(EXISTS(SELECT 1 FROM kiz_import_entries i WHERE i.code_id=e.kiz_id)),0),coalesce(sum(e.check_status='printed'),0) FROM ozon_exemplars e WHERE e.shop_id=? AND e.job_id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+") AND e.kiz_id IS NOT NULL GROUP BY e.job_id")){
     p.setInt(1,shop.getId());for(int n=0;n<batch.size();n++)p.setLong(n+2,batch.get(n));
     try(var rows=p.executeQuery()){while(rows.next())counts.put(rows.getLong(1),new int[]{rows.getInt(2),rows.getInt(3),rows.getInt(4)});}
    }
   }
    for(var item:items){var job=jobs.get(item.productId());int codes=0,imported=0,printed=0;
     if(job!=null){var value=counts.getOrDefault(job.id(),new int[3]);codes=value[0];imported=value[1];printed=value[2];}
     var evidence=new JsonObject();evidence.addProperty("localStage",job==null?"NOT_STARTED":job.stage().name());evidence.addProperty("imported",imported);evidence.addProperty("purchased",codes-imported);evidence.addProperty("printed",printed);evidence.addProperty("pdfBatchComplete",completed);evidence.addProperty("remoteKizConfirmedByThisTask",false);
     String error=completed?"":job!=null&&job.safeErrorCode()!=null&&!job.safeErrorCode().isBlank()?"FBS_"+job.safeErrorCode().toUpperCase(Locale.ROOT):"FBS_PRINT_NOT_COMPLETED";
     out.add(new SyncRunRepository.Item(item.id(),run,item.productId(),"","","","","","",completed?"SUCCEEDED":"NEEDS_ACTION",completed?"FBS_PDF_CREATED":"FBS_PRINT",error,"",evidence.toString()));
    }
   c.commit();
  }catch(java.sql.SQLException failure){throw new IllegalStateException("FBS_REPORT_STORAGE",failure);}
  reports.updateBatch(shop,run,out);
  reports.stage(shop,run,completed?"SUCCEEDED":"NEEDS_ACTION",completed?"FBS_PDF_CREATED":"FBS_PRINT","" );
 }
}
