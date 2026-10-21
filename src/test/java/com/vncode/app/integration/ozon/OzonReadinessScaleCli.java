package com.vncode.app.integration.ozon;

import com.google.gson.Gson;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.performance.SellerWorkspaceFixture;
import java.nio.file.*;
import java.util.*;

/** Repeatable baseline/current local workload; no marketplace, certificate or printer calls. */
public final class OzonReadinessScaleCli {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("isolated-data-directory output-json run-label");
        Path root=Path.of(args[0]).toAbsolutePath().normalize();SellerWorkspaceFixture.create(root,10);
        System.setProperty("vncode.appdata.dir",root.toString());
        try(var fixture=LicenseDomainFixture.active(root.resolve("test-license"))){
            LicenseRuntime.install(fixture);var repository=new OzonPostingRepository();var service=new OzonPrintReadinessService();
            var durations=new LinkedHashMap<String,Double>();var counts=new LinkedHashMap<String,Integer>();
            for(int shopId=1;shopId<=10;shopId++){
                var owner=new Shop(shopId,"Scale fixture",Marketplace.OZON,Integer.toString(shopId),"synthetic-fixture-key");
                long start=System.nanoTime();var all=repository.findByStatus(shopId,"",1000,0);
                if(all.size()!=1000)throw new AssertionError("Every fixture shop must have 1000 postings");
                durations.put("read1000_shop"+shopId,ms(start));
                var numbers=all.stream().map(OzonPostingDto::postingNumber).toList();
                if(shopId==1){
                    start=System.nanoTime();verify(service.inspectAll(owner,numbers.subList(0,100)),100);
                    durations.put("readiness100_shop1",ms(start));
                }
                start=System.nanoTime();var result=service.inspectAll(owner,numbers);verify(result,1000);
                durations.put("readiness1000_shop"+shopId,ms(start));counts.put("processed_shop"+shopId,result.postings().size());
            }
            var report=new LinkedHashMap<String,Object>();report.put("run",args[2]);report.put("synthetic",true);
            report.put("shops",10);report.put("productsPerShop",10000);report.put("postingsPerShop",1000);
            report.put("workload","local readiness with unavailable mapping; all postings checked, no reservation or HTTP");
            report.put("durationsMs",durations);report.put("counts",counts);
            report.put("actualWindowsLowEnd",false);Files.writeString(Path.of(args[1]),new Gson().toJson(report));
            System.out.println("scale_report_written="+Path.of(args[1]).getFileName());
        }
    }
    private static void verify(OzonBatchPrintReadiness result,int expected){
        if(result.postings().size()!=expected||result.ready()||result.postings().stream().anyMatch(p->!p.postingAvailable()))
            throw new AssertionError("All postings must be inspected and unresolved mapping must remain blocked");
    }
    private static double ms(long started){return (System.nanoTime()-started)/1_000_000d;}
}
