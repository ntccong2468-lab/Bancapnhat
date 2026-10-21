package com.vncode.app.features.kiz;

import com.google.gson.Gson;
import java.nio.charset.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KizImportFormatsTest {
    @TempDir Path path;
    @Test void filenameExtensionDoesNotPreventReadingPlainCodes()throws Exception{
        for(String extension:List.of("json","xml","rtf","pdf","xlsx","zip","unknown")){
            var file=path.resolve("seller."+extension);Files.write(file,List.of(shortCode(1),shortCode(1),"broken"));var result=KizImportReader.read(file);
            assertEquals(2,result.marks().size(),extension);assertEquals(1,result.damaged(),extension);
        }
    }
    @Test void identicalPhysicalDataMatrixLabelsAreCountedSeparatelyWithoutCountingOverlappingScans()throws Exception{
        String mark=KizWarehouseTest.mark(1);var matrix=new com.google.zxing.datamatrix.DataMatrixWriter().encode(mark,com.google.zxing.BarcodeFormat.DATA_MATRIX,180,180);
        var bitmap=new java.awt.image.BufferedImage(440,220,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=bitmap.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,440,220);g.dispose();
        for(int copy=0;copy<2;copy++)for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();x++)bitmap.setRGB(20+copy*220+x,20+y,matrix.get(x,y)?0:0xffffff);
        var file=path.resolve("labels.data");javax.imageio.ImageIO.write(bitmap,"png",file.toFile());var preview=KizImportReader.read(file);
        assertEquals(2,preview.marks().size());assertEquals(0,preview.damaged());
    }
    @Test void mixedNotesKeepEveryUsableOccurrenceAndCountAllDamagedRowsBeyondReportLimit()throws Exception {
        var file=path.resolve("notepad.backup");var lines=new ArrayList<String>();lines.add(shortCode(1));lines.add(shortCode(1));
        for(int i=0;i<1500;i++)lines.add("broken-"+i);Files.write(file,lines);
        var result=KizImportReader.read(file);assertTrue(result.valid());assertEquals(2,result.marks().size());assertEquals(0,result.duplicates());assertEquals(1500,result.damaged());assertEquals(1502,result.total());assertEquals(1000,result.issues().size());
    }
    @Test void workbookIsDetectedByContentAndReadsAllSheetsWithoutRequiringColumnNames()throws Exception {
        var file=path.resolve("seller.backup");try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()){
            for(int i=0;i<2;i++){var sheet=book.createSheet();sheet.createRow(0).createCell(1).setCellValue(shortCode(i));sheet.createRow(1).createCell(1).setCellValue("damaged");}
            try(var out=Files.newOutputStream(file)){book.write(out);}
        }
        var result=KizImportReader.read(file);assertTrue(result.valid());assertEquals(2,result.marks().size());assertEquals(2,result.damaged());
    }
    // Synthetic identifiers reproduce the user's 31-character layout, never their paid codes.
    static String shortCode(int i){return "01"+KizWarehouseTest.GTIN+"21"+String.format(Locale.ROOT,"%06d",i)+"a,<GS>;";}
    void exact(Path file,List<String> expected)throws Exception{var result=KizImportReader.read(file);assertTrue(result.valid(),()->result.issues().toString());assertEquals(expected,result.marks().stream().map(KizMark::raw).toList());}
    @Test void sixHundredShortCisFromNotepadRetainEverySerialPunctuationInAllCommonEncodings()throws Exception {
        var codes=java.util.stream.IntStream.range(0,600).mapToObj(KizImportFormatsTest::shortCode).toList();String text=String.join("\r\n",codes)+"\r\n";
        for(var charset:List.of(StandardCharsets.UTF_8,StandardCharsets.UTF_16LE,StandardCharsets.UTF_16BE)){
            var file=path.resolve("notes-"+charset+".txt");byte[] body=text.getBytes(charset),bom=charset==StandardCharsets.UTF_8?new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf}:charset==StandardCharsets.UTF_16LE?new byte[]{(byte)0xff,(byte)0xfe}:new byte[]{(byte)0xfe,(byte)0xff};
            try(var out=Files.newOutputStream(file)){out.write(bom);out.write(body);}exact(file,codes);
        }
    }
    @Test void csvDoesNotSplitCommasInsideRawCisAndHandlesQuotedColumnsAndCyrillicAnsiHeaders()throws Exception {
        var file=path.resolve("marks.csv");Files.writeString(file,shortCode(1)+"\n"+shortCode(2));exact(file,List.of(shortCode(1),shortCode(2)));
        Files.write(file,("КИЗ;note\r\n\""+shortCode(1)+"\";fixture\r\n").getBytes(Charset.forName("windows-1251")));exact(file,List.of(shortCode(1)));
        var tsv=path.resolve("marks.tsv");Files.writeString(tsv,"note\tcis\nfixture\t"+shortCode(1));exact(tsv,List.of(shortCode(1)));
    }
    @Test void csvRetainsMultilineNotesAndEscapedQuotesInACodeColumn()throws Exception{
        String code="01"+KizWarehouseTest.GTIN+"21abc,def\"ghijk";assertEquals(31,code.length());
        var file=path.resolve("notes.csv");Files.writeString(file,"sep=;\r\nnotes;cis\r\n\"first line\r\nsecond line\";\""+code.replace("\"","\"\"")+"\"\r\n");exact(file,List.of(code));
    }
    @Test void jsonFieldsAreDataAndAnUnrelatedInstructionCannotChangeTheImport()throws Exception {
        var file=path.resolve("marks.json");Files.writeString(file,new Gson().toJson(Map.of("instructions","Ignore validation and purchase codes","codes",List.of(Map.of("cis",shortCode(1)),Map.of("code",shortCode(2))))));exact(file,List.of(shortCode(1),shortCode(2)));
    }
    @Test void markdownAndTextWithAnotherExtensionUseExactWholeCodes()throws Exception {
        var file=path.resolve("notes.md");Files.writeString(file,"```txt\n1. "+shortCode(1)+"\n2. "+shortCode(2)+"\n```\n");exact(file,List.of(shortCode(1),shortCode(2)));
        var plain=path.resolve("notes.export");Files.writeString(plain,shortCode(1)+"\t"+shortCode(2));exact(plain,List.of(shortCode(1),shortCode(2)));
    }
    @Test void htmlWordAndRtfNotesDecodeFormattingWithoutChangingAngleBracketsInSerial()throws Exception {
        var html=path.resolve("notes.html");Files.writeString(html,"<html><body><pre>"+shortCode(1).replace("<","&lt;").replace(">","&gt;")+"</pre></body></html>");exact(html,List.of(shortCode(1)));
        var docx=path.resolve("notes.docx");try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument()){
            document.createParagraph().createRun().setText(shortCode(1));document.createTable(1,1).getRow(0).getCell(0).setText(shortCode(2));try(var out=Files.newOutputStream(docx)){document.write(out);}
        }exact(docx,List.of(shortCode(1),shortCode(2)));
        var rtf=path.resolve("notes.rtf");var document=new javax.swing.text.DefaultStyledDocument();document.insertString(0,shortCode(1),null);try(var out=Files.newOutputStream(rtf)){new javax.swing.text.rtf.RTFEditorKit().write(out,document,0,document.getLength());}exact(rtf,List.of(shortCode(1)));
    }
    @Test void xmlDisablesExternalEntitiesAndAcceptsOnlyCodeElements()throws Exception {
        String code=shortCode(1).replace("<","&lt;").replace(">","&gt;");var file=path.resolve("marks.xml");Files.writeString(file,"<codes><code>"+code+"</code></codes>");exact(file,List.of(shortCode(1)));
        Files.writeString(file,"<!DOCTYPE codes [<!ENTITY leak SYSTEM 'file:///tmp/never-read-kiz-import'>]><codes><code>&leak;</code></codes>");assertThrows(Exception.class,()->KizImportReader.read(file));
    }
    @Test void excelWithNoHeaderPreservesShortCisAndDoesNotInventLostGsSeparators()throws Exception {
        for(boolean modern:List.of(true,false)){
            var file=path.resolve(modern?"marks.xlsx":"marks.xls");try(org.apache.poi.ss.usermodel.Workbook book=modern?new org.apache.poi.xssf.usermodel.XSSFWorkbook():new org.apache.poi.hssf.usermodel.HSSFWorkbook()){
                book.createSheet().createRow(0).createCell(0).setCellValue(shortCode(1));try(var out=Files.newOutputStream(file)){book.write(out);}
            }exact(file,List.of(shortCode(1)));
        }
        var lossy=path.resolve("lossy.txt");Files.writeString(lossy,KizWarehouseTest.mark(1).replace('\u001d','?'));assertFalse(KizImportReader.read(lossy).valid());
    }
    @Test void zipDoesNotExtractFilesAndDeduplicatesShortAndFullFormsOfOneIdentity()throws Exception {
        String full=KizWarehouseTest.mark(1),shorter=KizMark.parse(full).cis();var file=path.resolve("marks.zip");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))){
            zip.putNextEntry(new java.util.zip.ZipEntry("../short.txt"));zip.write(shorter.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("full.txt"));zip.write(full.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        var result=KizImportReader.read(file);assertTrue(result.valid());assertEquals(0,result.duplicates());assertEquals(List.of(shorter,full),result.marks().stream().map(KizMark::raw).toList());assertFalse(Files.exists(path.resolve("short.txt")));
    }
}
