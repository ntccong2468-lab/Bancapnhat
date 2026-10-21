package com.vncode.app.features.gs1;

import com.vncode.app.features.gs1.Gs1WordPrefixService.Fields;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseRuntime;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class Gs1WordPrefixServiceTest {
    @TempDir Path directory;
    private final Gs1WordPrefixService service = new Gs1WordPrefixService();
    private final Fields fields = new Fields("NEW FIXTURE & COMPANY", "7811089030", List.of("4601111", "4602222", "4603333"), LocalDate.of(2027, 12, 31), "NEW FIXTURE SIGNER");

    @Test void fillsAllPersonalFieldsAndPreservesLiteralParagraphsSpacesAndStyles() throws Exception {
        byte[] source = statement(false), unchanged = source.clone();
        var description = service.inspect(source); assertEquals(12, description.paragraphs()); assertEquals("seller-imported-docx", description.sourceType()); assertEquals(64, description.sha256().length());
        byte[] filled = service.fillWord(source, fields); assertArrayEquals(unchanged, source);
        try (var document = new XWPFDocument(new ByteArrayInputStream(filled))) {
            assertEquals(12, document.getParagraphs().size());
            assertEquals("Директору", document.getParagraphs().get(0).getText());
            assertEquals(ParagraphAlignment.RIGHT, document.getParagraphs().get(0).getAlignment());
            assertEquals(ParagraphAlignment.CENTER, document.getParagraphs().get(5).getAlignment()); assertTrue(document.getParagraphs().get(5).getRuns().getFirst().isBold());
            String company = document.getParagraphs().get(6).getText();
            assertEquals(fields.legalName() + " (ИНН " + fields.inn() + ", номер предприятия в ЮНИСКАН/ГС1 РУС: 4601111, 4602222, 4603333) просит предоставить дополнительный префикс в связи с окончанием ранее присвоенного диапазона.", company);
            assertEquals(ParagraphAlignment.BOTH, document.getParagraphs().get(6).getAlignment());
            assertEquals("«31» декабря 2027 г.", document.getParagraphs().get(8).getText());
            assertEquals("NEW FIXTURE SIGNER     ____________________", document.getParagraphs().get(9).getText());
            assertEquals("                              Фамилия, имя, отчество                          подпись", document.getParagraphs().get(10).getText());
            assertEquals("М.П.", document.getParagraphs().get(11).getText());
            assertEquals("Times New Roman", document.getParagraphs().get(9).getRuns().getFirst().getFontFamily()); assertEquals(12d, document.getParagraphs().get(9).getRuns().getFirst().getFontSizeAsDouble());
            String all = document.getParagraphs().stream().map(XWPFParagraph::getText).reduce("", (a, b) -> a + b);
            assertFalse(all.contains("OLD COMPANY")); assertFalse(all.contains("OLD SIGNER")); assertFalse(all.contains("7707083893")); assertFalse(all.contains("4601234"));
        }
    }

    @Test void replacesTokensSplitAcrossWordRunsWithoutDestroyingLiteralRunStyles() throws Exception {
        byte[] source = statement(true);
        var filled = service.fillWord(source, fields);
        try (var document = new XWPFDocument(new ByteArrayInputStream(filled))) {
            var company = document.getParagraphs().get(6); assertTrue(company.getText().startsWith(fields.legalName()));
            assertTrue(company.getRuns().getFirst().isBold()); assertFalse(company.getRuns().getLast().isBold());
            assertTrue(company.getText().contains("4601111, 4602222, 4603333"));
            assertFalse(document.getParagraphs().stream().anyMatch(p -> p.getText().contains("${")));
            assertTrue(document.getParagraphs().get(9).getText().contains(fields.signerName()));
        }
    }

    @Test void keepsLiteralManagerHeadingWhenReplacingSignerName() throws Exception {
        var template = parts(statement(false));
        template.put("word/document.xml", new String(template.get("word/document.xml"), StandardCharsets.UTF_8)
                .replace("OLD SIGNER", "РУКОВОДИТЕЛЬ OLD SIGNER").getBytes(StandardCharsets.UTF_8));
        byte[] filled = service.fillWord(zip(template), fields);
        try (var document = new XWPFDocument(new ByteArrayInputStream(filled))) {
            assertEquals("РУКОВОДИТЕЛЬ NEW FIXTURE SIGNER     ____________________", document.getParagraphs().get(9).getText());
        }
        var preview = service.previewPdf(zip(template), fields);
        try (var document = Loader.loadPDF(preview.pdf())) {
            assertTrue(new PDFTextStripper().getText(document).contains("РУКОВОДИТЕЛЬ NEW FIXTURE SIGNER"));
        }
    }

    @Test void unsignedPreviewUsesRealFilledTextAndSignedPdfAddsInkAtAutomaticSignatureArea() throws Exception {
        byte[] source = statement(false); var preview = service.previewPdf(source, fields);
        assertEquals(PDRectangle.A4.getWidth(), preview.width(), .1); assertEquals(PDRectangle.A4.getHeight(), preview.height(), .1);
        assertTrue(preview.signature().x() > 0 && preview.signature().y() > 0); assertTrue(preview.signature().width() > .1);
        try (var ink = signature()) {
            var attachment = service.signedPdf(source, fields, ink);
            try (var unsigned = Loader.loadPDF(preview.pdf()); var signed = Loader.loadPDF(attachment.bytes())) {
                assertEquals(1, signed.getNumberOfPages()); assertNull(signed.getDocumentCatalog().getOpenAction());
                String text = new PDFTextStripper().getText(signed);
                assertTrue(text.contains(fields.legalName())); assertTrue(text.contains(fields.inn())); assertTrue(text.contains(fields.signerName()));
                for (String prefix : fields.gs1Codes()) assertTrue(text.contains(prefix)); assertTrue(text.contains("декабря 2027"));
                assertTrue(text.contains("просит предоставить дополнительный префикс"));
                var before = new PDFRenderer(unsigned).renderImage(0); var after = new PDFRenderer(signed).renderImage(0);
                int changed = 0, outside = 0;
                var area = preview.signature();
                for (int y = 0; y < before.getHeight(); y++) for (int x = 0; x < before.getWidth(); x++) {
                    if (before.getRGB(x, y) == after.getRGB(x, y)) continue; changed++;
                    double nx = (double) x / before.getWidth(), ny = 1 - (double) y / before.getHeight();
                    if (nx < area.x() - .004 || nx > area.x() + area.width() + .004 || ny < area.y() - .004 || ny > area.y() + area.height() + .004) outside++;
                }
                assertTrue(changed > 30, "Signed PDF contains real drawn ink"); assertEquals(0, outside, "Ink is confined to the actual underscore signature field");
            }
            assertFalse(ink.isEmpty(), "Caller owns the transient buffer and closes it with the dialog");
        }
    }

    @Test void allOneHundredGcpValuesCanBeFilledWithoutDroppingOtherPrefixCodes() throws Exception {
        var codes = java.util.stream.IntStream.range(1000, 1100).mapToObj(Integer::toString).toList();
        var many = new Fields(fields.legalName(), fields.inn(), codes, fields.date(), fields.signerName());
        byte[] filled = service.fillWord(statement(false), many);
        try (var document = new XWPFDocument(new ByteArrayInputStream(filled))) { assertTrue(document.getParagraphs().get(6).getText().contains(String.join(", ", codes))); }
        var preview = service.previewPdf(statement(false), many);
        try (var pdf = Loader.loadPDF(preview.pdf())) { String text = new PDFTextStripper().getText(pdf); for (String code : codes) assertTrue(text.contains(code)); }
        assertThrows(IllegalArgumentException.class, () -> new Fields("Fixture", fields.inn(), List.of("123", "4601234"), fields.date(), fields.signerName()));
        assertThrows(IllegalArgumentException.class, () -> new Fields("Fixture", fields.inn(), List.of("4601234", "4601234"), fields.date(), fields.signerName()));
    }

    @Test void rejectsActiveContentExternalRelationshipsAndXmlEntities() throws Exception {
        var external = parts(statement(false));
        external.put("word/_rels/document.xml.rels", ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"evil\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"https://example.invalid/\" TargetMode=\"External\"/></Relationships>").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.inspect(zip(external)));
        var macro = parts(statement(false)); macro.put("word/vbaProject.bin", new byte[]{1}); assertThrows(IllegalArgumentException.class, () -> service.inspect(zip(macro)));
        var types = parts(statement(false)); types.put("[Content_Types].xml", new String(types.get("[Content_Types].xml"), StandardCharsets.UTF_8).replace("wordprocessingml.document.main+xml", "wordprocessingml.document.macroEnabled.main+xml").getBytes(StandardCharsets.UTF_8)); assertThrows(IllegalArgumentException.class, () -> service.inspect(zip(types)));
        var entities = parts(statement(false)); entities.put("word/document.xml", ("<!DOCTYPE document [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>&secret;</w:t></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> service.inspect(zip(entities)));
    }

    @Test void refusesUnsupportedLayoutRatherThanSilentlyDiscardingIt() throws Exception {
        var table = parts(statement(false));
        table.put("word/document.xml", new String(table.get("word/document.xml"), StandardCharsets.UTF_8).replace("</w:body>", "<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body>").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.inspect(zip(table)));
        var unknownFont = parts(statement(false)); unknownFont.put("word/document.xml", new String(unknownFont.get("word/document.xml"), StandardCharsets.UTF_8).replace("Times New Roman", "Unprovided Font").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.previewPdf(zip(unknownFont), fields));
        var noSignature = parts(statement(false)); noSignature.put("word/document.xml", new String(noSignature.get("word/document.xml"), StandardCharsets.UTF_8).replace("____________________", "").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.inspect(zip(noSignature)));
    }

    @Test void boundedZipImportRejectsOversizedPartsAndTraversalNames() throws Exception {
        var oversized = parts(statement(false)); oversized.put("docProps/app.xml", new byte[1024 * 1024 + 1]);
        assertThrows(IllegalArgumentException.class, () -> service.inspect(zip(oversized)));
        var traversal = parts(statement(false)); traversal.put("../outside.xml", "<x/>".getBytes(StandardCharsets.UTF_8)); assertThrows(IllegalArgumentException.class, () -> service.inspect(zip(traversal)));
        assertThrows(IllegalArgumentException.class, () -> service.inspect(new byte[10 * 1024 * 1024 + 1]));
    }

    @Test void signaturesAndLicensingFailBeforeOpeningUntrustedWordAndMetadataDoesNotLeakOldIdentity() throws Exception {
        try (var empty = new Gs1SignatureInk()) { assertThrows(IllegalArgumentException.class, () -> service.signedPdf(statement(false), fields, empty)); }
        var previous = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(directory.resolve("license")); var ink = signature()) {
            LicenseRuntime.install(denied);
            assertThrows(LicenseDeniedException.class, () -> service.fillWord(null, fields));
            assertThrows(LicenseDeniedException.class, () -> service.previewPdf(null, fields));
            assertThrows(LicenseDeniedException.class, () -> service.signedPdf(null, fields, ink));
            assertNotNull(service.inspect(statement(false)), "Inspecting an imported local template does not make remote changes");
        } finally { LicenseRuntime.install(previous); }
        var filled = parts(service.fillWord(statement(false), fields));
        String core = new String(filled.get("docProps/core.xml"), StandardCharsets.UTF_8);
        assertFalse(core.contains("Old Template Author")); assertFalse(fields.toString().contains(fields.inn())); assertFalse(fields.toString().contains(fields.legalName()));
    }

    private static Gs1SignatureInk signature() { var ink = new Gs1SignatureInk(); ink.start(.1, .8); ink.append(.3, .2); ink.append(.5, .7); ink.append(.85, .3); ink.end(); return ink; }
    private static byte[] statement(boolean tokens) throws Exception {
        try (var document = new XWPFDocument()) {
            document.getProperties().getCoreProperties().setCreator("Old Template Author");
            for (String value : List.of("Директору", "Ассоциации", "идентификации ГС1 РУС", "Получателю")) paragraph(document, value, ParagraphAlignment.RIGHT, false, 0);
            paragraph(document, "", ParagraphAlignment.LEFT, false, 0); paragraph(document, "ЗАЯВЛЕНИЕ", ParagraphAlignment.CENTER, true, 240);
            if (tokens) {
                var p = document.createParagraph(); p.setAlignment(ParagraphAlignment.BOTH); p.setSpacingBefore(240);
                styled(p, "${companyN", true); styled(p, "ame} (ИНН ${inn}, номер предприятия в ЮНИСКАН/ГС1 РУС: ${gs1Codes}) просит предоставить дополнительный префикс в связи с окончанием ранее присвоенного диапазона.", false);
            } else paragraph(document, "OLD COMPANY (ИНН 7707083893, номер предприятия в ЮНИСКАН/ГС1 РУС: 4601234, 4605678) просит предоставить дополнительный префикс в связи с окончанием ранее присвоенного диапазона.", ParagraphAlignment.BOTH, false, 240);
            paragraph(document, "", ParagraphAlignment.LEFT, false, 480);
            paragraph(document, tokens ? "${date}" : "«1» января 2020 г.", ParagraphAlignment.LEFT, false, 480);
            paragraph(document, (tokens ? "${signerName}" : "OLD SIGNER") + "     ____________________", ParagraphAlignment.LEFT, false, 0);
            paragraph(document, "                              Фамилия, имя, отчество                          подпись", ParagraphAlignment.LEFT, false, 0);
            paragraph(document, "М.П.", ParagraphAlignment.LEFT, false, 240);
            var output = new ByteArrayOutputStream(); document.write(output); return output.toByteArray();
        }
    }
    private static void paragraph(XWPFDocument document, String value, ParagraphAlignment alignment, boolean bold, int before) { var p = document.createParagraph(); p.setAlignment(alignment); if (before > 0) p.setSpacingBefore(before); styled(p, value, bold); }
    private static XWPFRun styled(XWPFParagraph paragraph, String text, boolean bold) { var run = paragraph.createRun(); run.setFontFamily("Times New Roman"); run.setFontSize(12); run.setBold(bold); run.setText(text); return run; }
    private static Map<String, byte[]> parts(byte[] bytes) throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) { ZipEntry entry; while ((entry = zip.getNextEntry()) != null) parts.put(entry.getName(), zip.readAllBytes()); } return parts;
    }
    private static byte[] zip(Map<String, byte[]> parts) throws Exception {
        var output = new ByteArrayOutputStream(); try (var zip = new ZipOutputStream(output)) { for (var part : parts.entrySet()) { zip.putNextEntry(new ZipEntry(part.getKey())); zip.write(part.getValue()); zip.closeEntry(); } } return output.toByteArray();
    }
}
