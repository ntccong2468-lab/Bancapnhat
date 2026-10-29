package com.vncode.app.integration.ozon;

import com.itextpdf.kernel.pdf.*;
import com.vncode.app.models.Shop;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Download each shared task once. Page identity is read from the PDF, never inferred from request order. */
final class OzonBatchLabelDownloader implements OzonPrintBundleService.OfficialLabelDownloader {
    private final OzonLabelService service;
    private final Map<String,List<String>> membership=new LinkedHashMap<>();
    private final Set<List<String>> loaded=new HashSet<>();
    private final Set<List<String>> isolated=new HashSet<>();
    private final Map<String,List<Integer>> pages=new HashMap<>();
    private final Map<String,PdfDocument> documents=new HashMap<>();
    private final List<Path> temporaryFiles=new ArrayList<>();
    OzonBatchLabelDownloader(OzonLabelService service,Shop shop,List<String> numbers) {
        this.service=service;
        var known=new LinkedHashMap<String,List<String>>();var fresh=new ArrayList<String>();var repo=new OzonLabelRepository();
        var jobs=repo.findMany(shop.getId(),numbers);
        long batchable=numbers.stream().filter(number->{var job=jobs.get(number);return job==null||job.taskId()==null||job.taskId().isBlank()||OzonLabelService.canRegroup(job);}).count();
        boolean regroup=batchable>1&&jobs.values().stream().anyMatch(OzonLabelService::canRegroup);
        if(regroup)try {com.vncode.app.features.license.LicenseRuntime.require(com.vncode.app.features.license.LicenseModels.Permission.PRINT_NEW);}
        catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException denied){regroup=false;}
        for(String number:numbers) {
            var job=jobs.get(number);
            if(job==null||job.taskId()==null||job.taskId().isBlank()||regroup&&OzonLabelService.canRegroup(job))fresh.add(number);
            else if("ISOLATING".equals(job.status())||OzonLabelService.requiresNewTask(job)){
                var group=List.of(number);known.put("isolated:"+number,group);isolated.add(group);
            }else known.computeIfAbsent(job.taskId().startsWith("v3:")?job.taskId():"legacy:"+number,k->new ArrayList<>()).add(number);
        }
        for(int offset=0;offset<fresh.size();offset+=OzonLabelService.MAX_BATCH_POSTINGS)known.put("fresh:"+offset,new ArrayList<>(fresh.subList(offset,Math.min(offset+OzonLabelService.MAX_BATCH_POSTINGS,fresh.size()))));
        known.values().forEach(group->{var frozen=List.copyOf(group);frozen.forEach(number->membership.put(number,frozen));});
    }
    @Override public File download(Shop shop,String number,File target)throws IOException {
        var group=membership.get(number);if(group==null)throw new IOException("OZON_LABEL_BATCH_SELECTION_CHANGED");
        if(!isolated.contains(group)&&!loaded.contains(group))load(shop,group);
        if(isolated.contains(group))return service.downloadIsolatedPdf(shop,number,target);
        var selected=pages.get(number);if(selected==null||selected.isEmpty())throw new IOException("OZON_LABEL_BATCH_IDENTITY_UNVERIFIED");
        try(var output=new PdfDocument(new PdfWriter(target))) { documents.get(number).copyPagesTo(selected,output); }
        return target;
    }
    @Override public OzonPrintBundleService.VerifiedCarrierPage verifiedCarrierPage(Shop shop,String number)throws IOException {
        var group=membership.get(number);if(group==null)throw new IOException("OZON_LABEL_BATCH_SELECTION_CHANGED");
        if(!isolated.contains(group)&&!loaded.contains(group))load(shop,group);
        if(isolated.contains(group))return null;
        var selected=pages.get(number);
        // Multi-page/product-page filtering keeps the existing isolated-file path.
        if(selected==null||selected.size()!=1)return null;
        return new OzonPrintBundleService.VerifiedCarrierPage(documents.get(number),selected.getFirst());
    }
    private void load(Shop shop,List<String> group)throws IOException {
        Path file=Files.createTempFile("vncode-ozon-label-batch-",".pdf");temporaryFiles.add(file);
        var job=new OzonLabelRepository().find(shop.getId(),group.getFirst());
        boolean legacy=group.size()==1&&job!=null&&job.taskId()!=null&&!job.taskId().isBlank()&&!job.taskId().startsWith("v3:");
        if(legacy)service.downloadOfficialPdf(shop,group.getFirst(),file.toFile());else service.downloadOfficialBatchPdf(shop,group,file.toFile());
        var pdf=new PdfDocument(new PdfReader(file.toFile()));
        boolean retained=false;
        try {
            if(legacy)pages.put(group.getFirst(),java.util.stream.IntStream.rangeClosed(1,pdf.getNumberOfPages()).boxed().toList());
            else {
                var receipt=new OzonLabelRepository().find(shop.getId(),group.getFirst());
                var members=new OzonLabelRepository().taskPostings(shop.getId(),receipt.taskId());
                var postings=new OzonPostingRepository().findMany(shop.getId(),members);
                var detected=new HashMap<String,List<Integer>>();boolean verified=true;
                try(var identities=new OzonLabelPageIdentity(file.toFile(),pdf,members,postings)){
                    for(int page=1;page<=pdf.getNumberOfPages();page++){
                        if(Thread.currentThread().isInterrupted())throw new IOException("OZON_LABEL_BATCH_INTERRUPTED");
                        var matches=identities.postingNumbers(page);
                        if(matches.size()!=1){verified=false;break;}
                        String number=matches.iterator().next();
                        if(group.contains(number))detected.computeIfAbsent(number,k->new ArrayList<>()).add(page);
                    }
                }
                if(!verified||group.stream().anyMatch(number->!detected.containsKey(number))){
                    isolated.add(group);return;
                }
                pages.putAll(detected);
            }
            group.forEach(number->documents.put(number,pdf));loaded.add(group);retained=true;
        } finally {if(!retained)pdf.close();}
    }
    @Override public void close() {
        for(var pdf:new HashSet<>(documents.values()))try{pdf.close();}catch(RuntimeException ignored){}
        for(var file:temporaryFiles)try{Files.deleteIfExists(file);}catch(IOException ignored){}
        documents.clear();pages.clear();temporaryFiles.clear();loaded.clear();isolated.clear();
    }
}
