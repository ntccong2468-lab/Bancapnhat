package com.vncode.app.integration.ozon;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Reads the human-readable carrier number from the exact posting's official label, never page order. */
final class OzonCarrierLabelNumbers {
    private static final Pattern NUMBER=Pattern.compile("(?<![\\p{L}\\p{N}-])(?:[A-Za-z]{1,6})?[0-9]{6,}(?:[\\h]+[0-9]{4})?(?![\\p{L}\\p{N}-])");
    private OzonCarrierLabelNumbers() {}

    static Map<String,String> read(com.vncode.app.models.Shop shop,File file,PdfDocument pdf,List<Integer> pages,Map<String,OzonPostingDto> postings) throws IOException {
        try{return read(file,pdf,pages,OzonPostingTrackingStore.withLabelNumbers(shop,postings));}
        catch(java.sql.SQLException error){throw new IOException("OZON_TRACKING_READ_FAILED",error);}
    }

    static Map<String,String> read(File file,PdfDocument pdf,List<Integer> pages,Map<String,OzonPostingDto> postings) throws IOException {
        if(postings.isEmpty())return Map.of();
        Map<String,String> found=new LinkedHashMap<>();Set<String> conflicts=new HashSet<>();
        try(var identities=new OzonLabelPageIdentity(file,pdf,List.copyOf(postings.keySet()),postings)){
            for(int page:pages){
                if(Thread.currentThread().isInterrupted())throw new IOException("OZON_LABEL_BATCH_INTERRUPTED");
                var text=new LabelText();
                try{new PdfCanvasProcessor(text).processPageContent(pdf.getPage(page));}
                catch(RuntimeException unreadable){continue;}
                String visible=OzonLabelPageIdentity.normalize(text.getResultantText());
                var candidates=new LinkedHashMap<String,Float>();var matcher=NUMBER.matcher(visible);
                while(matcher.find()){
                    String number=compact(matcher.group());
                    if(number.toUpperCase(Locale.ROOT).startsWith("OZN"))continue; // Product barcode, never carrier number.
                    float size=text.parts.stream().filter(part->!compact(part.text()).isEmpty()&&number.contains(compact(part.text())))
                            .map(Part::size).max(Float::compare).orElse(0f);
                    candidates.merge(number,size,Math::max);
                }
                if(candidates.isEmpty())continue;
                Set<String> owners=identities.matches(visible);
                if(owners.isEmpty()&&postings.size()==1)owners=postings.keySet();
                if(owners.isEmpty()&&file!=null)owners=identities.postingNumbers(page);
                if(owners.size()!=1)continue;
                String postingNumber=owners.iterator().next();var posting=postings.get(postingNumber);
                if(posting==null)continue;
                Set<String> officialIds=new HashSet<>(List.of(compact(posting.lowerBarcode()).toLowerCase(Locale.ROOT),compact(posting.upperBarcode()).toLowerCase(Locale.ROOT),compact(posting.scanit()).toLowerCase(Locale.ROOT)));
                // Numeric text could be a GTIN/order ID. Accept it only if the carrier metadata agrees.
                candidates.keySet().removeIf(number->number.equals(compact(postingNumber))
                        ||number.equals(compact(posting.orderId()))||number.equals(compact(posting.orderNumber()))
                        ||!officialIds.contains(number.toLowerCase(Locale.ROOT))&&!number.matches("[A-Za-z]{1,6}[0-9]{7,}"));
                if(candidates.isEmpty())continue;
                // The API's native scanit wins over an article or another large printed number.
                String nativeNumber=compact(posting.scanit());
                if(candidates.containsKey(nativeNumber))candidates.keySet().removeIf(number->!number.equals(nativeNumber));
                float largest=Collections.max(candidates.values());
                var largestNumbers=candidates.entrySet().stream().filter(entry->Math.abs(entry.getValue()-largest)<.1f).map(Map.Entry::getKey).toList();
                if(largestNumbers.size()!=1)continue;
                String number=largestNumbers.getFirst(),previous=found.putIfAbsent(postingNumber,number);
                if(previous!=null&&!previous.equals(number))conflicts.add(postingNumber);
            }
        }
        conflicts.forEach(found::remove);
        return Map.copyOf(found);
    }
    private static String compact(String text){return Objects.requireNonNullElse(text,"").replaceAll("[\\h]+","").strip();}
    private record Part(String text,float size) {}
    private static final class LabelText extends LocationTextExtractionStrategy {
        final List<Part> parts=new ArrayList<>();
        @Override public void eventOccurred(IEventData data,EventType type){
            super.eventOccurred(data,type);
            if(data instanceof TextRenderInfo text)parts.add(new Part(text.getText(),text.getFontSize()));
        }
    }
}
