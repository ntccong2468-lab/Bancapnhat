package com.vncode.app.integration.ozon;

import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.vncode.app.models.Shop;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Download each shared task once. Page identity is read from the PDF, never inferred from request order. */
final class OzonBatchLabelDownloader implements OzonPrintBundleService.OfficialLabelDownloader {
    private final OzonLabelService service;
    private final Map<String,List<String>> membership=new LinkedHashMap<>();
    private final Set<List<String>> loaded=new HashSet<>();
    private final Map<String,List<Integer>> pages=new HashMap<>();
    private final Map<String,PdfDocument> documents=new HashMap<>();
    private final List<Path> temporaryFiles=new ArrayList<>();
    OzonBatchLabelDownloader(OzonLabelService service,Shop shop,List<String> numbers) {
        this.service=service;
        var known=new LinkedHashMap<String,List<String>>();var fresh=new ArrayList<String>();var repo=new OzonLabelRepository();
        for(String number:numbers) {
            var job=repo.find(shop.getId(),number);
            if(job==null||job.taskId()==null||job.taskId().isBlank())fresh.add(number);
            else known.computeIfAbsent(job.taskId().startsWith("v3:")?job.taskId():"legacy:"+number,k->new ArrayList<>()).add(number);
        }
        for(int offset=0;offset<fresh.size();offset+=1000)known.put("fresh:"+offset,new ArrayList<>(fresh.subList(offset,Math.min(offset+1000,fresh.size()))));
        known.values().forEach(group->{var frozen=List.copyOf(group);frozen.forEach(number->membership.put(number,frozen));});
    }
    @Override public File download(Shop shop,String number,File target)throws IOException {
        var group=membership.get(number);if(group==null)throw new IOException("OZON_LABEL_BATCH_SELECTION_CHANGED");
        if(!loaded.contains(group))load(shop,group);
        var selected=pages.get(number);if(selected==null||selected.isEmpty())throw new IOException("OZON_LABEL_BATCH_IDENTITY_UNVERIFIED");
        try(var output=new PdfDocument(new PdfWriter(target))) { documents.get(number).copyPagesTo(selected,output); }
        return target;
    }
    private void load(Shop shop,List<String> group)throws IOException {
        Path file=Files.createTempFile("vncode-ozon-label-batch-",".pdf");temporaryFiles.add(file);
        var job=new OzonLabelRepository().find(shop.getId(),group.getFirst());
        boolean legacy=job!=null&&job.taskId()!=null&&!job.taskId().isBlank()&&!job.taskId().startsWith("v3:");
        if(legacy)service.downloadOfficialPdf(shop,group.getFirst(),file.toFile());else service.downloadOfficialBatchPdf(shop,group,file.toFile());
        var pdf=new PdfDocument(new PdfReader(file.toFile()));
        boolean retained=false;
        try {
            if(legacy)pages.put(group.getFirst(),java.util.stream.IntStream.rangeClosed(1,pdf.getNumberOfPages()).boxed().toList());
            else {
                var identities=new LinkedHashMap<String,Pattern>();
                group.forEach(number->identities.put(number,Pattern.compile("(?<![A-Za-z0-9-])"+Pattern.quote(number)+"(?![A-Za-z0-9-])")));
                for(int page=1;page<=pdf.getNumberOfPages();page++) {
                    if(Thread.currentThread().isInterrupted())throw new IOException("OZON_LABEL_BATCH_INTERRUPTED");
                    String text=PdfTextExtractor.getTextFromPage(pdf.getPage(page));
                    var matches=identities.entrySet().stream().filter(entry->entry.getValue().matcher(text).find()).map(Map.Entry::getKey).toList();
                    if(matches.size()>1)throw new IOException("OZON_LABEL_BATCH_IDENTITY_UNVERIFIED");
                    if(matches.isEmpty()&&!Pattern.compile("(?<![0-9])[0-9]{3,}-[0-9]{3,}-[0-9]+(?![0-9])").matcher(text).find())
                        throw new IOException("OZON_LABEL_BATCH_IDENTITY_UNVERIFIED");
                    // A resumed shared task can contain unselected postings. Their pages are omitted.
                    if(matches.size()==1)pages.computeIfAbsent(matches.getFirst(),k->new ArrayList<>()).add(page);
                }
                if(group.stream().anyMatch(number->!pages.containsKey(number)))throw new IOException("OZON_LABEL_BATCH_IDENTITY_UNVERIFIED");
            }
            group.forEach(number->documents.put(number,pdf));loaded.add(group);retained=true;
        } finally {if(!retained)pdf.close();}
    }
    @Override public void close() {
        for(var pdf:new HashSet<>(documents.values()))try{pdf.close();}catch(RuntimeException ignored){}
        for(var file:temporaryFiles)try{Files.deleteIfExists(file);}catch(IOException ignored){}
        documents.clear();pages.clear();temporaryFiles.clear();
    }
}
