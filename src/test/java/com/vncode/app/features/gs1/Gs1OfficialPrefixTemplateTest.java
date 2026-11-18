package com.vncode.app.features.gs1;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Generated files are checked locally, not submitted to GS1 or signed with a certificate. */
class Gs1OfficialPrefixTemplateTest {
    private final Gs1WordPrefixService words=new Gs1WordPrefixService();
    private final Gs1WordPrefixService.Fields fields=new Gs1WordPrefixService.Fields(
            "ООО ТЕСТОВЫЙ ПРОФИЛЬ","7707083893",List.of("4601111","4602222"),LocalDate.of(2026,11,17),"ИВАНОВ ИВАН ИВАНОВИЧ");
    private static final List<String> REQUIRED_WORDING=List.of(
            "Форма 01.04.01","НА БЛАНКЕ ПРЕДПРИЯТИЯ","Генеральному директору","Ассоциации автоматической",
            "идентификации «ЮНИСКАН/ГС1 РУС»","г-ну Насонову Г.К.","ЗАЯВЛЕНИЕ",
            "(наименование организации/предприятия, номер предприятия в ЮНИСКАН/ГС1 РУС)",
            "просит предоставить дополнительный префикс в связи с окончанием ранее присвоенного диапазона.",
            "РУКОВОДИТЕЛЬ","Фамилия, имя, отчество","подпись","М.П.",
            "Документ может быть направлен: по ЭДО (Диадок, СБИС), по e-mail mail@gs1ru.org","01.04.01 – 2010.07.02");

    @Test void realDefaultUsesExistingStrictInspectorAndHasOnlyThreeInertParts()throws Exception{
        var template=Gs1OfficialPrefixTemplate.defaultTemplate();var description=words.inspect(template);
        assertEquals(21,description.paragraphs());assertEquals(5,description.fields().size());
        var parts=parts(template);assertEquals(Set.of("[Content_Types].xml","_rels/.rels","word/document.xml"),parts.keySet());
        String relations=new String(parts.get("_rels/.rels"),StandardCharsets.UTF_8);
        assertFalse(relations.contains("TargetMode"));assertFalse(relations.contains("mailto:"));
        String xml=new String(parts.get("word/document.xml"),StandardCharsets.UTF_8);
        for(String active:List.of("<w:drawing","<w:pict","<w:hyperlink","<w:headerReference","<w:footerReference","<!DOCTYPE"))assertFalse(xml.contains(active));
    }
    @Test void defaultRetainsOfficialWordingAndFieldOrderRatherThanInventingAReplacementLetter()throws Exception{
        try(var document=new XWPFDocument(new ByteArrayInputStream(Gs1OfficialPrefixTemplate.defaultTemplate()))){
            String text=document.getParagraphs().stream().map(p->p.getText()).reduce("",(a,b)->a+"\n"+b);
            for(String required:REQUIRED_WORDING)assertTrue(text.contains(required),required);
            assertTrue(text.indexOf("ЗАЯВЛЕНИЕ")<text.indexOf("${companyName}"));
            assertTrue(text.indexOf("${companyName}")<text.indexOf("просит предоставить"));
            assertTrue(text.indexOf("просит предоставить")<text.indexOf("${signerName}"));
            assertTrue(text.indexOf("${signerName}")<text.indexOf("М.П."));
            assertFalse(text.contains("ОБРАЗЕЦ"));assertTrue(text.contains("${date}"));
        }
    }
    @Test void fillsRealDocxWithEveryReviewedProfileFieldAndPreservesOriginalNotice()throws Exception{
        byte[] filled=words.fillWord(Gs1OfficialPrefixTemplate.defaultTemplate(),fields);
        try(var document=new XWPFDocument(new ByteArrayInputStream(filled))){
            String text=document.getParagraphs().stream().map(p->p.getText()).reduce("",(a,b)->a+"\n"+b);
            for(String value:List.of(fields.legalName(),fields.inn(),fields.signerName(),"4601111, 4602222","«17» ноября 2026 г."))assertTrue(text.contains(value),value);
            for(String required:REQUIRED_WORDING)assertTrue(text.contains(required),required);
            assertFalse(text.contains("${"));
        }
    }
    @Test void bothCapacityChoicesRetainActualFormPdfAndExactReviewedMailQuantity()throws Exception{
        var profile=new Gs1PrefixRequestModels.Profile(fields.legalName(),fields.inn(),fields.gs1Codes(),
                "seller@fixture.ru","+70000000000",fields.signerName(),"",List.of());
        for(var quantity:Gs1PrefixRequestModels.Quantity.values()){
            var preview=words.previewPdf(Gs1OfficialPrefixTemplate.defaultTemplate(),fields);
            try(var document=Loader.loadPDF(preview.pdf())){
                assertEquals(1,document.getNumberOfPages());assertNull(document.getDocumentCatalog().getOpenAction());
                String text=new PDFTextStripper().getText(document).replaceAll("\\s+"," ");
                for(String value:List.of(fields.legalName(),fields.inn(),fields.signerName(),"4601111, 4602222","ЗАЯВЛЕНИЕ","М.П.","01.04.01 – 2010.07.02"))assertTrue(text.contains(value),value);
                assertFalse(text.contains("${"));
                assertTrue(Gs1PrefixRequestService.body(profile,quantity,"").contains("генерации "+quantity.value()+" кодов GTIN"));
                assertEquals(595.35f,preview.width(),.1);assertEquals(842f,preview.height(),.1);
                assertTrue(preview.signature().x()>0&&preview.signature().y()>0&&preview.signature().width()>.1);
            }
        }
    }
    @Test void signedPdfContainsTransientDrawnInkOnlyInsideTheActualSignatureField()throws Exception{
        byte[] template=Gs1OfficialPrefixTemplate.defaultTemplate();var preview=words.previewPdf(template,fields);
        try(var ink=new Gs1SignatureInk()){
            ink.start(.05,.7);ink.append(.25,.2);ink.append(.5,.8);ink.append(.95,.3);ink.end();
            var attachment=words.signedPdf(template,fields,ink);
            try(var unsigned=Loader.loadPDF(preview.pdf());var signed=Loader.loadPDF(attachment.bytes())){
                assertEquals(1,signed.getNumberOfPages());assertEquals("application/pdf",attachment.type());
                var before=new PDFRenderer(unsigned).renderImage(0);var after=new PDFRenderer(signed).renderImage(0);
                int changed=0,outside=0;var area=preview.signature();
                for(int y=0;y<before.getHeight();y++)for(int x=0;x<before.getWidth();x++)if(before.getRGB(x,y)!=after.getRGB(x,y)){
                    changed++;double nx=(double)x/before.getWidth(),ny=1-(double)y/before.getHeight();
                    if(nx<area.x()-.004||nx>area.x()+area.width()+.004||ny<area.y()-.004||ny>area.y()+area.height()+.004)outside++;
                }
                assertTrue(changed>30);assertEquals(0,outside);
                assertTrue(new PDFTextStripper().getText(signed).contains(fields.signerName()));
            }
            assertFalse(ink.isEmpty(),"Dialog owns and clears the transient signature, not the default template");
        }
    }
    @Test void provenanceDistinguishesOfficialSourceRevisionFromIndependentGeneratedBytes()throws Exception{
        var source=Gs1OfficialPrefixTemplate.metadata();
        assertEquals("01.04.01",source.formId());assertEquals("2010.07.02",source.sourceRevision());
        assertEquals("https://gs1ru.org/downloads/documents/F01_0401.docx",source.sourceUrl());
        assertEquals("79406c129a9efa6a32220c18228a0249d7f471741235e6846400ea35404429ed",source.sourceSha256());
        assertEquals(LocalDate.of(2026,11,17),source.retrievedOn());assertTrue(source.adaptation().contains("Independent safe layout"));
        assertNotEquals(source.sourceSha256(),words.inspect(Gs1OfficialPrefixTemplate.defaultTemplate()).sha256());
    }
    @Test void callersReceiveIndependentDeterministicTemplateCopies()throws Exception{
        byte[] first=Gs1OfficialPrefixTemplate.defaultTemplate(),second=Gs1OfficialPrefixTemplate.defaultTemplate();
        assertArrayEquals(first,second);first[0]=0;assertEquals('P',second[0]);
        assertArrayEquals(second,Gs1OfficialPrefixTemplate.defaultTemplate());
    }
    private static Map<String,byte[]> parts(byte[] bytes)throws Exception{
        var result=new LinkedHashMap<String,byte[]>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)result.put(entry.getName(),zip.readAllBytes());
        }
        return result;
    }
}
