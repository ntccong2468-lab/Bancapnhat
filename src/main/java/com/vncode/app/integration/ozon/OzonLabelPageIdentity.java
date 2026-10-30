package com.vncode.app.integration.ozon;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import java.io.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.*;

/** Exact posting/transport identifiers only. SKU, product barcode and page order are not identities. */
final class OzonLabelPageIdentity implements AutoCloseable {
    private static final int DPI=203;
    // For these identifiers, a match bounded by the same character class used by
    // the legacy pattern must be an entire token. Looking it up avoids testing
    // thousands of alternatives on every page of a large label PDF.
    private static final Pattern TOKEN=Pattern.compile("[\\p{L}\\p{N}-]+");
    private static final Map<DecodeHintType,Object> HINTS=Map.of(DecodeHintType.TRY_HARDER,true,
            DecodeHintType.POSSIBLE_FORMATS,List.of(BarcodeFormat.CODE_128,BarcodeFormat.QR_CODE,BarcodeFormat.DATA_MATRIX));
    private final Map<String,Set<String>> owners=new LinkedHashMap<>();
    private final Pattern identifiers;
    private final File file;
    private final PdfDocument source;
    private PDDocument raster;
    private PDFRenderer renderer;

    OzonLabelPageIdentity(File file,PdfDocument source,List<String> numbers,Map<String,OzonPostingDto> postings){
        this.file=file;this.source=source;
        for(String number:numbers){
            add(number,number);var posting=postings.get(number);
            if(posting!=null){add(posting.lowerBarcode(),number);add(posting.upperBarcode(),number);}
        }
        // An identifier containing other characters may overlap another identifier
        // (e.g. ABC and ABC.); retain the longest-first, non-overlapping legacy
        // matcher for the whole set in that case.
        if(owners.keySet().stream().allMatch(value->TOKEN.matcher(value).matches())) identifiers=null;
        else {
            String alternatives=owners.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                    .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
            identifiers=Pattern.compile("(?<![\\p{L}\\p{N}-])(?:"+alternatives+")(?![\\p{L}\\p{N}-])");
        }
    }
    private void add(String identifier,String number){
        String value=normalize(identifier);if(!value.isBlank())owners.computeIfAbsent(value,k->new LinkedHashSet<>()).add(number);
    }
    Set<String> postingNumbers(int page)throws IOException{
        Set<String> result;
        try{result=matches(PdfTextExtractor.getTextFromPage(source.getPage(page)));}
        catch(RuntimeException unsupportedText){result=new LinkedHashSet<>();}
        if(!result.isEmpty())return result;
        if(Thread.currentThread().isInterrupted())throw new IOException("OZON_LABEL_BATCH_INTERRUPTED");
        try{return decodedPostings(page,result);}
        catch(IOException|RuntimeException unsupportedPage){
            if(Thread.currentThread().isInterrupted())throw new IOException("OZON_LABEL_BATCH_INTERRUPTED",unsupportedPage);
            return Set.of();
        }
    }
    private Set<String> decodedPostings(int page,Set<String> result)throws IOException{
        if(raster==null){raster=Loader.loadPDF(file);renderer=new PDFRenderer(raster);renderer.setSubsamplingAllowed(true);}
        var sourcePage=raster.getPage(page-1);var box=sourcePage.getCropBox();
        double unit=Math.max(1d,sourcePage.getUserUnit());
        double width=box.getWidth()*unit/72d,height=box.getHeight()*unit/72d;
        double area=width*height;
        if(!Double.isFinite(area)||width<=0||height<=0||area<=0)return Set.of();
        // A full A4 label at 203 DPI exceeds the allocation limit, although its
        // transport barcode can often be decoded at a lower resolution. Keep
        // the same bounded raster memory while attempting such pages instead of
        // immediately forcing the entire batch to be downloaded one at a time.
        double dpi=Math.min(DPI,Math.sqrt(1_950_000d/area));
        if(!Double.isFinite(dpi)||dpi<90)return Set.of();
        double pixels=Math.ceil(width*dpi)*Math.ceil(height*dpi);
        if(!Double.isFinite(pixels)||pixels<=0||pixels>2_000_000)return Set.of();
        var image=renderer.renderImageWithDPI(page-1,(float)dpi,ImageType.GRAY);
        try{
            var bitmap=new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            decode(bitmap,result);
            if(result.isEmpty()&&bitmap.isRotateSupported())decode(bitmap.rotateCounterClockwise(),result);
            return Set.copyOf(result);
        }finally{image.flush();}
    }
    private void decode(BinaryBitmap bitmap,Set<String> result){
        try{for(var barcode:new GenericMultipleBarcodeReader(new MultiFormatReader()).decodeMultiple(bitmap,HINTS))result.addAll(matches(barcode.getText()));}
        catch(NotFoundException unreadable){/* The caller uses a single-posting task instead. */}
    }
    Set<String> matches(String text){
        var result=new LinkedHashSet<String>();var matcher=(identifiers==null?TOKEN:identifiers).matcher(normalize(text));
        while(matcher.find())result.addAll(owners.getOrDefault(matcher.group(),Set.of()));return result;
    }
    static String normalize(String text){
        return Normalizer.normalize(Objects.requireNonNullElse(text,""),Normalizer.Form.NFKC)
                .replaceAll("[\\u2010-\\u2015\\u2212\\u00ad]","-").replaceAll("[\\u200b\\ufeff]","")
                .replaceAll("\\s*-\\s*","-").strip();
    }
    @Override public void close()throws IOException{if(raster!=null)raster.close();}
}
