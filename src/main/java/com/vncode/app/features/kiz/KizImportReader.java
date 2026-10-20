package com.vncode.app.features.kiz;

import com.google.gson.*;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.io.*;

/** Imports explicit CIS strings or decoded symbols as data; document contents never become instructions. */
public final class KizImportReader {
    public record Issue(int row,String code){}
    public record Preview(String sha256,List<KizMark> marks,List<Issue> issues,int duplicates,int damaged){
        public Preview(String sha256,List<KizMark> marks,List<Issue> issues,int duplicates){this(sha256,marks,issues,duplicates,issues.size());}
        public Preview{marks=List.copyOf(marks);issues=List.copyOf(issues);}
        public boolean valid(){return !marks.isEmpty();}
        public int total(){return marks.size()+duplicates+damaged;}
        public Map<String,Integer> counts(){var out=new TreeMap<String,Integer>();marks.forEach(m->out.merge(m.gtin(),1,Integer::sum));return Collections.unmodifiableMap(out);}
        @Override public String toString(){return "KizImportPreview[count="+marks.size()+", issues="+issues.size()+"]";}
    }
    private final List<KizMark> marks=new ArrayList<>();
    private final List<Issue> issues=new ArrayList<>();private int duplicates,rows,damaged;
    public static Preview read(Path path)throws Exception {
        long size=Files.size(path);if(size<=0||size>32L*1024*1024)throw new IOException("KIZ_FILE_SIZE_LIMIT");
        byte[] bytes=Files.readAllBytes(path);if(bytes.length>32*1024*1024)throw new IOException("KIZ_FILE_SIZE_LIMIT");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);var reader=new KizImportReader();
        reader.parse(name,bytes,false);
        if(reader.marks.isEmpty()&&reader.issues.isEmpty())reader.issue(0,"KIZ_FILE_EMPTY");
        return new Preview(hash,reader.marks,reader.issues,reader.duplicates,reader.damaged);
    }
    private void parse(String name,byte[] bytes,boolean nested)throws Exception {
        if(bytes.length>=4&&new String(bytes,0,4,StandardCharsets.US_ASCII).equals("%PDF"))pdf(bytes);
        else if(bytes.length>4&&bytes[0]=='P'&&bytes[1]=='K'){
            String kind=zipKind(bytes);
            if(kind.equals("xlsx")){xlsx(bytes);return;}
            if(kind.equals("docx")){docx(bytes);return;}
            if(nested)throw new IOException("KIZ_NESTED_ARCHIVE_UNSUPPORTED");
            archive(bytes);
        }else if(bytes.length>4&&(bytes[0]&255)==0xd0&&(bytes[1]&255)==0xcf)xlsx(bytes);
        else if(isImage(bytes)){
            try(var input=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
                var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("KIZ_IMAGE_INVALID");
                var decoder=readers.next();try{
                    decoder.setInput(input);int count=decoder.getNumImages(true);if(count<1||count>1000)throw new IOException("KIZ_IMAGE_SIZE_LIMIT");
                    for(int frame=0;frame<count;frame++){
                        if((long)decoder.getWidth(frame)*decoder.getHeight(frame)>16_000_000)throw new IOException("KIZ_IMAGE_SIZE_LIMIT");
                        var picture=decoder.read(frame);try{image(picture,frame+1);}finally{picture.flush();}
                    }
                }finally{decoder.dispose();}
            }
        }else{
            String text=text(bytes);
            if(text.stripLeading().startsWith("[")||text.stripLeading().startsWith("{")&&!text.stripLeading().startsWith("{\\rtf"))json(JsonParser.parseString(text));
            else if(text.stripLeading().startsWith("{\\rtf")){
                var document=new javax.swing.text.DefaultStyledDocument();new javax.swing.text.rtf.RTFEditorKit().read(new ByteArrayInputStream(bytes),document,0);plain(document.getText(0,document.getLength()));
            }else if(text.stripLeading().startsWith("<")&&(name.endsWith(".html")||name.endsWith(".htm")||text.stripLeading().toLowerCase(Locale.ROOT).startsWith("<!doctype html")||text.stripLeading().toLowerCase(Locale.ROOT).startsWith("<html"))){
                var kit=new javax.swing.text.html.HTMLEditorKit();var document=kit.createDefaultDocument();kit.read(new StringReader(text),document,0);plain(document.getText(0,document.getLength()));
            }else if(text.stripLeading().startsWith("<"))xml(bytes);
            else if(name.endsWith(".csv"))csv(text,',');
            else if(name.endsWith(".tsv"))csv(text,'\t');
            else plain(text);
        }
    }
    private static boolean isImage(byte[] bytes)throws IOException{
        try(var input=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){return javax.imageio.ImageIO.getImageReaders(input).hasNext();}
    }
    private static String zipKind(byte[] bytes)throws IOException{
        try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))){
            int count=0,expanded=0;for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry()){
                if(++count>100)break;
                if(e.getName().startsWith("xl/"))return "xlsx";
                if(e.getName().startsWith("word/"))return "docx";
                // Do not inflate arbitrary attachments just to identify the container.
                expanded+=zip.readNBytes(32*1024*1024-expanded+1).length;
                if(expanded>32*1024*1024)throw new IOException("KIZ_ARCHIVE_LIMIT");
            }return "zip";
        }
    }
    private static String text(byte[] bytes)throws CharacterCodingException {
        if(bytes.length>=2&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xfe)return StandardCharsets.UTF_16LE.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes,2,bytes.length-2)).toString();
        if(bytes.length>=2&&bytes[0]==(byte)0xfe&&bytes[1]==(byte)0xff)return StandardCharsets.UTF_16BE.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes,2,bytes.length-2)).toString();
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException ansi){text=Charset.forName("windows-1251").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        if(text.indexOf(0)>=0)throw new IllegalArgumentException("KIZ_FILE_FORMAT_UNSUPPORTED");return text.startsWith("\ufeff")?text.substring(1):text;
    }
    private void plain(String text){
        for(String line:text.split("\\R",-1)){
            if(line.isBlank())continue;
            String trimmed=line.strip();if(Set.of("```","```txt","```text","```csv").contains(trimmed))continue;
            if(column(List.of(trimmed))==0)continue;
            String value=trimmed.replaceFirst("^(?:[0-9]+[.)]\\s+|[-*•]\\s+)","");
            try{KizMark.parse(value);add(value);}
            catch(IllegalArgumentException whole){
                var tokens=value.split("[ \\t]+");
                if(tokens.length>1&&Arrays.stream(tokens).anyMatch(KizImportReader::isMark))for(String token:tokens)add(token);else add(value);
            }
        }
    }
    private static boolean isMark(String value){try{KizMark.parse(value);return true;}catch(IllegalArgumentException invalid){return false;}}
    private void docx(byte[] bytes)throws IOException{
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(bytes))){
            for(var p:document.getParagraphs())plain(p.getText());
            for(var table:document.getTables())for(var row:table.getRows())for(var cell:row.getTableCells())plain(cell.getText());
        }
    }
    private void xml(byte[] bytes)throws Exception {
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));var nodes=document.getElementsByTagName("*");
        for(int i=0;i<nodes.getLength();i++){var node=nodes.item(i);if(node.getChildNodes().getLength()==1&&node.getFirstChild().getNodeType()==org.w3c.dom.Node.TEXT_NODE)plain(node.getTextContent());}
    }
    private void archive(byte[] bytes)throws Exception {
        long expanded=0;int entries=0;
        try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))){
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){
                if(entry.isDirectory())continue;if(++entries>100)throw new IOException("KIZ_ARCHIVE_LIMIT");
                String name=entry.getName().toLowerCase(Locale.ROOT);
                var out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                while((n=zip.read(buffer))!=-1){expanded+=n;if(expanded>32L*1024*1024)throw new IOException("KIZ_ARCHIVE_LIMIT");out.write(buffer,0,n);}
                try{parse(name,out.toByteArray(),true);}catch(Exception invalid){
                    if(invalid.getMessage()!=null&&invalid.getMessage().endsWith("LIMIT"))throw invalid;
                    issue(++rows,"KIZ_ARCHIVE_ENTRY_UNREADABLE");
                }
            }
        }
    }
    private void add(String value){
        if(Thread.currentThread().isInterrupted())throw new IllegalStateException("KIZ_IMPORT_CANCELLED");
        if(++rows>100000)throw new IllegalArgumentException("KIZ_CODE_COUNT_LIMIT");if(value==null||value.isBlank())return;
        try{marks.add(KizMark.parse(value));}
        catch(IllegalArgumentException invalid){issue(rows,invalid.getMessage());}
    }
    private void issue(int row,String code){damaged++;if(issues.size()<1000)issues.add(new Issue(row,code));}
    private void json(JsonElement root){
        json(root,0);
    }
    private void json(JsonElement root,int depth){
        if(depth>100)throw new IllegalArgumentException("KIZ_JSON_DEPTH_LIMIT");
        if(root.isJsonArray()){for(var item:root.getAsJsonArray())json(item,depth+1);}
        else if(root.isJsonObject()){
            var o=root.getAsJsonObject();var codeFields=o.keySet().stream().filter(k->column(List.of(k))==0||k.equalsIgnoreCase("codes")).toList();
            if(!codeFields.isEmpty())codeFields.forEach(k->json(o.get(k),depth+1));else o.entrySet().forEach(e->json(e.getValue(),depth+1));
        }else if(root.isJsonPrimitive()&&root.getAsJsonPrimitive().isString())plain(root.getAsString());
        else issue(++rows,"KIZ_JSON_CODE_REQUIRED");
    }
    private void csv(String text,char requested){
        String trimmed=text.strip();String first=trimmed.split("\\R",2)[0];
        if(isMark(first)){plain(text);return;}
        char delimiter=requested;
        if(first.matches("(?i)sep=[,;\\t]")){delimiter=first.charAt(4);trimmed=trimmed.substring(first.length()).stripLeading();}
        else if(requested!='\t'){
            for(char candidate:new char[]{',',';','\t'})if(cells(first,candidate).stream().anyMatch(v->column(List.of(v))==0)){delimiter=candidate;break;}
        }
        var records=records(trimmed,delimiter);if(records.isEmpty())return;
        table(records);
    }
    private void table(List<List<String>> records){
        if(records.isEmpty())return;
        var columns=new TreeSet<Integer>();var header=records.getFirst();
        for(int i=0;i<header.size();i++)if(column(List.of(header.get(i)))==0)columns.add(i);
        int start=columns.isEmpty()?0:1;
        if(columns.isEmpty())for(var row:records)for(int i=0;i<row.size();i++)if(isMark(row.get(i)))columns.add(i);
        if(columns.isEmpty()){for(var row:records)for(var cell:row)if(!cell.isBlank())add(cell);return;}
        for(int i=start;i<records.size();i++)for(int col:columns){var row=records.get(i);if(col<row.size()&&!row.get(col).isBlank())add(row.get(col));}
    }
    private static List<List<String>> records(String text,char delimiter){
        var records=new ArrayList<List<String>>();var row=new ArrayList<String>();var field=new StringBuilder();boolean quoted=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(c=='"'&&(quoted||field.length()==0)){
                if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"'){field.append('"');i++;}else quoted=!quoted;
            }else if(c==delimiter&&!quoted){row.add(field.toString());field.setLength(0);if(row.size()>200)throw new IllegalArgumentException("KIZ_CSV_INVALID");}
            else if((c=='\n'||c=='\r')&&!quoted){
                row.add(field.toString());field.setLength(0);records.add(List.copyOf(row));row.clear();if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;
                if(records.size()>100001)throw new IllegalArgumentException("KIZ_CODE_COUNT_LIMIT");
            }else field.append(c);
        }
        if(quoted)throw new IllegalArgumentException("KIZ_CSV_INVALID");if(!row.isEmpty()||field.length()>0){row.add(field.toString());records.add(List.copyOf(row));}return records;
    }
    private void xlsx(byte[] bytes)throws IOException {
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
            for(var sheet:book){
                if(sheet.getLastRowNum()>100000)throw new IllegalArgumentException("KIZ_CODE_COUNT_LIMIT");
                var records=new ArrayList<List<String>>();
                for(var row:sheet){
                    if(row.getLastCellNum()>200)throw new IllegalArgumentException("KIZ_CODE_COLUMN_LIMIT");
                    var values=new ArrayList<String>();for(int i=0;i<row.getLastCellNum();i++){
                        var cell=row.getCell(i);if(cell==null||cell.getCellType()==CellType.BLANK)values.add("");
                        else if(cell.getCellType()==CellType.STRING)values.add(cell.getStringCellValue());
                        else {issue(++rows,"KIZ_TEXT_CELL_REQUIRED");values.add("");}
                    }records.add(values);
                }table(records);
            }
        }
    }
    private static int column(List<String> headers){int found=-1;for(int i=0;i<headers.size();i++)if(Set.of("cis","code","raw_code","datamatrix","км","киз").contains(headers.get(i).strip().toLowerCase(Locale.ROOT))){if(found>=0)return -1;found=i;}return found;}
    private static List<String> cells(String line,char delimiter){
        var result=new ArrayList<String>();var value=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){value.append('"');i++;}else quoted=!quoted;}else if(c==delimiter&&!quoted){result.add(value.toString());value.setLength(0);}else value.append(c);}
        if(quoted)throw new IllegalArgumentException("KIZ_CSV_INVALID");result.add(value.toString());return result;
    }
    private void pdf(byte[] bytes)throws IOException {
        try(var document=Loader.loadPDF(bytes)){
            if(document.isEncrypted()||document.getNumberOfPages()>1000)throw new IOException("KIZ_PDF_LIMIT");
            var renderer=new PDFRenderer(document);
            for(int page=0;page<document.getNumberOfPages();page++){
                if(Thread.currentThread().isInterrupted())throw new IOException("KIZ_IMPORT_CANCELLED");
                var box=document.getPage(page).getCropBox();if((double)box.getWidth()*box.getHeight()*Math.pow(300d/72,2)>16_000_000)throw new IOException("KIZ_IMAGE_SIZE_LIMIT");
                var image=renderer.renderImageWithDPI(page,300,org.apache.pdfbox.rendering.ImageType.GRAY);try{image(image,page+1);}finally{image.flush();}
            }
        }
    }
    private void image(java.awt.image.BufferedImage image,int page){
        var hints=new EnumMap<DecodeHintType,Object>(DecodeHintType.class);hints.put(DecodeHintType.POSSIBLE_FORMATS,List.of(BarcodeFormat.DATA_MATRIX));hints.put(DecodeHintType.TRY_HARDER,true);
        var found=new ArrayList<Symbol>();
        decode(image,hints,found,0,0);
        // Mixed text/label sheets need cropped detection; retain only decoded symbols, never OCR.
        for(int[] grid:List.of(new int[]{2,1},new int[]{1,2},new int[]{2,2},new int[]{3,3},new int[]{4,4})){
            for(int y=0;y<grid[1];y++)for(int x=0;x<grid[0];x++){
                if(Thread.currentThread().isInterrupted())throw new IllegalStateException("KIZ_IMPORT_CANCELLED");
                int width=image.getWidth(),height=image.getHeight();
                int x0=Math.max(0,(int)((x-.12)*width/grid[0])),x1=Math.min(width,(int)((x+1.12)*width/grid[0]));
                int y0=Math.max(0,(int)((y-.12)*height/grid[1])),y1=Math.min(height,(int)((y+1.12)*height/grid[1]));
                if(x1>x0&&y1>y0)decode(image.getSubimage(x0,y0,x1-x0,y1-y0),hints,found,x0,y0);
            }
        }
        if(found.isEmpty())issue(page,"KIZ_DATAMATRIX_NOT_FOUND");else found.forEach(symbol->add(symbol.raw()));
    }
    private record Symbol(String raw,double x,double y,double radius){}
    private static void decode(java.awt.image.BufferedImage image,Map<DecodeHintType,Object> hints,List<Symbol> found,int offsetX,int offsetY){
        var bitmap=new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        try{for(var item:new GenericMultipleBarcodeReader(new com.google.zxing.datamatrix.DataMatrixReader()).decodeMultiple(bitmap,hints)){
            var points=item.getResultPoints();if(points==null||points.length==0)continue;
            double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=0,maxY=0;
            for(var p:points){minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());maxX=Math.max(maxX,p.getX());maxY=Math.max(maxY,p.getY());}
            var symbol=new Symbol(item.getText(),offsetX+(minX+maxX)/2,offsetY+(minY+maxY)/2,Math.max(4,Math.max(maxX-minX,maxY-minY)/3));
            // The same physical symbol may be found in overlapping crops. Two separate
            // labels containing the same text remain two seller stock occurrences.
            if(found.stream().noneMatch(old->old.raw().equals(symbol.raw())&&Math.hypot(old.x()-symbol.x(),old.y()-symbol.y())<Math.max(old.radius(),symbol.radius())))found.add(symbol);
        }}
        catch(NotFoundException missing){/* Try overlapping areas on the same page. */}
    }
}
