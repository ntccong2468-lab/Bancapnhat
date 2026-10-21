package com.vncode.app.features.gs1;

import com.vncode.app.features.gs1.Gs1PrefixDocumentService.Area;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.gs1.Gs1MailClient;
import com.vncode.app.integration.gs1.Gs1Membership;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Explicit seller-supplied DOCX imports. Supports simple one-page prefix statements, not arbitrary Word layouts.
 * Literal text/runs remain in Word; PDF preserves the supported paragraph/run styles and renders real transient ink.
 */
public final class Gs1WordPrefixService {
    public record Fields(String legalName, String inn, List<String> gs1Codes, LocalDate date, String signerName) {
        public Fields {
            legalName = field(legalName, 500); Gs1Membership.requireInn(inn); signerName = field(signerName, 200); Objects.requireNonNull(date);
            if (date.getYear() < 1900 || date.getYear() > 9999) throw invalid("fields");
            gs1Codes = List.copyOf(gs1Codes);
            if (gs1Codes.isEmpty() || gs1Codes.size() > 100 || gs1Codes.stream().anyMatch(code -> code == null || !code.matches("[0-9]{4,12}")) ||
                    gs1Codes.stream().distinct().count() != gs1Codes.size()) throw invalid("fields");
        }
        @Override public String toString() { return "Gs1WordFields[redacted]"; }
    }
    public record TemplateDescription(String sha256, int paragraphs, String sourceType, String fontPolicy, String pagePolicy, List<String> fields) {
        public TemplateDescription { fields = List.copyOf(fields); }
    }
    public record PdfPreview(byte[] pdf, Area signature, float width, float height, TemplateDescription description) {
        public PdfPreview { pdf = pdf.clone(); }
        @Override public byte[] pdf() { return pdf.clone(); }
        @Override public String toString() { return "Gs1WordPdfPreview[redacted]"; }
    }
    private record Source(Map<String, byte[]> parts, org.w3c.dom.Document xml, List<Element> paragraphs, Binding binding, Geometry geometry, TemplateDescription description) { }
    private record Replacement(int start, int end, String value) { }
    private record Span(int paragraph, int start, int end, String field) { }
    private record Binding(List<Span> spans, int signatureParagraph) { }
    private record Geometry(float width, float height, float left, float right, float top, float bottom) { }
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int MAX_ZIP_ENTRIES = 64, MAX_PART_BYTES = 1024 * 1024, MAX_EXPANDED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PARAGRAPHS = 40, MAX_TEXT = 12000;
    private static final List<String> FIELD_NAMES = List.of("companyName", "inn", "gs1Codes", "date", "signerName");
    private static final Set<String> ELEMENTS = Set.of("document", "body", "p", "pPr", "r", "rPr", "t", "jc", "spacing", "ind", "rFonts", "sz", "szCs", "b", "bCs", "i", "iCs", "u", "color", "lang", "noProof", "sectPr", "pgSz", "pgMar");
    private static final Map<String, Set<String>> CHILDREN = Map.of(
            "document", Set.of("body"), "body", Set.of("p", "sectPr"), "p", Set.of("pPr", "r"),
            "pPr", Set.of("jc", "spacing", "ind"), "r", Set.of("rPr", "t"),
            "rPr", Set.of("rFonts", "sz", "szCs", "b", "bCs", "i", "iCs", "u", "color", "lang", "noProof", "spacing"),
            "sectPr", Set.of("pgSz", "pgMar"));
    private static final Pattern COMPANY = Pattern.compile("(?iu)^(\\s*)(.+?)(\\s*\\(\\s*ИНН\\s+)([0-9]{10}|[0-9]{12})(\\s*,\\s*номер предприятия в ЮНИСКАН/ГС1 РУС\\s*:\\s*)([0-9]{4,12}(?:\\s*,\\s*[0-9]{4,12})*)(\\s*\\).*)$");
    private static final Pattern DATE = Pattern.compile("(?iu)«([0-9]{1,2})»\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+([0-9]{4})\\s+г\\.");
    private static final Pattern SIGNER = Pattern.compile("(?iu)^(\\s*(?:РУКОВОДИТЕЛЬ\\s+)?)([\\p{L}][\\p{L} .’'\\-]*?)(\\s+)(_{5,})(\\s*)$");
    private static final String[] MONTHS = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    public TemplateDescription inspect(byte[] suppliedWord) throws IOException { return read(suppliedWord).description(); }

    public byte[] fillWord(byte[] suppliedWord, Fields fields) throws IOException {
        LicenseRuntime.require(Permission.GS1); Objects.requireNonNull(fields);
        var source = read(suppliedWord); return fill(source, fields);
    }

    public PdfPreview previewPdf(byte[] suppliedWord, Fields fields) throws IOException {
        LicenseRuntime.require(Permission.GS1); Objects.requireNonNull(fields);
        var source = read(suppliedWord); return render(source, fields, null);
    }

    public Gs1MailClient.Attachment signedPdf(byte[] suppliedWord, Fields fields, Gs1SignatureInk ink) throws IOException {
        LicenseRuntime.require(Permission.GS1); Objects.requireNonNull(fields); Objects.requireNonNull(ink);
        if (ink.isEmpty()) throw invalid("signature_required");
        var result = render(read(suppliedWord), fields, ink);
        return new Gs1MailClient.Attachment("GS1-prefix-request.pdf", "application/pdf", result.pdf());
    }

    private static Source read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 4 || bytes.length > Gs1MailClient.MAX_ATTACHMENT || bytes[0] != 'P' || bytes[1] != 'K') throw invalid("size");
        Map<String, byte[]> parts = new LinkedHashMap<>(); int expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (parts.size() >= MAX_ZIP_ENTRIES || !allowedPart(name) || parts.containsKey(name)) throw invalid("unsupported_package");
                byte[] content = zip.readNBytes(MAX_PART_BYTES + 1);
                if (content.length > MAX_PART_BYTES || expanded + content.length > MAX_EXPANDED_BYTES) throw invalid("expanded_size");
                expanded += content.length; parts.put(name, content);
                var xml = xml(content); validateRelationshipsAndTypes(name, xml);
            }
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw failure("invalid_template"); }
        if (!parts.containsKey("[Content_Types].xml") || !parts.containsKey("_rels/.rels") || !parts.containsKey("word/document.xml")) throw invalid("invalid_template");
        boolean mainDocument = false, mainType = false;
        var rootRelations = xml(parts.get("_rels/.rels")).getDocumentElement().getChildNodes();
        for (int i = 0; i < rootRelations.getLength(); i++) if (rootRelations.item(i) instanceof Element relationship &&
                relationship.getAttribute("Type").equals("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument") && relationship.getAttribute("Target").equals("word/document.xml")) mainDocument = true;
        var contentTypes = xml(parts.get("[Content_Types].xml")).getDocumentElement().getChildNodes();
        for (int i = 0; i < contentTypes.getLength(); i++) if (contentTypes.item(i) instanceof Element type && type.getAttribute("PartName").equals("/word/document.xml") &&
                type.getAttribute("ContentType").equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")) mainType = true;
        if (!mainDocument || !mainType) throw invalid("invalid_template");
        var xml = xml(parts.get("word/document.xml"));
        validateWordTree(xml.getDocumentElement());
        if (!"document".equals(xml.getDocumentElement().getLocalName())) throw invalid("invalid_template");
        var paragraphs = children(first(xml.getDocumentElement(), "body"), "p");
        if (paragraphs.isEmpty() || paragraphs.size() > MAX_PARAGRAPHS || paragraphs.stream().mapToInt(p -> text(p).length()).sum() > MAX_TEXT) throw invalid("layout_limits");
        var binding = binding(paragraphs);
        var geometry = geometry(xml);
        var description = new TemplateDescription(sha256(bytes), paragraphs.size(), "seller-imported-docx", "Times New Roman or metric-compatible Liberation Serif; Arial uses bundled Arial", "Explicit Word geometry; otherwise A4 with 20 mm margins", FIELD_NAMES);
        return new Source(parts, xml, paragraphs, binding, geometry, description);
    }
    private static boolean allowedPart(String name) {
        return name.equals("[Content_Types].xml") || name.equals("_rels/.rels") ||
                name.matches("docProps/(?:core|app)\\.xml") || name.matches("word/(?:document|styles|settings|fontTable|webSettings)\\.xml") ||
                name.equals("word/_rels/document.xml.rels") || name.matches("word/theme/theme[0-9]+\\.xml");
    }
    private static org.w3c.dom.Document xml(byte[] bytes) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException failure) throws org.xml.sax.SAXException { throw failure; }
                @Override public void error(org.xml.sax.SAXParseException failure) throws org.xml.sax.SAXException { throw failure; }
            });
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception failure) { throw failure("invalid_xml"); }
    }
    private static void validateRelationshipsAndTypes(String name, org.w3c.dom.Document xml) {
        var nodes = xml.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element element) {
            if (name.endsWith(".rels")) {
                String target = element.getAttribute("Target"), type = element.getAttribute("Type");
                if (element.getAttribute("TargetMode").equalsIgnoreCase("External") || target.contains(":") || target.contains("\\") || target.startsWith("/") || Arrays.asList(target.split("/")).contains("..") ||
                        type.toLowerCase(Locale.ROOT).matches(".*/(?:vbaproject|oleobject|attachedtemplate|package)$")) throw invalid("external_or_active_content");
            }
            if (name.equals("[Content_Types].xml") && element.getAttribute("ContentType").toLowerCase(Locale.ROOT).contains("macro")) throw invalid("external_or_active_content");
        }
    }
    private static void validateWordTree(Element element) {
        if (!W.equals(element.getNamespaceURI()) || !ELEMENTS.contains(element.getLocalName())) throw invalid("unsupported_layout");
        if (element.getLocalName().equals("rFonts")) {
            for (int i = 0; i < element.getAttributes().getLength(); i++) {
                Node attribute = element.getAttributes().item(i);
                if (attribute.getLocalName() != null && attribute.getLocalName().toLowerCase(Locale.ROOT).contains("theme")) throw invalid("unsupported_layout");
            }
        }
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element child) {
            if (!CHILDREN.getOrDefault(element.getLocalName(), Set.of()).contains(child.getLocalName())) throw invalid("unsupported_layout");
            validateWordTree(child);
        }
    }
    private static Binding binding(List<Element> paragraphs) {
        List<Span> spans = new ArrayList<>(); int signatureParagraph = -1;
        boolean placeholders = paragraphs.stream().anyMatch(p -> text(p).contains("${companyName}"));
        if (placeholders) {
            for (String field : FIELD_NAMES) {
                int count = 0;
                for (int i = 0; i < paragraphs.size(); i++) {
                    String text = text(paragraphs.get(i)), token = "${" + field + "}"; int offset = text.indexOf(token);
                    if (offset >= 0) { if (text.indexOf(token, offset + token.length()) >= 0) throw invalid("template_fields"); spans.add(new Span(i, offset, offset + token.length(), field)); count++; if (field.equals("signerName")) signatureParagraph = i; }
                }
                if (count != 1) throw invalid("template_fields");
            }
        } else {
            int companies = 0, dates = 0, signers = 0;
            for (int i = 0; i < paragraphs.size(); i++) {
                String text = text(paragraphs.get(i)); Matcher company = COMPANY.matcher(text), date = DATE.matcher(text), signer = SIGNER.matcher(text);
                if (company.matches()) {
                    spans.add(new Span(i, company.start(2), company.end(2), "companyName")); spans.add(new Span(i, company.start(4), company.end(4), "inn")); spans.add(new Span(i, company.start(6), company.end(6), "gs1Codes")); companies++;
                }
                if (date.find()) { spans.add(new Span(i, date.start(), date.end(), "date")); dates++; if (date.find()) throw invalid("template_fields"); }
                if (signer.matches() && i + 1 < paragraphs.size() && text(paragraphs.get(i + 1)).contains("Фамилия, имя, отчество")) {
                    spans.add(new Span(i, signer.start(2), signer.end(2), "signerName")); signatureParagraph = i; signers++;
                }
            }
            if (companies != 1 || dates != 1 || signers != 1) throw invalid("template_fields");
        }
        if (signatureParagraph < 0 || !text(paragraphs.get(signatureParagraph)).matches("(?s).*_{5,}.*")) throw invalid("signature_field");
        return new Binding(List.copyOf(spans), signatureParagraph);
    }
    private static byte[] fill(Source source, Fields fields) throws IOException {
        Map<Integer, List<Replacement>> replacements = new LinkedHashMap<>();
        for (var span : source.binding().spans()) replacements.computeIfAbsent(span.paragraph(), ignored -> new ArrayList<>()).add(new Replacement(span.start(), span.end(), value(fields, span.field())));
        for (var entry : replacements.entrySet()) {
            entry.getValue().sort(Comparator.comparingInt(Replacement::start).reversed());
            for (var replacement : entry.getValue()) replaceText(source.paragraphs().get(entry.getKey()), replacement);
        }
        source.parts().put("word/document.xml", serialize(source.xml()));
        // Imported template authors may belong to a previous seller; metadata is not part of the reviewed letter.
        if (source.parts().containsKey("docProps/core.xml")) {
            var metadata = xml(source.parts().get("docProps/core.xml"));
            for (Node node = metadata.getDocumentElement().getFirstChild(); node != null;) {
                Node next = node.getNextSibling(); if (node instanceof Element element && Set.of("creator", "lastModifiedBy", "description", "keywords", "subject", "title").contains(element.getLocalName())) metadata.getDocumentElement().removeChild(node); node = next;
            }
            source.parts().put("docProps/core.xml", serialize(metadata));
        }
        return zip(source.parts());
    }
    private static String value(Fields fields, String name) {
        return switch (name) {
            case "companyName" -> fields.legalName(); case "inn" -> fields.inn(); case "gs1Codes" -> String.join(", ", fields.gs1Codes());
            case "date" -> "«" + fields.date().getDayOfMonth() + "» " + MONTHS[fields.date().getMonthValue() - 1] + " " + fields.date().getYear() + " г.";
            case "signerName" -> fields.signerName(); default -> throw invalid("fields");
        };
    }
    private static void replaceText(Element paragraph, Replacement replacement) {
        var nodes = paragraph.getElementsByTagNameNS(W, "t"); int offset = 0; boolean inserted = false;
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i); String text = node.getTextContent(); int start = offset, end = offset + text.length(); offset = end;
            if (end <= replacement.start() || start >= replacement.end()) continue;
            int from = Math.max(0, replacement.start() - start), to = Math.min(text.length(), replacement.end() - start);
            node.setTextContent(text.substring(0, from) + (inserted ? "" : replacement.value()) + text.substring(to)); inserted = true;
            ((Element) node).setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
        }
        if (!inserted) throw invalid("template_fields");
    }
    private static byte[] serialize(org.w3c.dom.Document document) throws IOException {
        try {
            var factory = TransformerFactory.newInstance(); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var output = new ByteArrayOutputStream(); factory.newTransformer().transform(new DOMSource(document), new StreamResult(output)); return output.toByteArray();
        } catch (Exception failure) { throw failure("invalid_xml"); }
    }
    private static byte[] zip(Map<String, byte[]> parts) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var part : parts.entrySet()) { zip.putNextEntry(new ZipEntry(part.getKey())); zip.write(part.getValue()); zip.closeEntry(); }
        }
        if (output.size() > Gs1MailClient.MAX_ATTACHMENT) throw invalid("size"); return output.toByteArray();
    }
    private static Geometry geometry(org.w3c.dom.Document xml) {
        float width = PDRectangle.A4.getWidth(), height = PDRectangle.A4.getHeight(), left = 56.7f, right = 56.7f, top = 56.7f, bottom = 56.7f;
        var sizes = xml.getElementsByTagNameNS(W, "pgSz"); var margins = xml.getElementsByTagNameNS(W, "pgMar");
        if (sizes.getLength() > 1 || margins.getLength() > 1) throw invalid("unsupported_layout");
        if (sizes.getLength() == 1) { var size = (Element) sizes.item(0); width = number(size, "w", width * 20) / 20; height = number(size, "h", height * 20) / 20; }
        if (margins.getLength() == 1) { var margin = (Element) margins.item(0); left = number(margin, "left", left * 20) / 20; right = number(margin, "right", right * 20) / 20; top = number(margin, "top", top * 20) / 20; bottom = number(margin, "bottom", bottom * 20) / 20; }
        if (width < 300 || height < 300 || width > 1000 || height > 1000 || left < 18 || right < 18 || top < 18 || bottom < 18 || width - left - right < 200 || height - top - bottom < 200) throw invalid("layout_limits");
        return new Geometry(width, height, left, right, top, bottom);
    }
    private static float number(Element element, String attribute, float fallback) {
        String text = element.getAttributeNS(W, attribute); if (text.isBlank()) return fallback;
        try { float value = Float.parseFloat(text); if (!Float.isFinite(value)) throw invalid("unsupported_layout"); return value; }
        catch (NumberFormatException failure) { throw invalid("unsupported_layout"); }
    }
    private record Glyph(String text, PDType0Font font, float size, boolean underline, Color color, float width) { }
    private record Line(List<Glyph> glyphs, float width) { }

    private static PdfPreview render(Source source, Fields fields, Gs1SignatureInk ink) throws IOException {
        byte[] word = fill(source, fields); var geometry = source.geometry(); Area signature = null;
        try (var document = new XWPFDocument(new ByteArrayInputStream(word)); var pdf = new PDDocument()) {
            var page = new PDPage(new PDRectangle(geometry.width(), geometry.height())); pdf.addPage(page);
            var fonts = new Fonts(pdf); float cursor = geometry.height() - geometry.top();
            try (var content = new PDPageContentStream(pdf, page)) {
                for (int index = 0; index < document.getParagraphs().size(); index++) {
                    XWPFParagraph paragraph = document.getParagraphs().get(index);
                    validateParagraph(paragraph);
                    cursor -= Math.max(0, paragraph.getSpacingBefore()) / 20f;
                    float left = geometry.left() + Math.max(0, paragraph.getIndentationLeft()) / 20f;
                    float available = geometry.width() - geometry.right() - Math.max(0, paragraph.getIndentationRight()) / 20f - left;
                    List<Glyph> glyphs = new ArrayList<>();
                    for (XWPFRun run : paragraph.getRuns()) {
                        float size = run.getFontSizeAsDouble() == null ? 12 : run.getFontSizeAsDouble().floatValue();
                        if (size < 8 || size > 24 || run.getCharacterSpacing() != 0 || (run.getTextPosition() != 0 && run.getTextPosition() != -1) || run.isStrikeThrough() || run.isDoubleStrikeThrough()) throw invalid("unsupported_layout");
                        for (var range : XWPFRun.FontCharRange.values()) {
                            String regional = run.getFontFamily(range);
                            if (regional != null && run.getFontFamily() != null && !regional.equalsIgnoreCase(run.getFontFamily())) throw invalid("unsupported_font");
                        }
                        PDType0Font font = fonts.get(run.getFontFamily(), run.isBold(), run.isItalic());
                        Color color = run.getColor() == null || run.getColor().equalsIgnoreCase("auto") ? Color.BLACK : color(run.getColor());
                        boolean underline = run.getUnderline() == org.apache.poi.xwpf.usermodel.UnderlinePatterns.SINGLE;
                        if (run.getUnderline() != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE && !underline) throw invalid("unsupported_layout");
                        for (String letter : run.text().codePoints().mapToObj(code -> new String(Character.toChars(code))).toList())
                            glyphs.add(new Glyph(letter, font, size, underline, color, font.getStringWidth(letter) / 1000 * size));
                    }
                    float firstIndent = Math.max(0, paragraph.getIndentationFirstLine()) / 20f - Math.max(0, paragraph.getIndentationHanging()) / 20f;
                    List<Line> lines = wrap(glyphs, available, firstIndent);
                    float largest = glyphs.stream().map(Glyph::size).max(Float::compare).orElse(12f);
                    float leading = largest * 1.2f;
                    for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                        var line = lines.get(lineIndex); float indent = lineIndex == 0 ? firstIndent : 0, width = available - indent;
                        float x = left + indent;
                        if (paragraph.getAlignment() == ParagraphAlignment.RIGHT) x += width - line.width();
                        else if (paragraph.getAlignment() == ParagraphAlignment.CENTER) x += (width - line.width()) / 2;
                        float y = cursor - largest; if (y < geometry.bottom()) throw invalid("page_overflow");
                        long spaces = line.glyphs().stream().filter(glyph -> glyph.text().equals(" ")).count();
                        float justify = paragraph.getAlignment() == ParagraphAlignment.BOTH && lineIndex < lines.size() - 1 && spaces > 0 ? (width - line.width()) / spaces : 0;
                        float signatureStart = -1, signatureEnd = -1;
                        for (var glyph : line.glyphs()) {
                            content.setNonStrokingColor(glyph.color()); content.beginText(); content.setFont(glyph.font(), glyph.size()); content.newLineAtOffset(x, y); content.showText(glyph.text()); content.endText();
                            if (glyph.underline()) { content.setStrokingColor(glyph.color()); content.setLineWidth(.5f); content.moveTo(x, y - 1); content.lineTo(x + glyph.width(), y - 1); content.stroke(); }
                            if (index == source.binding().signatureParagraph() && glyph.text().equals("_")) { if (signatureStart < 0) signatureStart = x; signatureEnd = x + glyph.width(); }
                            x += glyph.width() + (glyph.text().equals(" ") ? justify : 0);
                        }
                        if (signatureStart >= 0 && signatureEnd - signatureStart >= 20) {
                            if (signature != null) throw invalid("signature_field");
                            signature = new Area(signatureStart / geometry.width(), (y + 2) / geometry.height(), (signatureEnd - signatureStart) / geometry.width(), 22 / geometry.height());
                        }
                        cursor -= leading;
                    }
                    cursor -= Math.max(0, paragraph.getSpacingAfter()) / 20f;
                }
                if (signature == null) throw invalid("signature_field");
                if (ink != null) drawSignature(content, ink, signature, geometry);
            }
            var output = new ByteArrayOutputStream(); pdf.save(output);
            if (output.size() > Gs1MailClient.MAX_ATTACHMENT) throw invalid("size");
            return new PdfPreview(output.toByteArray(), signature, geometry.width(), geometry.height(), source.description());
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("gs1.word_")) throw failure;
            throw failure("render_failed");
        }
        catch (Exception failure) { throw failure("render_failed"); }
        finally { Arrays.fill(word, (byte) 0); }
    }
    private static void validateParagraph(XWPFParagraph paragraph) {
        if (paragraph.isPageBreak() || paragraph.getIndentationLeft() > 2000 || paragraph.getIndentationRight() > 2000 || paragraph.getIndentationFirstLine() > 2000 || paragraph.getIndentationHanging() > 2000 ||
                !Set.of(ParagraphAlignment.LEFT, ParagraphAlignment.RIGHT, ParagraphAlignment.CENTER, ParagraphAlignment.BOTH).contains(paragraph.getAlignment())) throw invalid("unsupported_layout");
        if (paragraph.getSpacingBeforeLines() > 0 || paragraph.getSpacingAfterLines() > 0 || paragraph.getSpacingBetween() > 1.01) throw invalid("unsupported_layout");
    }
    private static List<Line> wrap(List<Glyph> glyphs, float width, float firstIndent) {
        if (width < 120 || width - firstIndent < 120) throw invalid("layout_limits");
        List<Line> lines = new ArrayList<>(); int start = 0;
        while (start < glyphs.size()) {
            float limit = width - (lines.isEmpty() ? firstIndent : 0), used = 0; int end = start, lastSpace = -1;
            while (end < glyphs.size() && used + glyphs.get(end).width() <= limit) { used += glyphs.get(end).width(); if (glyphs.get(end).text().equals(" ")) lastSpace = end; end++; }
            if (end == start) throw invalid("layout_limits");
            if (end < glyphs.size() && lastSpace >= start) end = lastSpace + 1;
            var line = new ArrayList<>(glyphs.subList(start, end));
            if (end < glyphs.size()) while (!line.isEmpty() && line.getLast().text().equals(" ")) line.removeLast();
            lines.add(new Line(List.copyOf(line), (float) line.stream().mapToDouble(Glyph::width).sum())); start = end;
        }
        if (lines.isEmpty()) lines.add(new Line(List.of(), 0)); return lines;
    }
    private static void drawSignature(PDPageContentStream content, Gs1SignatureInk ink, Area area, Geometry geometry) throws IOException {
        content.setStrokingColor(Color.BLACK); content.setLineWidth(1);
        var strokes = ink.snapshot();
        try {
            for (var stroke : strokes) {
                if (stroke.length < 2) continue;
                content.moveTo((float) ((area.x() + stroke[0][0] * area.width()) * geometry.width()), (float) ((area.y() + (1 - stroke[0][1]) * area.height()) * geometry.height()));
                for (int i = 1; i < stroke.length; i++) content.lineTo((float) ((area.x() + stroke[i][0] * area.width()) * geometry.width()), (float) ((area.y() + (1 - stroke[i][1]) * area.height()) * geometry.height())); content.stroke();
            }
        } finally { for (var stroke : strokes) for (var point : stroke) Arrays.fill(point, 0); }
    }
    private static final class Fonts {
        private final PDDocument document; private final Map<String, PDType0Font> loaded = new LinkedHashMap<>();
        Fonts(PDDocument document) { this.document = document; }
        PDType0Font get(String requested, boolean bold, boolean italic) throws IOException {
            String family = requested == null || requested.isBlank() ? "times new roman" : requested.toLowerCase(Locale.ROOT);
            if (!Set.of("times new roman", "liberation serif", "arial").contains(family)) throw invalid("unsupported_font");
            String key = family + ":" + bold + ":" + italic;
            if (loaded.containsKey(key)) return loaded.get(key);
            PDType0Font font;
            if (family.equals("arial")) {
                if (bold || italic) throw invalid("unsupported_font");
                try (var stream = Gs1WordPrefixService.class.getResourceAsStream("/com/vncode/app/assets/fonts/ARIAL.TTF")) { if (stream == null) throw failure("font_unavailable"); font = PDType0Font.load(document, stream); }
            } else {
                Path path = serifFont(bold, italic); if (path == null) throw failure("font_unavailable");
                try (var stream = Files.newInputStream(path)) { font = PDType0Font.load(document, stream); }
            }
            loaded.put(key, font); return font;
        }
        private static Path serifFont(boolean bold, boolean italic) {
            String root = System.getenv("SystemRoot");
            String windows = bold && italic ? "timesbi.ttf" : bold ? "timesbd.ttf" : italic ? "timesi.ttf" : "times.ttf";
            if (root != null) { Path path = Path.of(root, "Fonts", windows); if (Files.isRegularFile(path)) return path; }
            String style = bold && italic ? "BoldItalic" : bold ? "Bold" : italic ? "Italic" : "Regular";
            for (String folder : List.of("/usr/share/fonts/truetype/liberation", "/usr/share/fonts/truetype/liberation2")) {
                Path path = Path.of(folder, "LiberationSerif-" + style + ".ttf"); if (Files.isRegularFile(path)) return path;
            }
            return null;
        }
    }
    private static Color color(String value) { if (!value.matches("[0-9a-fA-F]{6}")) throw invalid("unsupported_layout"); return new Color(Integer.parseInt(value, 16)); }
    private static String field(String value, int limit) { if (value == null || value.isBlank() || value.length() > limit || value.chars().anyMatch(Character::isISOControl)) throw invalid("fields"); return value.strip(); }
    private static String text(Element paragraph) { var nodes = paragraph.getElementsByTagNameNS(W, "t"); var value = new StringBuilder(); for (int i = 0; i < nodes.getLength(); i++) value.append(nodes.item(i).getTextContent()); return value.toString(); }
    private static Element first(Element parent, String name) { var values = children(parent, name); if (values.size() != 1) throw invalid("unsupported_layout"); return values.getFirst(); }
    private static List<Element> children(Element parent, String name) { List<Element> result = new ArrayList<>(); for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element child && W.equals(child.getNamespaceURI()) && name.equals(child.getLocalName())) result.add(child); return result; }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException("gs1.word_" + code); }
    private static IOException failure(String code) { return new IOException("gs1.word_" + code); }
}
