package com.vncode.app.features.gs1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Inert, independently laid-out default derived from the public GS1 RUS form.
 * The active original Word package is not bundled or passed through a weaker validator.
 * Loading this template never imports a seller template, signs or sends a request.
 */
public final class Gs1OfficialPrefixTemplate {
    public record Metadata(String formId,String sourceRevision,String sourceUrl,String sourceSha256,
                           LocalDate retrievedOn,String adaptation) {}
    private static final Metadata METADATA=new Metadata("01.04.01","2010.07.02",
            "https://gs1ru.org/downloads/documents/F01_0401.docx",
            "79406c129a9efa6a32220c18228a0249d7f471741235e6846400ea35404429ed",LocalDate.of(2026,11,17),
            "Independent safe layout of the official visible wording; sample watermarks and active Word content omitted; "
                    +"header/footer labels moved to paragraphs; Times New Roman replaces Tahoma; INN/date added from reviewed profile. "
                    +"Capacity remains in the reviewed email. Seller review of letterhead, signature and stamp is still required.");
    private static final String RESOURCE="/com/vncode/app/gs1/gs1-prefix-01.04.01.xml";
    private static final String DOCUMENT_SHA256="601f78ba4474b071991c7bfc89c5d36f09286aa33c08d55699ab0bedb3ad57bb";
    private static final String CONTENT_TYPES="""
            <?xml version="1.0" encoding="UTF-8"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """;
    private static final String RELATIONSHIPS="""
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """;
    private Gs1OfficialPrefixTemplate() {}
    public static Metadata metadata(){return METADATA;}

    /** Same generic official request for both supported capacities; the reviewed mail states 999 or 99999.
     * An existing explicit seller import takes precedence in the caller.
     */
    public static byte[] defaultTemplate()throws IOException{
        byte[] document;
        try(var input=Gs1OfficialPrefixTemplate.class.getResourceAsStream(RESOURCE)){
            if(input==null)throw unavailable();
            document=input.readNBytes(64*1024+1);
        }
        if(document.length>64*1024||!DOCUMENT_SHA256.equals(sha256(document)))throw unavailable();
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
            part(zip,"[Content_Types].xml",CONTENT_TYPES.getBytes(StandardCharsets.UTF_8));
            part(zip,"_rels/.rels",RELATIONSHIPS.getBytes(StandardCharsets.UTF_8));
            part(zip,"word/document.xml",document);
        }
        byte[] template=output.toByteArray();
        // Keep the exact validator used for custom imports; no trusted-path bypass of Word safeguards.
        new Gs1WordPrefixService().inspect(template);
        return template;
    }
    private static void part(ZipOutputStream zip,String name,byte[] bytes)throws IOException{
        var entry=new ZipEntry(name);entry.setTime(0);zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry();
    }
    private static String sha256(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static IOException unavailable(){return new IOException("GS1 official default template is unavailable or altered");}
}
