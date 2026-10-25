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
        String alternatives=owners.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        identifiers=Pattern.compile("(?<![\\p{L}\\p{N}-])(?:"+alternatives+")(?![\\p{L}\\p{N}-])");
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
        var box=raster.getPage(page-1).getCropBox();double scale=Math.max(1d,raster.getPage(page-1).getUserUnit())*DPI/72d;
        double pixels=Math.ceil(box.getWidth()*scale)*Math.ceil(box.getHeight()*scale);
        if(!Double.isFinite(pixels)||pixels<=0||pixels>2_000_000)return Set.of();
        var image=renderer.renderImageWithDPI(page-1,DPI,ImageType.GRAY);
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
    private Set<String> matches(String text){
        var result=new LinkedHashSet<String>();var matcher=identifiers.matcher(normalize(text));
        while(matcher.find())result.addAll(owners.getOrDefault(matcher.group(),Set.of()));return result;
    }
    static String normalize(String text){
        return Normalizer.normalize(Objects.requireNonNullElse(text,""),Normalizer.Form.NFKC)
                .replaceAll("[\\u2010-\\u2015\\u2212\\u00ad]","-").replaceAll("[\\u200b\\ufeff]","")
                .replaceAll("\\s*-\\s*","-").strip();
    }
    @Override public void close()throws IOException{if(raster!=null)raster.close();}
}
